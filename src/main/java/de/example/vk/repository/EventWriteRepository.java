package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.Json;
import de.example.vk.util.SearchTextUtil;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Schreibender Zugriff auf Events (Selbsteintrag/Redaktion), tenant- und besitzergescoped. */
@Repository
public class EventWriteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EventWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Eingabewerte für Anlage/Änderung (kein API-DTO). */
    public static final class EventInput {
        public String schemaType = "Event";
        public String title;
        public String shortDescription;
        public String descriptionHtml; // bereits sanitisiert
        public Timestamp startAt;
        public Timestamp endAt;
        public String attendanceMode = "OFFLINE";
        public boolean accessibleForFree;
        public String categorySlug;
        public String placePublicId;   // bestehender Ort
        public String virtualUrl;      // bei ONLINE/MIXED
        public String organizerName;
        public String organizerEmail;
        public List<String> keywords;
    }

    @Transactional
    public String createDraft(EventInput in, long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        String publicId = UUID.randomUUID().toString();

        Long placeId = resolvePlaceId(in.placePublicId);
        Long vlocId = createVirtualLocation(in.virtualUrl);

        KeyHolder kh = new GeneratedKeyHolder();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("pid", publicId).addValue("mandant", mandant).addValue("vk", vk)
                .addValue("type", in.schemaType).addValue("title", in.title)
                .addValue("shortDesc", in.shortDescription).addValue("desc", in.descriptionHtml)
                .addValue("startAt", in.startAt).addValue("endAt", in.endAt)
                .addValue("mode", in.attendanceMode)
                .addValue("free", in.accessibleForFree ? "Y" : "N")
                .addValue("placeId", placeId).addValue("vlocId", vlocId)
                .addValue("search", buildSearchText(in))
                .addValue("createdBy", userId);
        jdbc.update("INSERT INTO VK_EVENT (PUBLIC_ID, MANDANT_ID, VK_ID, SCHEMA_TYPE, TITLE, SHORT_DESCRIPTION, "
                + "DESCRIPTION, START_AT, END_AT, ATTENDANCE_MODE, EVENT_STATUS, WORKFLOW_STATUS, "
                + "IS_ACCESSIBLE_FOR_FREE, PLACE_ID, VIRTUAL_LOCATION_ID, SEARCH_TEXT, CREATED_BY, CREATED_AT) "
                + "VALUES (:pid, :mandant, :vk, :type, :title, :shortDesc, :desc, :startAt, :endAt, :mode, "
                + "'EventScheduled', 'DRAFT', :free, :placeId, :vlocId, :search, :createdBy, CURRENT_TIMESTAMP)",
                p, kh, new String[]{"ID"});
        long eventId = kh.getKey().longValue();
        writeRelations(eventId, in, mandant, vk);
        return publicId;
    }

    @Transactional
    public void updateDraft(long eventId, EventInput in, long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        Long placeId = resolvePlaceId(in.placePublicId);
        Long vlocId = createVirtualLocation(in.virtualUrl);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("type", in.schemaType).addValue("title", in.title)
                .addValue("shortDesc", in.shortDescription).addValue("desc", in.descriptionHtml)
                .addValue("startAt", in.startAt).addValue("endAt", in.endAt)
                .addValue("mode", in.attendanceMode)
                .addValue("free", in.accessibleForFree ? "Y" : "N")
                .addValue("placeId", placeId).addValue("vlocId", vlocId)
                .addValue("search", buildSearchText(in))
                .addValue("updatedBy", userId);
        jdbc.update("UPDATE VK_EVENT SET SCHEMA_TYPE=:type, TITLE=:title, SHORT_DESCRIPTION=:shortDesc, "
                + "DESCRIPTION=:desc, START_AT=:startAt, END_AT=:endAt, ATTENDANCE_MODE=:mode, "
                + "IS_ACCESSIBLE_FOR_FREE=:free, PLACE_ID=:placeId, VIRTUAL_LOCATION_ID=:vlocId, "
                + "SEARCH_TEXT=:search, UPDATED_BY=:updatedBy, UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id", p);
        // Beziehungen ersetzen
        MapSqlParameterSource eid = new MapSqlParameterSource("eid", eventId);
        jdbc.update("DELETE FROM VK_EVENT_CATEGORY WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_EVENT_KEYWORD WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_EVENT_PARTY_ROLE WHERE EVENT_ID = :eid", eid);
        writeRelations(eventId, in, mandant, vk);
    }

    // ------------------------------------------------------------------
    // Eigene Events lesen (Besitzer)
    // ------------------------------------------------------------------

    public JsonArray listOwn(long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        final JsonArray out = new JsonArray();
        jdbc.query("SELECT PUBLIC_ID, TITLE, START_AT, WORKFLOW_STATUS, EVENT_STATUS, UPDATED_AT, CREATED_AT "
                + "FROM VK_EVENT WHERE MANDANT_ID=:m AND VK_ID=:vk AND CREATED_BY=:uid "
                + "ORDER BY COALESCE(UPDATED_AT, CREATED_AT) DESC",
                new MapSqlParameterSource().addValue("m", mandant).addValue("vk", vk).addValue("uid", userId),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "id", rs.getString("PUBLIC_ID"));
                    Json.str(o, "title", rs.getString("TITLE"));
                    Json.isoField(o, "startAt", rs.getTimestamp("START_AT"));
                    Json.str(o, "workflowStatus", rs.getString("WORKFLOW_STATUS"));
                    Json.str(o, "eventStatus", rs.getString("EVENT_STATUS"));
                    out.add(o);
                });
        return out;
    }

    /** Liefert die interne ID + Status eines eigenen Events oder null. */
    public long[] findOwnIdAndStatus(String publicId, long userId, String[] statusOut) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        try {
            return jdbc.queryForObject(
                    "SELECT ID, WORKFLOW_STATUS FROM VK_EVENT WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m "
                  + "AND VK_ID=:vk AND CREATED_BY=:uid",
                    new MapSqlParameterSource().addValue("pid", publicId).addValue("m", mandant)
                            .addValue("vk", vk).addValue("uid", userId),
                    (rs, n) -> {
                        statusOut[0] = rs.getString("WORKFLOW_STATUS");
                        return new long[]{rs.getLong("ID")};
                    });
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void setWorkflowStatus(long eventId, String status) {
        jdbc.update("UPDATE VK_EVENT SET WORKFLOW_STATUS=:s, UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id",
                new MapSqlParameterSource().addValue("s", status).addValue("id", eventId));
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private void writeRelations(long eventId, EventInput in, long mandant, long vk) {
        if (in.categorySlug != null && !in.categorySlug.isEmpty()) {
            jdbc.update("INSERT INTO VK_EVENT_CATEGORY (EVENT_ID, CATEGORY_ID, PRIMARY_FLAG) "
                    + "SELECT :eid, ID, 'Y' FROM VK_CATEGORY WHERE SLUG = :slug",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("slug", in.categorySlug));
        }
        if (in.keywords != null) {
            for (String kw : in.keywords) {
                String name = kw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                long kwId = resolveKeyword(name);
                jdbc.update("INSERT INTO VK_EVENT_KEYWORD (EVENT_ID, KEYWORD_ID) VALUES (:eid, :kid)",
                        new MapSqlParameterSource().addValue("eid", eventId).addValue("kid", kwId));
            }
        }
        if (in.organizerName != null && !in.organizerName.trim().isEmpty()) {
            long partyId = resolveOrganizer(in.organizerName.trim(), in.organizerEmail, mandant, vk);
            jdbc.update("INSERT INTO VK_EVENT_PARTY_ROLE (EVENT_ID, PARTY_ID, ROLE_TYPE, SORT_ORDER) "
                    + "VALUES (:eid, :pid, 'ORGANIZER', 0)",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("pid", partyId));
        }
    }

    private Long resolvePlaceId(String placePublicId) {
        if (placePublicId == null || placePublicId.trim().isEmpty()) {
            return null;
        }
        try {
            return jdbc.queryForObject("SELECT ID FROM VK_PLACE WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m AND VK_ID=:vk",
                    new MapSqlParameterSource().addValue("pid", placePublicId)
                            .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId()),
                    Long.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Long createVirtualLocation(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO VK_VIRTUAL_LOCATION (URL) VALUES (:url)",
                new MapSqlParameterSource("url", url.trim()), kh, new String[]{"ID"});
        return kh.getKey().longValue();
    }

    private long resolveKeyword(String name) {
        String slug = SearchTextUtil.normalizeTerm(name).replace(' ', '-');
        try {
            return jdbc.queryForObject("SELECT ID FROM VK_KEYWORD WHERE SLUG=:slug",
                    new MapSqlParameterSource("slug", slug), Long.class);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO VK_KEYWORD (NAME, SLUG) VALUES (:name, :slug)",
                    new MapSqlParameterSource().addValue("name", name).addValue("slug", slug),
                    kh, new String[]{"ID"});
            return kh.getKey().longValue();
        }
    }

    private long resolveOrganizer(String name, String email, long mandant, long vk) {
        try {
            return jdbc.queryForObject(
                    "SELECT ID FROM VK_PARTY WHERE MANDANT_ID=:m AND VK_ID=:vk AND DISPLAY_NAME=:name "
                  + "AND PARTY_TYPE='ORGANIZATION'",
                    new MapSqlParameterSource().addValue("m", mandant).addValue("vk", vk).addValue("name", name),
                    Long.class);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO VK_PARTY (PUBLIC_ID, MANDANT_ID, VK_ID, PARTY_TYPE, DISPLAY_NAME, EMAIL) "
                    + "VALUES (:pid, :m, :vk, 'ORGANIZATION', :name, :email)",
                    new MapSqlParameterSource().addValue("pid", UUID.randomUUID().toString())
                            .addValue("m", mandant).addValue("vk", vk).addValue("name", name)
                            .addValue("email", email),
                    kh, new String[]{"ID"});
            return kh.getKey().longValue();
        }
    }

    private String buildSearchText(EventInput in) {
        String kw = in.keywords == null ? "" : String.join(" ", in.keywords);
        return SearchTextUtil.build(in.title, in.shortDescription, in.categorySlug,
                in.organizerName, kw);
    }
}
