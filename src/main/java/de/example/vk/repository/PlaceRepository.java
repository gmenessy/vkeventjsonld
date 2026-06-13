package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.Json;
import de.example.vk.util.SearchTextUtil;
import de.example.vk.util.ConfigVk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PlaceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JsonArray suggest(String q, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", "%" + SearchTextUtil.normalizeTerm(q) + "%")
                .addValue("lim", limit)
                .addValue("mandant", ConfigVk.requireMandant())
                .addValue("vk", ConfigVk.requireVkId());
        final JsonArray result = new JsonArray();
        jdbc.query(
                "SELECT p.PUBLIC_ID, p.NAME, a.LOCALITY "
              + "FROM VK_PLACE p LEFT JOIN VK_ADDRESS a ON a.ID = p.ADDRESS_ID "
              + "WHERE p.MANDANT_ID = :mandant AND p.VK_ID = :vk "
              + "  AND (LOWER(p.NAME) LIKE :q OR LOWER(a.LOCALITY) LIKE :q) "
              + "ORDER BY p.NAME FETCH FIRST :lim ROWS ONLY",
                params,
                rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "id", rs.getString("PUBLIC_ID"));
                    Json.str(o, "name", rs.getString("NAME"));
                    Json.str(o, "locality", rs.getString("LOCALITY"));
                    result.add(o);
                });
        return result;
    }
}
