package de.example.vk;

import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import de.example.vk.config.DataSourceConfig;
import de.example.vk.dev.DevDataInitializer;
import de.example.vk.repository.EventRepository;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Startet die eingebettete H2 mit ~12.000 Events und prueft Funktion und
 * Performance der oeffentlichen Suche (Spezifikation 30.1: < 500 ms).
 */
public class EventSearchIntegrationTest {

    private static HikariDataSource ds;
    private static EventRepository repo;

    @BeforeClass
    public static void setUp() throws Exception {
        ds = new DataSourceConfig().dataSource("h2", "", "", "");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        new DevDataInitializer(ds, "h2").afterPropertiesSet();
        repo = new EventRepository(jdbc, false);
    }

    @AfterClass
    public static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    public void datasetIsSeeded() {
        EventRepository.Query q = new EventRepository.Query();
        long total = repo.count(q);
        assertTrue("Es sollten viele veroeffentlichte Events vorhanden sein, waren: " + total,
                total > 8000);
    }

    @Test
    public void firstPageReturnsRequestedSize() {
        EventRepository.Query q = new EventRepository.Query();
        q.size = 20;
        assertEquals(20, repo.search(q).size());
    }

    @Test
    public void fullTextSearchFindsMatches() {
        EventRepository.Query q = new EventRepository.Query();
        q.q = "konzert";
        long total = repo.count(q);
        assertTrue("Volltextsuche 'konzert' sollte Treffer liefern", total > 0);
        assertTrue(repo.search(q).size() > 0);
    }

    @Test
    public void detailLoadsRelations() {
        EventRepository.Query q = new EventRepository.Query();
        q.size = 1;
        JsonObject first = repo.search(q).get(0).getAsJsonObject();
        String id = first.get("id").getAsString();
        JsonObject detail = repo.findPublishedByPublicId(id);
        assertNotNull(detail);
        assertTrue(detail.has("categories"));
        assertTrue(detail.has("organizers"));
        assertTrue(detail.has("offers"));
    }

    @Test
    public void searchIsFastOnLargeDataset() {
        EventRepository.Query q = new EventRepository.Query();
        q.q = "musik";
        q.size = 20;
        // Aufwaermen (JIT, Caches)
        repo.count(q);
        repo.search(q);

        long startNs = System.nanoTime();
        int iterations = 20;
        for (int i = 0; i < iterations; i++) {
            EventRepository.Query iq = new EventRepository.Query();
            iq.q = (i % 2 == 0) ? "musik" : "theater";
            iq.page = 1 + (i % 5);
            repo.count(iq);
            repo.search(iq);
        }
        long avgMs = (System.nanoTime() - startNs) / iterations / 1_000_000;
        System.out.println("Durchschnittliche Suchzeit (count + page): " + avgMs + " ms");
        assertTrue("Suche sollte < 500 ms sein, war: " + avgMs + " ms", avgMs < 500);
    }
}
