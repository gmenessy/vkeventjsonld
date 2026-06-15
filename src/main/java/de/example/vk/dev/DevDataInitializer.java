package de.example.vk.dev;

import de.example.vk.util.PasswordHasher;
import de.example.vk.util.SearchTextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Dev-/Demo-Modus (VK_DB_MODE=h2): legt das Schema an und befuellt es mit
 * mehreren Mandanten und je mehreren Veranstaltungskalendern (VK), damit
 * Mandanten-Isolation und Suchperformance realistisch geprueft werden koennen.
 * In Produktion (oracle) inaktiv.
 *
 * <p>Kategorie-Taxonomie und Keywords sind global; Orte, Veranstalter und Events
 * gehoeren jeweils zu genau einem (MANDANT_ID, VK_ID).</p>
 */
@Component
public class DevDataInitializer implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DevDataInitializer.class);

    /** Demo-Tenants: mandant, vk, Name, Anzahl Events (im Bereich 1.000–20.000). */
    private static final long[][] TENANTS = {
            {1, 1, 12000},
            {1, 2, 2000},
            {2, 3, 1500},
    };
    private static final String[] TENANT_NAMES = {
            "Stadt Freiburg – Kulturkalender",
            "Stadt Freiburg – Sportkalender",
            "Landkreis Emmendingen – Veranstaltungen",
    };

    private final DataSource dataSource;
    private final String mode;

    public DevDataInitializer(DataSource dataSource, @Value("${VK_DB_MODE:h2}") String mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!"h2".equalsIgnoreCase(mode)) {
            return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'VK_EVENT'",
                Integer.class);
        if (tables != null && tables > 0) {
            return;
        }
        long start = System.currentTimeMillis();
        try (Connection con = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/h2/schema.sql"));
        }
        long total = seed(jdbc);
        LOG.info("Dev-Datenbank mit {} Events ueber {} VKs angelegt in {} ms",
                total, TENANTS.length, System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------

    private static final String[][] CATEGORIES = {
            {"kultur", "Kultur", null, "Event"},
            {"musik", "Musik", "kultur", "MusicEvent"},
            {"theater", "Theater", "kultur", "TheaterEvent"},
            {"ausstellung", "Ausstellung", "kultur", "ExhibitionEvent"},
            {"sport", "Sport", null, "SportsEvent"},
            {"laufveranstaltung", "Laufveranstaltung", "sport", "SportsEvent"},
            {"fussball", "Fußball", "sport", "SportsEvent"},
            {"radsport", "Radsport", "sport", "SportsEvent"},
            {"bildung", "Bildung", null, "EducationEvent"},
            {"vortrag", "Vortrag", "bildung", "EducationEvent"},
            {"kurs", "Kurs", "bildung", "CourseInstance"},
            {"workshop", "Workshop", "bildung", "EducationEvent"},
            {"kinder-familie", "Kinder & Familie", null, "ChildrensEvent"},
    };

    private static final String[] CITIES = {
            "Freiburg im Breisgau", "Emmendingen", "Offenburg", "Lörrach",
            "Waldkirch", "Titisee-Neustadt", "Müllheim", "Breisach am Rhein"
    };
    private static final String[] PLACE_NAMES = {
            "Stadtpark", "Stadthalle", "Bürgerhaus", "Kulturzentrum", "Theater am Markt",
            "Musikschule", "Sportzentrum", "Stadtbibliothek", "Museum", "Marktplatz",
            "Jugendzentrum", "Gemeindesaal"
    };
    private static final String[] TITLE_LEAD = {
            "Sommerkonzert", "Wochenmarkt", "Theateraufführung", "Lesung", "Stadtlauf",
            "Familienfest", "Workshop Fotografie", "Yoga im Park", "Jazzabend", "Orgelkonzert",
            "Kindertheater", "Vernissage", "Vortrag Klimaschutz", "Repair Café", "Flohmarkt",
            "Chorprobe offen", "Tanzabend", "Filmnacht", "Mitmachzirkus", "Stadtführung",
            "Programmierkurs", "Töpferkurs", "Radtour", "Fußballturnier", "Hallenkonzert",
            "Poetry Slam", "Weinprobe", "Adventsmarkt", "Osterbasteln", "Sommerfest"
    };
    private static final String[] TITLE_TAIL = {
            "für die ganze Familie", "mit regionalen Künstlern", "unter freiem Himmel",
            "für Einsteiger", "für Fortgeschrittene", "mit Live-Musik", "im historischen Zentrum",
            "zum Mitmachen", "bei freiem Eintritt", "mit Anmeldung", "der Jugendabteilung",
            "des Fördervereins", "zum Saisonauftakt", "zum Jahresausklang", ""
    };
    private static final String[] ORG_NAMES = {
            "Kulturverein", "Musikverein", "Sportverein", "Förderverein", "Bürgerinitiative",
            "Volkshochschule", "Stadtjugendring", "Naturfreunde", "Kunstverein", "Heimatverein"
    };
    private static final String[] KEYWORDS = {
            "Open Air", "Familie", "Musik", "Kostenlos", "Barrierefrei", "Draußen",
            "Kinder", "Senioren", "Jugend", "Regional", "Kreativ", "Gesundheit",
            "Natur", "Tradition", "Modern", "Mitmachen", "Live", "Festival",
            "Markt", "Bildung", "Sport", "Kultur", "Tanz", "Literatur"
    };

    /** Globale ID-Zaehler ueber alle Tenants (IDs sind generated-by-default). */
    private static final class Ids {
        long address = 0, place = 0, party = 0, vloc = 0, event = 0, offer = 0;
    }

    private long seed(JdbcTemplate jdbc) {
        Random rnd = new Random(20260612L);
        Ids ids = new Ids();

        seedGlobalCategories(jdbc);
        seedGlobalKeywords(jdbc);
        seedRegistry(jdbc);
        seedAuth(jdbc);

        long totalEvents = 0;
        for (int t = 0; t < TENANTS.length; t++) {
            long mandant = TENANTS[t][0];
            long vk = TENANTS[t][1];
            int eventCount = (int) TENANTS[t][2];
            seedTenant(jdbc, rnd, ids, mandant, vk, eventCount);
            totalEvents += eventCount;
        }
        resetIdentities(jdbc);
        return totalEvents;
    }

    /**
     * Da der Seed explizite IDs setzt (GENERATED BY DEFAULT), bleibt der Identity-Zähler
     * auf 1 – neue Runtime-Inserts würden kollidieren. Daher die Sequenzen hochsetzen.
     * Nur H2/Dev; in Produktion (Oracle) erzeugt die Anwendung IDs regulär.
     */
    private void resetIdentities(JdbcTemplate jdbc) {
        String[] tables = {"VK_ADDRESS", "VK_PLACE", "VK_VIRTUAL_LOCATION", "VK_PARTY",
                "VK_CATEGORY", "VK_KEYWORD", "VK_EVENT", "VK_OFFER", "VK_USER", "VK_ROLE"};
        for (String t : tables) {
            Long max = jdbc.queryForObject("SELECT COALESCE(MAX(ID), 0) FROM " + t, Long.class);
            jdbc.execute("ALTER TABLE " + t + " ALTER COLUMN ID RESTART WITH " + (max + 1));
        }
    }

    private void seedGlobalCategories(JdbcTemplate jdbc) {
        List<Object[]> cats = new ArrayList<Object[]>();
        for (int i = 0; i < CATEGORIES.length; i++) {
            String[] c = CATEGORIES[i];
            Long parentId = null;
            if (c[2] != null) {
                for (int j = 0; j < CATEGORIES.length; j++) {
                    if (CATEGORIES[j][0].equals(c[2])) {
                        parentId = (long) (j + 1);
                    }
                }
            }
            cats.add(new Object[]{i + 1, parentId, c[1], c[0], c[3], i});
        }
        jdbc.batchUpdate("INSERT INTO VK_CATEGORY (ID, PARENT_ID, NAME, SLUG, SCHEMA_TYPE, SORT_ORDER) VALUES (?,?,?,?,?,?)", cats);
    }

    private void seedGlobalKeywords(JdbcTemplate jdbc) {
        List<Object[]> keywords = new ArrayList<Object[]>();
        for (int i = 0; i < KEYWORDS.length; i++) {
            keywords.add(new Object[]{i + 1, KEYWORDS[i],
                    KEYWORDS[i].toLowerCase(Locale.GERMAN).replace(' ', '-')
                            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")});
        }
        jdbc.batchUpdate("INSERT INTO VK_KEYWORD (ID, NAME, SLUG) VALUES (?,?,?)", keywords);
    }

    /** Rollen (global) + Demo-Nutzer je Mandant mit PBKDF2-Hashes. */
    private void seedAuth(JdbcTemplate jdbc) {
        jdbc.batchUpdate("INSERT INTO VK_ROLE (ID, NAME) VALUES (?,?)", Arrays.asList(
                new Object[]{1, "REGISTERED"}, new Object[]{2, "EDITOR"}, new Object[]{3, "ADMIN"}));

        // userId, mandant, email, name, klartext-pw, roleIds...
        Object[][] users = {
                {1L, 1L, "nutzer@vk.example", "Nutzer Freiburg", "nutzer", new int[]{1}},
                {2L, 1L, "redaktion@vk.example", "Redaktion Freiburg", "redaktion", new int[]{1, 2}},
                {3L, 1L, "admin@vk.example", "Admin", "admin", new int[]{1, 2, 3}},
                {4L, 2L, "redaktion@emmendingen.example", "Redaktion Emmendingen", "redaktion", new int[]{1, 2}},
        };
        List<Object[]> userRows = new ArrayList<Object[]>();
        List<Object[]> roleRows = new ArrayList<Object[]>();
        for (Object[] u : users) {
            long id = (Long) u[0];
            userRows.add(new Object[]{id, java.util.UUID.randomUUID().toString(), u[1], u[2], u[3],
                    PasswordHasher.hash((String) u[4])});
            for (int roleId : (int[]) u[5]) {
                roleRows.add(new Object[]{id, roleId});
            }
        }
        jdbc.batchUpdate("INSERT INTO VK_USER (ID, PUBLIC_ID, MANDANT_ID, EMAIL, DISPLAY_NAME, PASSWORD_HASH) "
                + "VALUES (?,?,?,?,?,?)", userRows);
        jdbc.batchUpdate("INSERT INTO VK_USER_ROLE (USER_ID, ROLE_ID) VALUES (?,?)", roleRows);
    }

    private void seedRegistry(JdbcTemplate jdbc) {
        List<Object[]> regs = new ArrayList<Object[]>();
        for (int t = 0; t < TENANTS.length; t++) {
            regs.add(new Object[]{TENANTS[t][0], TENANTS[t][1], TENANT_NAMES[t], "vk" + TENANTS[t][1]});
        }
        jdbc.batchUpdate("INSERT INTO VK_REGISTRY (MANDANT_ID, VK_ID, NAME, SLUG) VALUES (?,?,?,?)", regs);
    }

    private void seedTenant(JdbcTemplate jdbc, Random rnd, Ids ids, long mandant, long vk, int eventCount) {
        // Orte + Adressen (pro VK)
        int placeCount = 24;
        long[] placeIds = new long[placeCount];
        String[] placeNames = new String[placeCount];
        String[] placeCities = new String[placeCount];
        List<Object[]> addresses = new ArrayList<Object[]>();
        List<Object[]> places = new ArrayList<Object[]>();
        for (int i = 0; i < placeCount; i++) {
            String city = CITIES[i % CITIES.length];
            String name = PLACE_NAMES[(i / CITIES.length) % PLACE_NAMES.length] + " " + city.split(" ")[0];
            long addrId = ++ids.address;
            long placeId = ++ids.place;
            placeIds[i] = placeId;
            placeNames[i] = name;
            placeCities[i] = city;
            addresses.add(new Object[]{addrId, "Beispielstraße " + (1 + rnd.nextInt(90)),
                    String.valueOf(79000 + rnd.nextInt(999)), city, "Baden-Württemberg", "DE"});
            String note = rnd.nextInt(3) > 0 ? "Barrierefreier Zugang über den Haupteingang." : null;
            places.add(new Object[]{placeId, uuid(rnd), mandant, vk, name, addrId,
                    47.5 + rnd.nextDouble(), 7.5 + rnd.nextDouble(), note});
        }
        jdbc.batchUpdate("INSERT INTO VK_ADDRESS (ID, STREET_ADDRESS, POSTAL_CODE, LOCALITY, REGION, COUNTRY_CODE) VALUES (?,?,?,?,?,?)", addresses);
        jdbc.batchUpdate("INSERT INTO VK_PLACE (ID, PUBLIC_ID, MANDANT_ID, VK_ID, NAME, ADDRESS_ID, LATITUDE, LONGITUDE, ACCESSIBILITY_NOTE) VALUES (?,?,?,?,?,?,?,?,?)", places);

        // Virtuelle Orte (pro VK)
        int vlocCount = 10;
        long[] vlocIds = new long[vlocCount];
        List<Object[]> vlocs = new ArrayList<Object[]>();
        for (int i = 0; i < vlocCount; i++) {
            long id = ++ids.vloc;
            vlocIds[i] = id;
            vlocs.add(new Object[]{id, "Online-Raum " + id, "https://meet.example.org/raum-" + id, "BigBlueButton"});
        }
        jdbc.batchUpdate("INSERT INTO VK_VIRTUAL_LOCATION (ID, NAME, URL, PLATFORM) VALUES (?,?,?,?)", vlocs);

        // Veranstalter + Performer (pro VK)
        int orgCount = 20;
        int personCount = 10;
        long[] orgIds = new long[orgCount];
        String[] orgNames = new String[orgCount];
        long[] personIds = new long[personCount];
        List<Object[]> parties = new ArrayList<Object[]>();
        for (int i = 0; i < orgCount; i++) {
            long id = ++ids.party;
            orgIds[i] = id;
            String name = ORG_NAMES[i % ORG_NAMES.length] + " " + CITIES[i % CITIES.length].split(" ")[0]
                    + " (VK" + vk + ") e.V.";
            orgNames[i] = name;
            parties.add(new Object[]{id, uuid(rnd), mandant, vk, "ORGANIZATION", name,
                    "info" + id + "@example.org", "https://verein" + id + ".example.org"});
        }
        for (int i = 0; i < personCount; i++) {
            long id = ++ids.party;
            personIds[i] = id;
            parties.add(new Object[]{id, uuid(rnd), mandant, vk, "PERSON",
                    "Ensemble " + (char) ('A' + i % 26) + id, null, null});
        }
        jdbc.batchUpdate("INSERT INTO VK_PARTY (ID, PUBLIC_ID, MANDANT_ID, VK_ID, PARTY_TYPE, DISPLAY_NAME, EMAIL, URL) VALUES (?,?,?,?,?,?,?,?)", parties);

        // Events (pro VK)
        LocalDate today = LocalDate.now();
        List<Object[]> events = new ArrayList<Object[]>();
        List<Object[]> eventCats = new ArrayList<Object[]>();
        List<Object[]> eventKeywords = new ArrayList<Object[]>();
        List<Object[]> eventRoles = new ArrayList<Object[]>();
        List<Object[]> offers = new ArrayList<Object[]>();

        for (int n = 0; n < eventCount; n++) {
            long eventId = ++ids.event;
            int catIdx = 1 + rnd.nextInt(CATEGORIES.length - 1);
            String[] cat = CATEGORIES[catIdx];
            String lead = TITLE_LEAD[rnd.nextInt(TITLE_LEAD.length)];
            String tail = TITLE_TAIL[rnd.nextInt(TITLE_TAIL.length)];
            int placeIdx = rnd.nextInt(placeCount);
            String title = (lead + " " + tail).trim();
            String shortDesc = cat[1] + " in " + placeCities[placeIdx]
                    + " – " + lead + (tail.isEmpty() ? "" : " " + tail) + ".";

            int dayOffset = -30 + rnd.nextInt(570);
            LocalDateTime startAt = today.plusDays(dayOffset).atTime(8 + rnd.nextInt(13), rnd.nextBoolean() ? 0 : 30);
            LocalDateTime endAt = startAt.plusHours(1 + rnd.nextInt(5));

            int modeRoll = rnd.nextInt(100);
            String attendanceMode = modeRoll < 75 ? "OFFLINE" : (modeRoll < 90 ? "ONLINE" : "MIXED");
            Long placeId = "ONLINE".equals(attendanceMode) ? null : placeIds[placeIdx];
            Long vlocId = "OFFLINE".equals(attendanceMode) ? null : vlocIds[rnd.nextInt(vlocCount)];

            boolean free = rnd.nextInt(100) < 30;
            int statusRoll = rnd.nextInt(100);
            String eventStatus = statusRoll < 94 ? "EventScheduled"
                    : (statusRoll < 97 ? "EventCancelled" : "EventRescheduled");
            String workflowStatus = rnd.nextInt(100) < 92 ? "PUBLISHED" : "SUBMITTED";

            int orgIdx = rnd.nextInt(orgCount);
            long organizerId = orgIds[orgIdx];
            String organizerName = orgNames[orgIdx];

            int kw1 = 1 + rnd.nextInt(KEYWORDS.length);
            int kw2 = 1 + rnd.nextInt(KEYWORDS.length);

            String searchText = SearchTextUtil.build(title, shortDesc, cat[1],
                    placeId == null ? "online" : placeNames[placeIdx],
                    placeId == null ? "" : placeCities[placeIdx],
                    organizerName, KEYWORDS[kw1 - 1], kw2 != kw1 ? KEYWORDS[kw2 - 1] : "");

            String description = "<p>" + shortDesc + "</p><p>Weitere Informationen erhalten Sie beim Veranstalter <strong>"
                    + organizerName + "</strong>. Änderungen vorbehalten.</p>";

            events.add(new Object[]{eventId, uuid(rnd), mandant, vk, cat[3], title, shortDesc, description,
                    Timestamp.valueOf(startAt), Timestamp.valueOf(endAt),
                    attendanceMode, eventStatus, workflowStatus,
                    free ? "Y" : "N", placeId, vlocId, searchText,
                    Timestamp.valueOf(startAt.minusDays(30))});

            eventCats.add(new Object[]{eventId, catIdx + 1, "Y"});
            eventRoles.add(new Object[]{eventId, organizerId, "ORGANIZER", 0});
            if (rnd.nextInt(100) < 25) {
                eventRoles.add(new Object[]{eventId, personIds[rnd.nextInt(personCount)], "PERFORMER", 1});
            }
            eventKeywords.add(new Object[]{eventId, kw1});
            if (kw2 != kw1) {
                eventKeywords.add(new Object[]{eventId, kw2});
            }
            if (free) {
                offers.add(new Object[]{++ids.offer, eventId, "Eintritt frei", java.math.BigDecimal.ZERO, "EUR", "InStock"});
            } else {
                offers.add(new Object[]{++ids.offer, eventId, "Standardticket",
                        new java.math.BigDecimal(5 + rnd.nextInt(40)), "EUR", "InStock"});
                if (rnd.nextBoolean()) {
                    offers.add(new Object[]{++ids.offer, eventId, "Ermäßigt",
                            new java.math.BigDecimal(3 + rnd.nextInt(15)), "EUR", "InStock"});
                }
            }

            if (events.size() == 1000) {
                flushEvents(jdbc, events);
            }
        }
        flushEvents(jdbc, events);
        batchInChunks(jdbc, "INSERT INTO VK_EVENT_CATEGORY (EVENT_ID, CATEGORY_ID, PRIMARY_FLAG) VALUES (?,?,?)", eventCats);
        batchInChunks(jdbc, "INSERT INTO VK_EVENT_PARTY_ROLE (EVENT_ID, PARTY_ID, ROLE_TYPE, SORT_ORDER) VALUES (?,?,?,?)", eventRoles);
        batchInChunks(jdbc, "INSERT INTO VK_EVENT_KEYWORD (EVENT_ID, KEYWORD_ID) VALUES (?,?)", eventKeywords);
        batchInChunks(jdbc, "INSERT INTO VK_OFFER (ID, EVENT_ID, NAME, PRICE, PRICE_CURRENCY, AVAILABILITY) VALUES (?,?,?,?,?,?)", offers);
    }

    private void flushEvents(JdbcTemplate jdbc, List<Object[]> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO VK_EVENT (ID, PUBLIC_ID, MANDANT_ID, VK_ID, SCHEMA_TYPE, TITLE, SHORT_DESCRIPTION, DESCRIPTION, "
                + "START_AT, END_AT, ATTENDANCE_MODE, EVENT_STATUS, WORKFLOW_STATUS, IS_ACCESSIBLE_FOR_FREE, "
                + "PLACE_ID, VIRTUAL_LOCATION_ID, SEARCH_TEXT, PUBLISHED_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                events);
        events.clear();
    }

    private void batchInChunks(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
        int chunk = 2000;
        for (int start = 0; start < rows.size(); start += chunk) {
            jdbc.batchUpdate(sql, rows.subList(start, Math.min(start + chunk, rows.size())));
        }
    }

    private static String uuid(Random rnd) {
        return new UUID(rnd.nextLong(), rnd.nextLong()).toString();
    }
}
