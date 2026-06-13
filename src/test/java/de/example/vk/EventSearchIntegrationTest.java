package de.example.vk;

import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import de.example.vk.config.DataSourceConfig;
import de.example.vk.dev.DevDataInitializer;
import de.example.vk.repository.EventRepository;
import de.example.vk.util.ConfigVk;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Startet die eingebettete H2 mit mehreren Mandanten/VKs und prueft
 * Mandanten-Isolation sowie Such-Performance (Spezifikation 30.1: &lt; 500 ms).
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

    @After
    public void clearContext() {
        ConfigVk.clear();
    }

    @Test
    public void datasetIsSeeded() {
        ConfigVk.set(1L, 1L);
        long total = repo.count(new EventRepository.Query());
        assertTrue("VK 1 sollte viele veroeffentlichte Events haben, waren: " + total, total > 8000);
    }

    @Test
    public void firstPageReturnsRequestedSize() {
        ConfigVk.set(1L, 1L);
        EventRepository.Query q = new EventRepository.Query();
        q.size = 20;
        assertEquals(20, repo.search(q).size());
    }

    @Test
    public void fullTextSearchFindsMatches() {
        ConfigVk.set(1L, 1L);
        EventRepository.Query q = new EventRepository.Query();
        q.q = "konzert";
        assertTrue("Volltextsuche 'konzert' sollte Treffer liefern", repo.count(q) > 0);
        assertTrue(repo.search(q).size() > 0);
    }

    @Test
    public void detailLoadsRelations() {
        ConfigVk.set(1L, 1L);
        EventRepository.Query q = new EventRepository.Query();
        q.size = 1;
        JsonObject first = repo.search(q).get(0).getAsJsonObject();
        JsonObject detail = repo.findPublishedByPublicId(first.get("id").getAsString());
        assertNotNull(detail);
        assertTrue(detail.has("categories"));
        assertTrue(detail.has("organizers"));
        assertTrue(detail.has("offers"));
    }

    /** Kernanforderung Multi-Tenant: VKs sehen sich gegenseitig nicht. */
    @Test
    public void tenantsAreIsolated() {
        ConfigVk.set(1L, 1L);
        long vk1 = repo.count(new EventRepository.Query());

        ConfigVk.set(2L, 3L);
        long vk3 = repo.count(new EventRepository.Query());

        assertTrue("VK 1 (12000) sollte deutlich mehr Events haben als VK 3 (1500)", vk1 > vk3);
        assertTrue("VK 3 sollte > 0 Events haben", vk3 > 0);

        // Ein Event aus VK 1 darf unter VK 3 NICHT auffindbar sein.
        ConfigVk.set(1L, 1L);
        EventRepository.Query q = new EventRepository.Query();
        q.size = 1;
        String vk1EventId = repo.search(q).get(0).getAsJsonObject().get("id").getAsString();
        assertNotNull(repo.findPublishedByPublicId(vk1EventId));

        ConfigVk.set(2L, 3L);
        assertNull("Event aus VK 1 darf unter VK 3 nicht sichtbar sein",
                repo.findPublishedByPublicId(vk1EventId));
    }

    @Test
    public void searchFailsClosedWithoutTenantContext() {
        ConfigVk.clear();
        try {
            repo.count(new EventRepository.Query());
            org.junit.Assert.fail("Ohne Mandanten-Kontext darf keine Suche laufen");
        } catch (IllegalStateException expected) {
            // erwartet: fail-closed
        }
    }

    @Test
    public void searchIsFastOnLargeDataset() {
        ConfigVk.set(1L, 1L);
        EventRepository.Query warm = new EventRepository.Query();
        warm.q = "musik";
        repo.count(warm);
        repo.search(warm);

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
        System.out.println("Durchschnittliche Suchzeit (count + page) in VK 1: " + avgMs + " ms");
        assertTrue("Suche sollte < 500 ms sein, war: " + avgMs + " ms", avgMs < 500);
    }
}
