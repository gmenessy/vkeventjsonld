package de.example.vk.dev;

import de.example.vk.util.SearchTextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Dev-/Demo-Modus (VK_DB_MODE=h2): legt das Schema an und befuellt die Datenbank
 * mit ca. 12.000 veroeffentlichten Events, damit Suchverhalten und Performance
 * realistisch geprueft werden koennen. In Produktion (oracle) inaktiv.
 */
@Component
public class DevDataInitializer implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DevDataInitializer.class);

    private static final int EVENT_COUNT = 12000;

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
        seed(jdbc);
        LOG.info("Dev-Datenbank mit {} Events angelegt in {} ms",
                EVENT_COUNT, System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------

    private static final String[][] CATEGORIES = {
            // slug, name, parentSlug, schemaType
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

    private void seed(JdbcTemplate jdbc) {
        Random rnd = new Random(20260612L);

        // Adressen + Orte
        int placeCount = CITIES.length * PLACE_NAMES.length / 2;
        List<Object[]> addresses = new ArrayList<Object[]>();
        List<Object[]> places = new ArrayList<Object[]>();
        String[] placeNames = new String[placeCount];
        String[] placeCities = new String[placeCount];
        for (int i = 0; i < placeCount; i++) {
            String city = CITIES[i % CITIES.length];
            String name = PLACE_NAMES[(i / CITIES.length) % PLACE_NAMES.length] + " " + city.split(" ")[0];
            placeNames[i] = name;
            placeCities[i] = city;
            long id = i + 1;
            addresses.add(new Object[]{id, "Beispielstraße " + (1 + rnd.nextInt(90)),
                    String.valueOf(79000 + rnd.nextInt(999)), city, "Baden-Württemberg", "DE"});
            String note = rnd.nextInt(3) > 0
                    ? "Barrierefreier Zugang über den Haupteingang." : null;
            places.add(new Object[]{id, uuid(rnd), name, id,
                    47.5 + rnd.nextDouble(), 7.5 + rnd.nextDouble(), note});
        }
        jdbc.batchUpdate("INSERT INTO VK_ADDRESS (ID, STREET_ADDRESS, POSTAL_CODE, LOCALITY, REGION, COUNTRY_CODE) VALUES (?,?,?,?,?,?)", addresses);
        jdbc.batchUpdate("INSERT INTO VK_PLACE (ID, PUBLIC_ID, NAME, ADDRESS_ID, LATITUDE, LONGITUDE, ACCESSIBILITY_NOTE) VALUES (?,?,?,?,?,?,?)", places);

        // Virtuelle Orte
        List<Object[]> vlocs = new ArrayList<Object[]>();
        for (int i = 1; i <= 10; i++) {
            vlocs.add(new Object[]{i, "Online-Raum " + i, "https://meet.example.org/raum-" + i, "BigBlueButton"});
        }
        jdbc.batchUpdate("INSERT INTO VK_VIRTUAL_LOCATION (ID, NAME, URL, PLATFORM) VALUES (?,?,?,?)", vlocs);

        // Kategorien
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

        // Veranstalter (Organisationen) + Personen (Performer)
        List<Object[]> parties = new ArrayList<Object[]>();
        int orgCount = ORG_NAMES.length * 4;
        for (int i = 0; i < orgCount; i++) {
            String name = ORG_NAMES[i % ORG_NAMES.length] + " " + CITIES[i % CITIES.length].split(" ")[0] + " e.V.";
            parties.add(new Object[]{i + 1, uuid(rnd), "ORGANIZATION", name,
                    "info" + (i + 1) + "@example.org", "https://verein" + (i + 1) + ".example.org"});
        }
        int personCount = 20;
        for (int i = 0; i < personCount; i++) {
            parties.add(new Object[]{orgCount + i + 1, uuid(rnd), "PERSON",
                    "Ensemble " + (char) ('A' + i % 26) + (i + 1), null, null});
        }
        jdbc.batchUpdate("INSERT INTO VK_PARTY (ID, PUBLIC_ID, PARTY_TYPE, DISPLAY_NAME, EMAIL, URL) VALUES (?,?,?,?,?,?)", parties);

        // Keywords
        List<Object[]> keywords = new ArrayList<Object[]>();
        for (int i = 0; i < KEYWORDS.length; i++) {
            keywords.add(new Object[]{i + 1, KEYWORDS[i],
                    KEYWORDS[i].toLowerCase(java.util.Locale.GERMAN).replace(' ', '-').replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")});
        }
        jdbc.batchUpdate("INSERT INTO VK_KEYWORD (ID, NAME, SLUG) VALUES (?,?,?)", keywords);

        // Events
        LocalDate today = LocalDate.now();
        List<Object[]> events = new ArrayList<Object[]>(EVENT_COUNT);
        List<Object[]> eventCats = new ArrayList<Object[]>();
        List<Object[]> eventKeywords = new ArrayList<Object[]>();
        List<Object[]> eventRoles = new ArrayList<Object[]>();
        List<Object[]> offers = new ArrayList<Object[]>();
        long offerId = 1;

        for (int i = 1; i <= EVENT_COUNT; i++) {
            int catIdx = 1 + rnd.nextInt(CATEGORIES.length - 1); // bevorzugt Unterkategorien
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
            Long placeId = "ONLINE".equals(attendanceMode) ? null : (long) (placeIdx + 1);
            Long vlocId = "OFFLINE".equals(attendanceMode) ? null : (long) (1 + rnd.nextInt(10));

            boolean free = rnd.nextInt(100) < 30;
            int statusRoll = rnd.nextInt(100);
            String eventStatus = statusRoll < 94 ? "EventScheduled"
                    : (statusRoll < 97 ? "EventCancelled" : "EventRescheduled");
            // ~92% veroeffentlicht, Rest im Redaktionsprozess
            String workflowStatus = rnd.nextInt(100) < 92 ? "PUBLISHED" : "SUBMITTED";

            long organizerId = 1 + rnd.nextInt(orgCount);
            String organizerName = (String) parties.get((int) organizerId - 1)[3];

            int kw1 = 1 + rnd.nextInt(KEYWORDS.length);
            int kw2 = 1 + rnd.nextInt(KEYWORDS.length);

            String searchText = SearchTextUtil.build(title, shortDesc, cat[1],
                    placeId == null ? "online" : placeNames[placeIdx],
                    placeId == null ? "" : placeCities[placeIdx],
                    organizerName, KEYWORDS[kw1 - 1], kw2 != kw1 ? KEYWORDS[kw2 - 1] : "");

            String description = "<p>" + shortDesc + "</p><p>Weitere Informationen erhalten Sie beim Veranstalter <strong>"
                    + organizerName + "</strong>. Änderungen vorbehalten.</p>";

            events.add(new Object[]{i, uuid(rnd), cat[3], title, shortDesc, description,
                    Timestamp.valueOf(startAt), Timestamp.valueOf(endAt),
                    attendanceMode, eventStatus, workflowStatus,
                    free ? "Y" : "N", placeId, vlocId, searchText,
                    Timestamp.valueOf(startAt.minusDays(30))});

            eventCats.add(new Object[]{i, catIdx + 1, "Y"});
            eventRoles.add(new Object[]{i, organizerId, "ORGANIZER", 0});
            if (rnd.nextInt(100) < 25) {
                eventRoles.add(new Object[]{i, (long) (orgCount + 1 + rnd.nextInt(personCount)), "PERFORMER", 1});
            }
            eventKeywords.add(new Object[]{i, kw1});
            if (kw2 != kw1) {
                eventKeywords.add(new Object[]{i, kw2});
            }
            if (free) {
                offers.add(new Object[]{offerId++, i, "Eintritt frei", java.math.BigDecimal.ZERO, "EUR", "InStock"});
            } else {
                offers.add(new Object[]{offerId++, i, "Standardticket",
                        new java.math.BigDecimal(5 + rnd.nextInt(40)), "EUR", "InStock"});
                if (rnd.nextBoolean()) {
                    offers.add(new Object[]{offerId++, i, "Ermäßigt",
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
        jdbc.batchUpdate("INSERT INTO VK_EVENT (ID, PUBLIC_ID, SCHEMA_TYPE, TITLE, SHORT_DESCRIPTION, DESCRIPTION, "
                + "START_AT, END_AT, ATTENDANCE_MODE, EVENT_STATUS, WORKFLOW_STATUS, IS_ACCESSIBLE_FOR_FREE, "
                + "PLACE_ID, VIRTUAL_LOCATION_ID, SEARCH_TEXT, PUBLISHED_AT) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
