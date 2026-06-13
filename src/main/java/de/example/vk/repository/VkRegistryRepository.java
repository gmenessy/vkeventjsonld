package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.Json;
import de.example.vk.util.VkConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VkRegistryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public VkRegistryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle aktiven VKs des aktuellen Mandanten (fuer den VK-Umschalter in der SPA). */
    public JsonArray listForCurrentMandant() {
        long mandant = VkConfig.requireMandant();
        long currentVk = VkConfig.requireVkId();
        final JsonArray result = new JsonArray();
        jdbc.query("SELECT VK_ID, NAME, SLUG FROM VK_REGISTRY "
                 + "WHERE MANDANT_ID = :mandant AND ACTIVE = 'Y' ORDER BY NAME",
                new MapSqlParameterSource("mandant", mandant),
                rs -> {
                    JsonObject o = new JsonObject();
                    long vkId = rs.getLong("VK_ID");
                    o.addProperty("vkId", vkId);
                    Json.str(o, "name", rs.getString("NAME"));
                    Json.str(o, "slug", rs.getString("SLUG"));
                    o.addProperty("current", vkId == currentVk);
                    result.add(o);
                });
        return result;
    }
}
