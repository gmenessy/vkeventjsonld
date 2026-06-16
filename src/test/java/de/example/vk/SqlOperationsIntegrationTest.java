package de.example.vk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariDataSource;
import de.example.vk.config.DataSourceConfig;
import de.example.vk.dev.DevDataInitializer;
import de.example.vk.repository.AdminEventRepository;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.CategoryRepository;
import de.example.vk.repository.EventRepository;
import de.example.vk.repository.EventWriteRepository;
import de.example.vk.repository.ImportRepository;
import de.example.vk.service.AdminEventService;
import de.example.vk.service.AuditService;
import de.example.vk.service.EventInputMapper;
import de.example.vk.service.EventValidator;
import de.example.vk.service.ExportService;
import de.example.vk.service.ImportService;
import de.example.vk.service.SelfServiceEventService;
import de.example.vk.service.ValidationException;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.CurrentUser;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Übt <b>alle SQL-Operationen</b> der Repositories/Services gegen die eingebettete H2:
 * Anlage eines Events mit sämtlichen jetzt pflegbaren Feldern (Einlass, Dauer, Preise,
 * Mitwirkende, Sponsoren, Bilder, Dokumente), Wiederlesen, Aktualisieren (Beziehungen
 * ersetzen), Selbsteintrags-Workflow, Redaktions-Workflow inkl. Versions-Snapshot,
 * Export, Import (CSV + JSON), Audit, Kategorie-Baum. Validiert damit, dass jede
 * INSERT/UPDATE/SELECT/DELETE-Anweisung syntaktisch und fachlich funktioniert.
 */
public class SqlOperationsIntegrationTest {

    private static HikariDataSource ds;
    private static NamedParameterJdbcTemplate jdbc;
    private static EventWriteRepository writeRepo;
    private static EventRepository eventRepo;
    private static SelfServiceEventService selfService;
    private static AdminEventService adminService;
    private static ExportService exportService;
    private static ImportService importService;
    private static CategoryRepository categoryRepo;

    private static final long USER_ID = 2L; // Seed: redaktion@vk.example (EDITOR)

    @BeforeClass
    public static void setUp() throws Exception {
        ds = new DataSourceConfig().dataSource("h2", "", "", "");
        jdbc = new NamedParameterJdbcTemplate(ds);
        new DevDataInitializer(ds, "h2").afterPropertiesSet();

        writeRepo = new EventWriteRepository(jdbc);
        eventRepo = new EventRepository(jdbc, false);
        categoryRepo = new CategoryRepository(jdbc);
        EventInputMapper mapper = new EventInputMapper();
        EventValidator validator = new EventValidator();
        ApprovalRepository approvalRepo = new ApprovalRepository(jdbc);
        AdminEventRepository adminRepo = new AdminEventRepository(jdbc);
        AuditService audit = new AuditService(jdbc);
        ImportRepository importRepo = new ImportRepository(jdbc);

        selfService = new SelfServiceEventService(writeRepo, eventRepo, approvalRepo, validator, mapper, audit);
        adminService = new AdminEventService(adminRepo, eventRepo, approvalRepo, audit, writeRepo, mapper, validator);
        exportService = new ExportService(jdbc);
        importService = new ImportService(importRepo, writeRepo, approvalRepo, mapper, validator, audit);
    }

    @AfterClass
    public static void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    private void context() {
        ConfigVk.set(1L, 1L);
        CurrentUser.set(new CurrentUser(USER_ID, "Test-Redaktion", 1L,
                new java.util.HashSet<String>(java.util.Arrays.asList("EDITOR", "REGISTERED"))));
    }

    @After
    public void clear() {
        ConfigVk.clear();
        CurrentUser.clear();
    }

    private String somePlacePublicId() {
        return jdbc.queryForObject(
                "SELECT PUBLIC_ID FROM VK_PLACE WHERE MANDANT_ID=1 AND VK_ID=1 ORDER BY ID FETCH FIRST 1 ROW ONLY",
                Collections.<String, Object>emptyMap(), String.class);
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM VK_AUDIT_LOG", Collections.<String, Object>emptyMap(),
                Long.class);
    }

    @Test
    public void fullRichEventLifecycleTouchesAllSql() {
        context();
        String place = somePlacePublicId();
        assertNotNull(place);
        long auditBefore = auditCount();

        JsonObject body = JsonParser.parseString("{"
                + "\"title\":\"SQL-Op Test Galakonzert\","
                + "\"shortDescription\":\"Ein festlicher Abend.\","
                + "\"description\":\"<p>Mit <strong>Orchester</strong>.</p>\","
                + "\"startAt\":\"2026-09-01T19:00\",\"endAt\":\"2026-09-01T22:00\","
                + "\"doorTime\":\"2026-09-01T18:15\",\"durationIso\":\"PT3H\","
                + "\"attendanceMode\":\"OFFLINE\",\"place\":\"" + place + "\","
                + "\"category\":\"musik\",\"organizerName\":\"Kulturamt\","
                + "\"organizerEmail\":\"kultur@example.org\","
                + "\"keywords\":[\"gala\",\"orchester\"],"
                + "\"isAccessibleForFree\":false,"
                + "\"offers\":[{\"name\":\"Erwachsene\",\"price\":\"25.00\",\"priceCurrency\":\"EUR\"},"
                + "            {\"name\":\"Ermäßigt\",\"price\":\"15\",\"url\":\"https://tickets.example.org\"}],"
                + "\"performers\":[{\"displayName\":\"Stadtorchester\"},{\"displayName\":\"Anna Solo\",\"email\":\"anna@example.org\"}],"
                + "\"sponsors\":[{\"displayName\":\"Sparkasse\",\"url\":\"https://sparkasse.example.org\"}],"
                + "\"images\":[{\"url\":\"https://cdn.example.org/gala.jpg\",\"altText\":\"Orchester auf der Bühne\",\"copyrightText\":\"Foto: Stadt\"}],"
                + "\"documents\":[{\"url\":\"https://cdn.example.org/programm.pdf\",\"fileName\":\"Programm.pdf\"}]"
                + "}").getAsJsonObject();

        // CREATE: VK_EVENT + alle Beziehungen
        String publicId = selfService.create(body);
        assertNotNull(publicId);

        // READ: alle Felder zurücklesen
        JsonObject ev = eventRepo.findByPublicId(publicId, false);
        assertNotNull(ev);
        assertEquals("PT3H", str(ev, "durationIso"));
        assertNotNull(str(ev, "doorTime"));
        assertEquals(2, ev.getAsJsonArray("offers").size());
        assertEquals(2, ev.getAsJsonArray("performers").size());
        assertEquals(1, ev.getAsJsonArray("sponsors").size());
        assertEquals(1, ev.getAsJsonArray("images").size());
        assertEquals("Orchester auf der Bühne",
                ev.getAsJsonArray("images").get(0).getAsJsonObject().get("altText").getAsString());
        assertEquals(1, ev.getAsJsonArray("documents").size());
        assertEquals(2, ev.getAsJsonArray("keywords").size());

        // LIST OWN
        JsonArray own = selfService.listOwn();
        assertTrue(containsId(own, publicId));

        // UPDATE: Beziehungen ersetzen (1 Offer, 1 Performer, kein Dokument mehr)
        JsonObject upd = body.deepCopy();
        upd.add("offers", JsonParser.parseString("[{\"name\":\"Einheitspreis\",\"price\":\"20\"}]"));
        upd.add("performers", JsonParser.parseString("[{\"displayName\":\"Nur Orchester\"}]"));
        upd.add("documents", new JsonArray());
        selfService.update(publicId, upd);
        JsonObject ev2 = eventRepo.findByPublicId(publicId, false);
        assertEquals(1, ev2.getAsJsonArray("offers").size());
        assertEquals(1, ev2.getAsJsonArray("performers").size());
        assertEquals(0, ev2.getAsJsonArray("documents").size());

        // SELF-SERVICE WORKFLOW: submit
        selfService.submit(publicId);

        // ADMIN WORKFLOW: approve -> publish (Versions-Snapshot)
        adminService.approve(publicId, "Sieht gut aus");
        long versionsBefore = versionCount(publicId);
        adminService.publish(publicId);
        assertEquals("nach publish sollte genau ein Versions-Snapshot existieren",
                versionsBefore + 1, versionCount(publicId));
        JsonObject published = eventRepo.findByPublicId(publicId, true);
        assertNotNull("veröffentlichtes Event muss öffentlich lesbar sein", published);

        // EXPORT
        String csv = exportService.exportPublishedCsv();
        assertNotNull(csv);
        assertTrue(csv.length() > 0);

        // CATEGORY-Baum + Slugs
        assertTrue(categoryRepo.findTree().size() > 0);
        assertTrue(categoryRepo.activeSlugs().contains("musik"));

        // AUDIT: es wurden Einträge geschrieben (create/update/submit/approve/publish)
        assertTrue("Audit-Log sollte gewachsen sein", auditCount() > auditBefore);

        // VERSIONS-HISTORIE: listen, Snapshot lesen, wiederherstellen
        JsonArray versions = adminService.versions(publicId);
        assertEquals("publish sollte genau eine Version erzeugt haben", 1, versions.size());
        int firstVersion = versions.get(0).getAsJsonObject().get("versionNo").getAsInt();
        JsonObject snap = adminService.versionSnapshot(publicId, firstVersion);
        assertEquals("SQL-Op Test Galakonzert", str(snap, "title"));

        adminService.restoreVersion(publicId, firstVersion);
        assertEquals("restore sollte eine zweite Version anlegen", 2, adminService.versions(publicId).size());
        JsonObject afterRestore = eventRepo.findByPublicId(publicId, false);
        assertEquals("SQL-Op Test Galakonzert", str(afterRestore, "title"));
        // Version 1 wurde beim Publish (nach dem Update) erstellt -> Stand: 1 Offer/Performer
        assertEquals(1, afterRestore.getAsJsonArray("offers").size());
        assertEquals(1, afterRestore.getAsJsonArray("performers").size());

        // AUDIT-TRAIL lesbar und tenant-gescoped
        JsonArray trail = adminService.auditTrail(50);
        assertTrue("Audit-Trail sollte Einträge liefern", trail.size() > 0);
    }

    @Test
    public void csvImportSupportsNewFlatColumns() {
        context();
        String csv = "title,startAt,attendanceMode,virtualUrl,category,organizerName,durationIso,price,performerNames,imageUrl,imageAlt\n"
                + "\"Import Online Talk\",2026-10-05T18:00,ONLINE,https://meet.example.org/x,musik,Volkshochschule,PT1H30M,0,\"Redner A;Redner B\",https://cdn.example.org/talk.jpg,\"Sprecher am Pult\"\n";
        JsonObject result = importService.importData("csv", csv, "talks.csv", "DRAFT");
        JsonObject summary = result.getAsJsonObject("summary");
        assertEquals(1, summary.get("imported").getAsInt());
        assertEquals(0, summary.get("errors").getAsInt());
    }

    @Test
    public void jsonImportSupportsNestedArrays() {
        context();
        String json = "{\"events\":[{"
                + "\"title\":\"Import JSON Festival\",\"startAt\":\"2026-11-01T16:00\","
                + "\"attendanceMode\":\"OFFLINE\",\"place\":\"" + somePlacePublicId() + "\","
                + "\"category\":\"musik\",\"organizerName\":\"Verein\","
                + "\"durationIso\":\"PT4H\","
                + "\"offers\":[{\"name\":\"Tag\",\"price\":\"30\"}],"
                + "\"performers\":[{\"displayName\":\"Band X\"}],"
                + "\"images\":[{\"url\":\"https://cdn.example.org/f.jpg\",\"altText\":\"Bühne\"}]"
                + "}]}";
        JsonObject result = importService.importData("json", json, "fest.json", "DRAFT");
        assertEquals(1, result.getAsJsonObject("summary").get("imported").getAsInt());
    }

    @Test
    public void imageWithoutAltTextIsRejected() {
        context();
        JsonObject body = JsonParser.parseString("{"
                + "\"title\":\"Bild ohne Alt\",\"startAt\":\"2026-09-09T10:00\","
                + "\"attendanceMode\":\"ONLINE\",\"virtualUrl\":\"https://x.example.org\","
                + "\"category\":\"musik\",\"organizerName\":\"Org\","
                + "\"images\":[{\"url\":\"https://cdn.example.org/x.jpg\"}]"
                + "}").getAsJsonObject();
        try {
            selfService.create(body);
            fail("Bild ohne Alternativtext hätte abgelehnt werden müssen");
        } catch (ValidationException expected) {
            assertEquals("images", expected.getField());
        }
    }

    @Test
    public void invalidDurationIsRejected() {
        context();
        JsonObject body = JsonParser.parseString("{"
                + "\"title\":\"Falsche Dauer\",\"startAt\":\"2026-09-09T10:00\","
                + "\"attendanceMode\":\"ONLINE\",\"virtualUrl\":\"https://x.example.org\","
                + "\"category\":\"musik\",\"organizerName\":\"Org\",\"durationIso\":\"2 Stunden\""
                + "}").getAsJsonObject();
        try {
            selfService.create(body);
            fail("Ungültige ISO-Dauer hätte abgelehnt werden müssen");
        } catch (ValidationException expected) {
            assertEquals("durationIso", expected.getField());
        }
    }

    // ------------------------------------------------------------------

    private long versionCount(String publicId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM VK_EVENT_VERSION v JOIN VK_EVENT e ON e.ID=v.EVENT_ID "
              + "WHERE e.PUBLIC_ID=:pid",
                Collections.singletonMap("pid", (Object) publicId), Long.class);
    }

    private static boolean containsId(JsonArray arr, String publicId) {
        for (int i = 0; i < arr.size(); i++) {
            if (publicId.equals(str(arr.get(i).getAsJsonObject(), "id"))) {
                return true;
            }
        }
        return false;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
