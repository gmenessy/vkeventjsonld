package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.ClobUtil;
import de.example.vk.util.Json;
import de.example.vk.util.SearchTextUtil;
import de.example.vk.util.ConfigVk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Zugriff auf VK_EVENT. Die Suche ist auf grosse Datenmengen ausgelegt:
 *
 * <ol>
 *   <li>Volltextsuche nur ueber die denormalisierte Spalte SEARCH_TEXT
 *       (keine Joins ueber Keyword-/Kategorie-/Party-Tabellen zur Suchzeit).</li>
 *   <li>Die Listenabfrage liest nur Listenspalten (kein DESCRIPTION-CLOB).</li>
 *   <li>Pagination in der Datenbank (OFFSET/FETCH, Oracle 12c+ und H2).</li>
 *   <li>Treiberindex IDX_EVENT_PUB_START (WORKFLOW_STATUS, START_AT, ID)
 *       traegt Standard-Listing und Sortierung.</li>
 *   <li>Optional Oracle Text (vk.search.oracleText=true) statt INSTR.</li>
 * </ol>
 *
 * Ergebnisse werden direkt als Gson-{@link JsonObject} gebaut (kein DTO-Layer).
 */
@Repository
public class EventRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean oracleTextEnabled;

    public EventRepository(NamedParameterJdbcTemplate jdbc,
                           @Value("${vk.search.oracleText:false}") boolean oracleTextEnabled) {
        this.jdbc = jdbc;
        this.oracleTextEnabled = oracleTextEnabled;
    }

    private static final String LIST_SELECT =
            "SELECT e.PUBLIC_ID, e.SCHEMA_TYPE, e.TITLE, e.SHORT_DESCRIPTION, "
          + "       e.START_AT, e.END_AT, e.ATTENDANCE_MODE, e.EVENT_STATUS, "
          + "       e.IS_ACCESSIBLE_FOR_FREE, "
          + "       p.PUBLIC_ID AS PLACE_PUBLIC_ID, p.NAME AS PLACE_NAME, "
          + "       p.ACCESSIBILITY_NOTE, a.LOCALITY, "
          + "       (SELECT c.NAME FROM VK_EVENT_CATEGORY ec "
          + "          JOIN VK_CATEGORY c ON c.ID = ec.CATEGORY_ID "
          + "         WHERE ec.EVENT_ID = e.ID "
          + "         ORDER BY ec.PRIMARY_FLAG DESC, c.SORT_ORDER "
          + "         FETCH FIRST 1 ROWS ONLY) AS PRIMARY_CATEGORY, "
          + "       (SELECT MIN(o.PRICE) FROM VK_OFFER o WHERE o.EVENT_ID = e.ID) AS MIN_PRICE "
          + "FROM VK_EVENT e "
          + "LEFT JOIN VK_PLACE p ON p.ID = e.PLACE_ID "
          + "LEFT JOIN VK_ADDRESS a ON a.ID = p.ADDRESS_ID ";

    /** Such-Parameter als schlichtes Werte-Buendel (kein DTO im API-Sinn). */
    public static final class Query {
        public LocalDate from;
        public LocalDate to;
        public String q;
        public String category;
        public String place;
        public String organizer;
        public String attendanceMode;
        public Boolean free;
        public int page = 1;
        public int size = 20;
        public String sort = "date";

        public int offset() {
            return (page - 1) * size;
        }
    }

    public JsonArray search(Query req) {
        StringBuilder sql = new StringBuilder(LIST_SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        sql.append(buildWhere(req, params));
        sql.append(buildOrderBy(req));
        sql.append(" OFFSET :off ROWS FETCH NEXT :sz ROWS ONLY");
        params.addValue("off", req.offset());
        params.addValue("sz", req.size);

        JsonArray array = new JsonArray();
        List<JsonObject> rows = jdbc.query(sql.toString(), params, LIST_MAPPER);
        for (JsonObject row : rows) {
            array.add(row);
        }
        return array;
    }

    /**
     * Sortierung. Bei {@code sort=relevance} mit Suchbegriff werden Titel-Treffer
     * hoeher gewichtet (Oracle Text: SCORE; sonst INSTR-Score auf den Titel),
     * danach nach Datum. Die :qN-Parameter stammen aus {@link #buildWhere}.
     */
    private String buildOrderBy(Query req) {
        boolean hasQuery = req.q != null && !req.q.trim().isEmpty();
        if ("relevance".equalsIgnoreCase(req.sort) && hasQuery) {
            if (oracleTextEnabled) {
                return " ORDER BY SCORE(1) DESC, e.START_AT, e.ID";
            }
            String[] terms = SearchTextUtil.normalizeTerm(req.q).split("\\s+");
            StringBuilder score = new StringBuilder();
            for (int i = 0; i < terms.length && i < 6; i++) {
                if (terms[i].isEmpty()) {
                    continue;
                }
                score.append("CASE WHEN INSTR(LOWER(e.TITLE), :q").append(i)
                     .append(") > 0 THEN 2 ELSE 0 END + ");
            }
            score.append("0");
            return " ORDER BY (" + score + ") DESC, e.START_AT, e.ID";
        }
        if ("desc".equalsIgnoreCase(req.sort) || "date_desc".equalsIgnoreCase(req.sort)) {
            return " ORDER BY e.START_AT DESC, e.ID DESC";
        }
        return " ORDER BY e.START_AT, e.ID";
    }

    public long count(Query req) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM VK_EVENT e LEFT JOIN VK_PLACE p ON p.ID = e.PLACE_ID ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        sql.append(buildWhere(req, params));
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0L : total;
    }

    private String buildWhere(Query req, MapSqlParameterSource params) {
        // Mandanten-Isolation zuerst: jede Suche laeuft strikt im aktuellen (MANDANT_ID, VK_ID).
        StringBuilder where = new StringBuilder(
                "WHERE e.MANDANT_ID = :mandant AND e.VK_ID = :vk AND e.WORKFLOW_STATUS = 'PUBLISHED'");
        params.addValue("mandant", ConfigVk.requireMandant());
        params.addValue("vk", ConfigVk.requireVkId());

        if (req.from != null) {
            where.append(" AND e.START_AT >= :fromTs");
            params.addValue("fromTs", Timestamp.valueOf(req.from.atStartOfDay()));
        }
        if (req.to != null) {
            where.append(" AND e.START_AT < :toTs");
            params.addValue("toTs", Timestamp.valueOf(req.to.plusDays(1).atStartOfDay()));
        }

        if (req.q != null && !req.q.trim().isEmpty()) {
            if (oracleTextEnabled) {
                where.append(" AND CONTAINS(e.SEARCH_TEXT, :ctxQuery, 1) > 0");
                params.addValue("ctxQuery", toOracleTextQuery(req.q));
            } else {
                String[] terms = SearchTextUtil.normalizeTerm(req.q).split("\\s+");
                for (int i = 0; i < terms.length && i < 6; i++) {
                    if (terms[i].isEmpty()) {
                        continue;
                    }
                    String p = "q" + i;
                    where.append(" AND INSTR(e.SEARCH_TEXT, :").append(p).append(") > 0");
                    params.addValue(p, terms[i]);
                }
            }
        }

        if (notBlank(req.category)) {
            where.append(" AND EXISTS (SELECT 1 FROM VK_EVENT_CATEGORY ec")
                 .append(" JOIN VK_CATEGORY c ON c.ID = ec.CATEGORY_ID")
                 .append(" LEFT JOIN VK_CATEGORY pc ON pc.ID = c.PARENT_ID")
                 .append(" WHERE ec.EVENT_ID = e.ID AND (c.SLUG = :cat OR pc.SLUG = :cat))");
            params.addValue("cat", req.category);
        }

        if (notBlank(req.place)) {
            where.append(" AND p.PUBLIC_ID = :placeId");
            params.addValue("placeId", req.place);
        }

        if (notBlank(req.organizer)) {
            where.append(" AND EXISTS (SELECT 1 FROM VK_EVENT_PARTY_ROLE r")
                 .append(" JOIN VK_PARTY pa ON pa.ID = r.PARTY_ID")
                 .append(" WHERE r.EVENT_ID = e.ID AND r.ROLE_TYPE = 'ORGANIZER'")
                 .append(" AND pa.PUBLIC_ID = :orgId)");
            params.addValue("orgId", req.organizer);
        }

        if (notBlank(req.attendanceMode)) {
            where.append(" AND e.ATTENDANCE_MODE = :mode");
            params.addValue("mode", req.attendanceMode);
        }

        if (Boolean.TRUE.equals(req.free)) {
            where.append(" AND e.IS_ACCESSIBLE_FOR_FREE = 'Y'");
        }

        return where.toString();
    }

    /** Oracle-Text-Query: Terme AND-verknuepft mit Praefixsuche, Sonderzeichen entfernt. */
    private static String toOracleTextQuery(String q) {
        String cleaned = SearchTextUtil.normalizeTerm(q).replaceAll("[^\\p{L}\\p{Nd}\\s]", " ");
        StringBuilder sb = new StringBuilder();
        for (String term : cleaned.split("\\s+")) {
            if (term.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append(term).append('%');
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static final RowMapper<JsonObject> LIST_MAPPER = new RowMapper<JsonObject>() {
        @Override
        public JsonObject mapRow(ResultSet rs, int rowNum) throws SQLException {
            JsonObject o = new JsonObject();
            Json.str(o, "id", rs.getString("PUBLIC_ID"));
            Json.str(o, "schemaType", rs.getString("SCHEMA_TYPE"));
            Json.str(o, "title", rs.getString("TITLE"));
            Json.str(o, "shortDescription", rs.getString("SHORT_DESCRIPTION"));
            Json.isoField(o, "startAt", rs.getTimestamp("START_AT"));
            Json.isoField(o, "endAt", rs.getTimestamp("END_AT"));
            Json.str(o, "attendanceMode", rs.getString("ATTENDANCE_MODE"));
            Json.str(o, "eventStatus", rs.getString("EVENT_STATUS"));
            Json.bool(o, "isAccessibleForFree", rs.getString("IS_ACCESSIBLE_FOR_FREE"));
            Json.num(o, "minPrice", Json.bigDecimalOrNull(rs, "MIN_PRICE"));
            Json.str(o, "placeId", rs.getString("PLACE_PUBLIC_ID"));
            Json.str(o, "placeName", rs.getString("PLACE_NAME"));
            Json.str(o, "locality", rs.getString("LOCALITY"));
            Json.str(o, "primaryCategory", rs.getString("PRIMARY_CATEGORY"));
            String note = rs.getString("ACCESSIBILITY_NOTE");
            o.addProperty("hasAccessibilityInfo", note != null && !note.trim().isEmpty());
            return o;
        }
    };

    // ------------------------------------------------------------------
    // Detailabruf
    // ------------------------------------------------------------------

    public JsonObject findPublishedByPublicId(String publicId) {
        // Isolation: Detailabruf nur innerhalb des aktuellen Mandanten/VK, auch wenn
        // die (global eindeutige) PUBLIC_ID bekannt waere.
        MapSqlParameterSource p = new MapSqlParameterSource("pid", publicId)
                .addValue("mandant", ConfigVk.requireMandant())
                .addValue("vk", ConfigVk.requireVkId());

        List<JsonObject> events = jdbc.query(
                "SELECT e.*, "
              + "       p.PUBLIC_ID AS PLACE_PUBLIC_ID, p.NAME AS PLACE_NAME, "
              + "       p.LATITUDE, p.LONGITUDE, p.URL AS PLACE_URL, p.ACCESSIBILITY_NOTE, "
              + "       a.STREET_ADDRESS, a.POSTAL_CODE, a.LOCALITY, a.REGION, a.COUNTRY_CODE, "
              + "       v.NAME AS VLOC_NAME, v.URL AS VLOC_URL, v.ACCESS_HINT, v.PLATFORM "
              + "FROM VK_EVENT e "
              + "LEFT JOIN VK_PLACE p ON p.ID = e.PLACE_ID "
              + "LEFT JOIN VK_ADDRESS a ON a.ID = p.ADDRESS_ID "
              + "LEFT JOIN VK_VIRTUAL_LOCATION v ON v.ID = e.VIRTUAL_LOCATION_ID "
              + "WHERE e.PUBLIC_ID = :pid AND e.MANDANT_ID = :mandant AND e.VK_ID = :vk "
              + "  AND e.WORKFLOW_STATUS = 'PUBLISHED'",
                p, DETAIL_MAPPER);

        if (events.isEmpty()) {
            return null;
        }
        JsonObject event = events.get(0);
        Long eventId = jdbc.queryForObject(
                "SELECT ID FROM VK_EVENT WHERE PUBLIC_ID = :pid AND MANDANT_ID = :mandant AND VK_ID = :vk",
                p, Long.class);
        loadRelations(event, eventId);
        return event;
    }

    private void loadRelations(JsonObject event, Long eventId) {
        MapSqlParameterSource p = new MapSqlParameterSource("eid", eventId);

        final JsonArray categories = new JsonArray();
        jdbc.query("SELECT c.SLUG, c.NAME, ec.PRIMARY_FLAG "
                 + "FROM VK_EVENT_CATEGORY ec JOIN VK_CATEGORY c ON c.ID = ec.CATEGORY_ID "
                 + "WHERE ec.EVENT_ID = :eid ORDER BY ec.PRIMARY_FLAG DESC, c.SORT_ORDER", p,
                (RowCallbackHandler) rs -> {
                    JsonObject c = new JsonObject();
                    Json.str(c, "id", rs.getString("SLUG"));
                    Json.str(c, "name", rs.getString("NAME"));
                    c.addProperty("primary", "Y".equals(rs.getString("PRIMARY_FLAG")));
                    categories.add(c);
                });
        event.add("categories", categories);

        final JsonArray keywords = new JsonArray();
        jdbc.query("SELECT k.NAME FROM VK_EVENT_KEYWORD ek "
                 + "JOIN VK_KEYWORD k ON k.ID = ek.KEYWORD_ID WHERE ek.EVENT_ID = :eid "
                 + "ORDER BY k.NAME", p,
                (RowCallbackHandler) rs -> keywords.add(rs.getString("NAME")));
        event.add("keywords", keywords);

        final JsonArray organizers = new JsonArray();
        final JsonArray performers = new JsonArray();
        final JsonArray sponsors = new JsonArray();
        jdbc.query("SELECT r.ROLE_TYPE, pa.PUBLIC_ID, pa.PARTY_TYPE, pa.DISPLAY_NAME, "
                 + "pa.EMAIL, pa.TELEPHONE, pa.URL "
                 + "FROM VK_EVENT_PARTY_ROLE r JOIN VK_PARTY pa ON pa.ID = r.PARTY_ID "
                 + "WHERE r.EVENT_ID = :eid ORDER BY r.SORT_ORDER", p,
                (RowCallbackHandler) rs -> {
                    JsonObject party = new JsonObject();
                    Json.str(party, "id", rs.getString("PUBLIC_ID"));
                    Json.str(party, "type", rs.getString("PARTY_TYPE"));
                    Json.str(party, "displayName", rs.getString("DISPLAY_NAME"));
                    Json.str(party, "email", rs.getString("EMAIL"));
                    Json.str(party, "telephone", rs.getString("TELEPHONE"));
                    Json.str(party, "url", rs.getString("URL"));
                    String role = rs.getString("ROLE_TYPE");
                    if ("ORGANIZER".equals(role)) {
                        organizers.add(party);
                    } else if ("PERFORMER".equals(role)) {
                        performers.add(party);
                    } else if ("SPONSOR".equals(role)) {
                        sponsors.add(party);
                    }
                });
        event.add("organizers", organizers);
        event.add("performers", performers);
        event.add("sponsors", sponsors);

        final JsonArray offers = new JsonArray();
        jdbc.query("SELECT NAME, PRICE, PRICE_CURRENCY, URL, AVAILABILITY "
                 + "FROM VK_OFFER WHERE EVENT_ID = :eid ORDER BY PRICE", p,
                (RowCallbackHandler) rs -> {
                    JsonObject offer = new JsonObject();
                    Json.str(offer, "name", rs.getString("NAME"));
                    Json.num(offer, "price", Json.bigDecimalOrNull(rs, "PRICE"));
                    Json.str(offer, "priceCurrency", rs.getString("PRICE_CURRENCY"));
                    Json.str(offer, "url", rs.getString("URL"));
                    Json.str(offer, "availability", rs.getString("AVAILABILITY"));
                    offers.add(offer);
                });
        event.add("offers", offers);

        final JsonArray images = new JsonArray();
        jdbc.query("SELECT STORAGE_PATH, ALT_TEXT, COPYRIGHT_TEXT "
                 + "FROM VK_ASSET WHERE EVENT_ID = :eid AND ASSET_TYPE = 'IMAGE' "
                 + "ORDER BY SORT_ORDER", p,
                (RowCallbackHandler) rs -> {
                    JsonObject asset = new JsonObject();
                    Json.str(asset, "url", rs.getString("STORAGE_PATH"));
                    Json.str(asset, "altText", rs.getString("ALT_TEXT"));
                    Json.str(asset, "copyrightText", rs.getString("COPYRIGHT_TEXT"));
                    images.add(asset);
                });
        event.add("images", images);
    }

    private static final RowMapper<JsonObject> DETAIL_MAPPER = new RowMapper<JsonObject>() {
        @Override
        public JsonObject mapRow(ResultSet rs, int rowNum) throws SQLException {
            JsonObject o = new JsonObject();
            Json.str(o, "id", rs.getString("PUBLIC_ID"));
            Json.str(o, "schemaType", rs.getString("SCHEMA_TYPE"));
            Json.str(o, "title", rs.getString("TITLE"));
            Json.str(o, "shortDescription", rs.getString("SHORT_DESCRIPTION"));
            Json.str(o, "description", ClobUtil.readClob(rs, "DESCRIPTION"));
            Json.isoField(o, "startAt", rs.getTimestamp("START_AT"));
            Json.isoField(o, "endAt", rs.getTimestamp("END_AT"));
            Json.isoField(o, "doorTime", rs.getTimestamp("DOOR_TIME"));
            Json.str(o, "durationIso", rs.getString("DURATION_ISO"));
            Json.str(o, "attendanceMode", rs.getString("ATTENDANCE_MODE"));
            Json.str(o, "eventStatus", rs.getString("EVENT_STATUS"));
            Json.str(o, "workflowStatus", rs.getString("WORKFLOW_STATUS"));
            Json.bool(o, "isAccessibleForFree", rs.getString("IS_ACCESSIBLE_FOR_FREE"));
            Json.num(o, "maxAttendeeCapacity", Json.intOrNull(rs, "MAX_ATTENDEE_CAPACITY"));
            Json.num(o, "remainingAttendeeCapacity", Json.intOrNull(rs, "REMAINING_ATTENDEE_CAPACITY"));
            Json.str(o, "canonicalUrl", rs.getString("CANONICAL_URL"));
            Json.str(o, "sameAsUrl", rs.getString("SAME_AS_URL"));
            Json.str(o, "languageCode", rs.getString("LANGUAGE_CODE"));

            if (rs.getString("PLACE_PUBLIC_ID") != null) {
                JsonObject place = new JsonObject();
                Json.str(place, "id", rs.getString("PLACE_PUBLIC_ID"));
                Json.str(place, "name", rs.getString("PLACE_NAME"));
                Json.num(place, "latitude", Json.doubleOrNull(rs, "LATITUDE"));
                Json.num(place, "longitude", Json.doubleOrNull(rs, "LONGITUDE"));
                Json.str(place, "url", rs.getString("PLACE_URL"));
                Json.str(place, "accessibilityNote", rs.getString("ACCESSIBILITY_NOTE"));
                JsonObject addr = new JsonObject();
                Json.str(addr, "streetAddress", rs.getString("STREET_ADDRESS"));
                Json.str(addr, "postalCode", rs.getString("POSTAL_CODE"));
                Json.str(addr, "locality", rs.getString("LOCALITY"));
                Json.str(addr, "region", rs.getString("REGION"));
                Json.str(addr, "countryCode", rs.getString("COUNTRY_CODE"));
                place.add("address", addr);
                o.add("place", place);
            } else {
                o.add("place", com.google.gson.JsonNull.INSTANCE);
            }

            if (rs.getString("VLOC_URL") != null) {
                JsonObject vloc = new JsonObject();
                Json.str(vloc, "name", rs.getString("VLOC_NAME"));
                Json.str(vloc, "url", rs.getString("VLOC_URL"));
                Json.str(vloc, "accessHint", rs.getString("ACCESS_HINT"));
                Json.str(vloc, "platform", rs.getString("PLATFORM"));
                o.add("virtualLocation", vloc);
            } else {
                o.add("virtualLocation", com.google.gson.JsonNull.INSTANCE);
            }
            return o;
        }
    };
}
