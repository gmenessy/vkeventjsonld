package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.Json;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/** Persistenz der Import-Jobs und -Items (Spezifikation 9.23/9.24), tenant-gescoped. */
@Repository
public class ImportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createJob(String type, String fileName, String publicId, long userId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO VK_IMPORT_JOB (PUBLIC_ID, MANDANT_ID, VK_ID, IMPORT_TYPE, STATUS, FILE_NAME, "
                + "CREATED_BY, CREATED_AT) VALUES (:pid, :m, :vk, :type, 'RUNNING', :file, :by, CURRENT_TIMESTAMP)",
                new MapSqlParameterSource().addValue("pid", publicId)
                        .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId())
                        .addValue("type", type).addValue("file", fileName).addValue("by", userId),
                kh, new String[]{"ID"});
        return kh.getKey().longValue();
    }

    public void addItem(long jobId, int rowNo, String status, String externalId,
                        Long eventId, String rawJson, String error) {
        jdbc.update("INSERT INTO VK_IMPORT_ITEM (IMPORT_JOB_ID, ROW_NO, STATUS, EXTERNAL_ID, EVENT_ID, "
                + "RAW_JSON, ERROR_MESSAGE) VALUES (:job, :row, :status, :ext, :eid, :raw, :err)",
                new MapSqlParameterSource().addValue("job", jobId).addValue("row", rowNo)
                        .addValue("status", status).addValue("ext", externalId).addValue("eid", eventId)
                        .addValue("raw", rawJson).addValue("err", error));
    }

    public void finishJob(long jobId, String status) {
        jdbc.update("UPDATE VK_IMPORT_JOB SET STATUS=:s, FINISHED_AT=CURRENT_TIMESTAMP WHERE ID=:id",
                new MapSqlParameterSource().addValue("s", status).addValue("id", jobId));
    }

    /** Dublettenprüfung (Spec 27.3): Titel + Startzeitpunkt im aktuellen VK. */
    public boolean duplicateExists(String title, Timestamp startAt) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM VK_EVENT WHERE MANDANT_ID=:m AND VK_ID=:vk AND TITLE=:t AND START_AT=:s",
                new MapSqlParameterSource().addValue("m", ConfigVk.requireMandant())
                        .addValue("vk", ConfigVk.requireVkId()).addValue("t", title).addValue("s", startAt),
                Integer.class);
        return n != null && n > 0;
    }

    /** Ort per Name im aktuellen VK auflösen -> PUBLIC_ID (für CSV ohne ID) oder null. */
    public String resolvePlacePublicIdByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                    "SELECT PUBLIC_ID FROM VK_PLACE WHERE MANDANT_ID=:m AND VK_ID=:vk AND LOWER(NAME)=LOWER(:n) "
                  + "FETCH FIRST 1 ROWS ONLY",
                    new MapSqlParameterSource().addValue("m", ConfigVk.requireMandant())
                            .addValue("vk", ConfigVk.requireVkId()).addValue("n", name.trim()),
                    String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public Long eventIdByPublicId(String publicId) {
        try {
            return jdbc.queryForObject(
                    "SELECT ID FROM VK_EVENT WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m AND VK_ID=:vk",
                    new MapSqlParameterSource().addValue("pid", publicId)
                            .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId()),
                    Long.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public JsonArray listJobs() {
        final JsonArray out = new JsonArray();
        jdbc.query("SELECT PUBLIC_ID, IMPORT_TYPE, STATUS, FILE_NAME, CREATED_AT, FINISHED_AT "
                + "FROM VK_IMPORT_JOB WHERE MANDANT_ID=:m AND VK_ID=:vk ORDER BY CREATED_AT DESC",
                new MapSqlParameterSource().addValue("m", ConfigVk.requireMandant())
                        .addValue("vk", ConfigVk.requireVkId()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "id", rs.getString("PUBLIC_ID"));
                    Json.str(o, "type", rs.getString("IMPORT_TYPE"));
                    Json.str(o, "status", rs.getString("STATUS"));
                    Json.str(o, "fileName", rs.getString("FILE_NAME"));
                    Json.isoField(o, "createdAt", rs.getTimestamp("CREATED_AT"));
                    out.add(o);
                });
        return out;
    }

    public JsonObject getJob(String publicId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", publicId)
                .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId());
        JsonObject job;
        try {
            job = jdbc.queryForObject(
                    "SELECT ID, PUBLIC_ID, IMPORT_TYPE, STATUS, FILE_NAME FROM VK_IMPORT_JOB "
                  + "WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m AND VK_ID=:vk",
                    p, (rs, n) -> {
                        JsonObject o = new JsonObject();
                        o.addProperty("_id", rs.getLong("ID"));
                        Json.str(o, "id", rs.getString("PUBLIC_ID"));
                        Json.str(o, "type", rs.getString("IMPORT_TYPE"));
                        Json.str(o, "status", rs.getString("STATUS"));
                        Json.str(o, "fileName", rs.getString("FILE_NAME"));
                        return o;
                    });
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        long jobId = job.get("_id").getAsLong();
        job.remove("_id");
        final JsonArray items = new JsonArray();
        jdbc.query("SELECT ROW_NO, STATUS, ERROR_MESSAGE, EXTERNAL_ID FROM VK_IMPORT_ITEM "
                + "WHERE IMPORT_JOB_ID=:job ORDER BY ROW_NO",
                new MapSqlParameterSource("job", jobId),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("row", rs.getInt("ROW_NO"));
                    Json.str(o, "status", rs.getString("STATUS"));
                    Json.str(o, "error", rs.getString("ERROR_MESSAGE"));
                    Json.str(o, "eventId", rs.getString("EXTERNAL_ID"));
                    items.add(o);
                });
        job.add("items", items);
        return job;
    }
}
