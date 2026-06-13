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
public class PartyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PartyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Veranstalter-Vorschlaege: nur Parties, die als ORGANIZER an Events haengen. */
    public JsonArray suggestOrganizers(String q, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", "%" + SearchTextUtil.normalizeTerm(q) + "%")
                .addValue("lim", limit)
                .addValue("mandant", ConfigVk.requireMandant())
                .addValue("vk", ConfigVk.requireVkId());
        final JsonArray result = new JsonArray();
        jdbc.query(
                "SELECT DISTINCT pa.PUBLIC_ID, pa.DISPLAY_NAME "
              + "FROM VK_PARTY pa "
              + "WHERE pa.MANDANT_ID = :mandant AND pa.VK_ID = :vk "
              + "  AND LOWER(pa.DISPLAY_NAME) LIKE :q "
              + "  AND EXISTS (SELECT 1 FROM VK_EVENT_PARTY_ROLE r "
              + "              WHERE r.PARTY_ID = pa.ID AND r.ROLE_TYPE = 'ORGANIZER') "
              + "ORDER BY pa.DISPLAY_NAME FETCH FIRST :lim ROWS ONLY",
                params,
                rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "id", rs.getString("PUBLIC_ID"));
                    Json.str(o, "name", rs.getString("DISPLAY_NAME"));
                    result.add(o);
                });
        return result;
    }
}
