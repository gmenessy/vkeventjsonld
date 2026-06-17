package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.Json;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lese-/Schreibzugriffe der Redaktion über alle Workflow-Status (tenant-gescoped). */
@Repository
public class AdminEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Review-Liste, optional nach Workflow-Status gefiltert. */
    public JsonArray listByStatus(String status) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId());
        StringBuilder sql = new StringBuilder(
                "SELECT e.PUBLIC_ID, e.TITLE, e.START_AT, e.WORKFLOW_STATUS, e.EVENT_STATUS, "
              + "u.DISPLAY_NAME AS CREATOR "
              + "FROM VK_EVENT e LEFT JOIN VK_USER u ON u.ID = e.CREATED_BY "
              + "WHERE e.MANDANT_ID = :m AND e.VK_ID = :vk");
        if (status != null && !status.trim().isEmpty()) {
            sql.append(" AND e.WORKFLOW_STATUS = :status");
            p.addValue("status", status.trim().toUpperCase());
        }
        sql.append(" ORDER BY COALESCE(e.UPDATED_AT, e.CREATED_AT) DESC");
        final JsonArray out = new JsonArray();
        jdbc.query(sql.toString(), p, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            JsonObject o = new JsonObject();
            Json.str(o, "id", rs.getString("PUBLIC_ID"));
            Json.str(o, "title", rs.getString("TITLE"));
            Json.isoField(o, "startAt", rs.getTimestamp("START_AT"));
            Json.str(o, "workflowStatus", rs.getString("WORKFLOW_STATUS"));
            Json.str(o, "eventStatus", rs.getString("EVENT_STATUS"));
            Json.str(o, "creator", rs.getString("CREATOR"));
            out.add(o);
        });
        return out;
    }

    /** Interne ID + aktueller Workflow-Status eines Events im Mandanten oder null. */
    public Object[] findIdAndStatus(String publicId) {
        try {
            return jdbc.queryForObject(
                    "SELECT ID, WORKFLOW_STATUS FROM VK_EVENT WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m AND VK_ID=:vk",
                    new MapSqlParameterSource().addValue("pid", publicId)
                            .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId()),
                    (rs, n) -> new Object[]{rs.getLong("ID"), rs.getString("WORKFLOW_STATUS")});
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void setWorkflowStatus(long eventId, String status, boolean publish) {
        String extra = publish ? ", PUBLISHED_AT = CURRENT_TIMESTAMP" : "";
        jdbc.update("UPDATE VK_EVENT SET WORKFLOW_STATUS=:s" + extra
                + ", UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id",
                new MapSqlParameterSource().addValue("s", status).addValue("id", eventId));
    }

    public void setEventStatus(long eventId, String eventStatus) {
        jdbc.update("UPDATE VK_EVENT SET EVENT_STATUS=:s, UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id",
                new MapSqlParameterSource().addValue("s", eventStatus).addValue("id", eventId));
    }

    public void insertVersion(long eventId, String snapshotJson, long createdBy) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO),0)+1 FROM VK_EVENT_VERSION WHERE EVENT_ID=:eid",
                new MapSqlParameterSource("eid", eventId), Integer.class);
        jdbc.update("INSERT INTO VK_EVENT_VERSION (EVENT_ID, VERSION_NO, SNAPSHOT_JSON, CREATED_BY, CREATED_AT) "
                + "VALUES (:eid, :ver, :json, :by, CURRENT_TIMESTAMP)",
                new MapSqlParameterSource().addValue("eid", eventId).addValue("ver", next)
                        .addValue("json", snapshotJson).addValue("by", createdBy));
    }

    /** Versionsliste eines Events (neueste zuerst), tenant-gescoped über das Event. */
    public JsonArray listVersions(long eventId) {
        final JsonArray out = new JsonArray();
        jdbc.query("SELECT v.VERSION_NO, v.CREATED_AT, u.DISPLAY_NAME AS AUTHOR "
                 + "FROM VK_EVENT_VERSION v LEFT JOIN VK_USER u ON u.ID = v.CREATED_BY "
                 + "WHERE v.EVENT_ID = :eid ORDER BY v.VERSION_NO DESC",
                new MapSqlParameterSource("eid", eventId),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    Json.num(o, "versionNo", rs.getInt("VERSION_NO"));
                    Json.isoField(o, "createdAt", rs.getTimestamp("CREATED_AT"));
                    Json.str(o, "author", rs.getString("AUTHOR"));
                    out.add(o);
                });
        return out;
    }

    /** Snapshot-JSON einer bestimmten Version oder null. */
    public String getVersionSnapshot(long eventId, int versionNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT SNAPSHOT_JSON FROM VK_EVENT_VERSION WHERE EVENT_ID=:eid AND VERSION_NO=:ver",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("ver", versionNo),
                    (rs, n) -> de.example.vk.util.ClobUtil.readClob(rs, "SNAPSHOT_JSON"));
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** Audit-Einträge dieses Mandanten/VK (neueste zuerst), begrenzt. Tenant-gescoped
     *  über die eigenen Spalten des Audit-Logs (deckt alle Entitätstypen ab). */
    public JsonArray listEventAudit(int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId())
                .addValue("lim", limit);
        final JsonArray out = new JsonArray();
        jdbc.query("SELECT a.ACTION, a.ENTITY_TYPE, a.NEW_VALUE_JSON, a.CREATED_AT, "
                 + "u.DISPLAY_NAME AS AUTHOR, e.TITLE AS EVENT_TITLE "
                 + "FROM VK_AUDIT_LOG a "
                 + "LEFT JOIN VK_EVENT e ON e.ID = a.ENTITY_ID AND a.ENTITY_TYPE = 'EVENT' "
                 + "LEFT JOIN VK_USER u ON u.ID = a.USER_ID "
                 + "WHERE a.MANDANT_ID = :m AND a.VK_ID = :vk "
                 + "ORDER BY a.CREATED_AT DESC, a.ID DESC FETCH FIRST :lim ROWS ONLY", p,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "action", rs.getString("ACTION"));
                    Json.str(o, "entityType", rs.getString("ENTITY_TYPE"));
                    Json.str(o, "eventTitle", rs.getString("EVENT_TITLE"));
                    Json.str(o, "note", rs.getString("NEW_VALUE_JSON"));
                    Json.str(o, "author", rs.getString("AUTHOR"));
                    Json.isoField(o, "createdAt", rs.getTimestamp("CREATED_AT"));
                    out.add(o);
                });
        return out;
    }
}
