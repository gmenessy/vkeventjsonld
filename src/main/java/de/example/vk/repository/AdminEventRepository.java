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
}
