package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.Json;
import de.example.vk.util.ConfigVk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle aktiven Kategorie-Slugs (global; für die NL-Suche-Validierung). */
    public java.util.Set<String> activeSlugs() {
        final java.util.Set<String> slugs = new java.util.LinkedHashSet<String>();
        jdbc.query("SELECT SLUG FROM VK_CATEGORY WHERE ACTIVE = 'Y'",
                new MapSqlParameterSource(),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> slugs.add(rs.getString("SLUG")));
        return slugs;
    }

    /** Aktiver Kategoriebaum inkl. Anzahl veroeffentlichter Events je Kategorie. */
    public JsonArray findTree() {
        final Map<Long, JsonObject> byId = new HashMap<Long, JsonObject>();
        final Map<Long, long[]> counts = new HashMap<Long, long[]>();
        final Map<Long, Long> parentOf = new HashMap<Long, Long>();
        final List<Long> order = new ArrayList<Long>();

        // Kategorie-Taxonomie ist global; die Event-Zaehlung ist mandantengescoped.
        MapSqlParameterSource tenant = new MapSqlParameterSource()
                .addValue("mandant", ConfigVk.requireMandant())
                .addValue("vk", ConfigVk.requireVkId());
        jdbc.query("SELECT c.ID, c.PARENT_ID, c.NAME, c.SLUG, c.ICON, "
                 + "       (SELECT COUNT(*) FROM VK_EVENT_CATEGORY ec "
                 + "          JOIN VK_EVENT e ON e.ID = ec.EVENT_ID "
                 + "         WHERE ec.CATEGORY_ID = c.ID "
                 + "           AND e.MANDANT_ID = :mandant AND e.VK_ID = :vk "
                 + "           AND e.WORKFLOW_STATUS = 'PUBLISHED') AS EVENT_COUNT "
                 + "FROM VK_CATEGORY c WHERE c.ACTIVE = 'Y' "
                 + "ORDER BY c.SORT_ORDER, c.NAME",
                tenant,
                rs -> {
                    long id = rs.getLong("ID");
                    JsonObject node = new JsonObject();
                    Json.str(node, "id", rs.getString("SLUG"));
                    Json.str(node, "name", rs.getString("NAME"));
                    Json.str(node, "icon", rs.getString("ICON"));
                    node.add("children", new JsonArray());
                    byId.put(id, node);
                    counts.put(id, new long[]{rs.getLong("EVENT_COUNT")});
                    long parentId = rs.getLong("PARENT_ID");
                    parentOf.put(id, rs.wasNull() ? null : parentId);
                    order.add(id);
                });

        JsonArray roots = new JsonArray();
        for (Long id : order) {
            Long parentId = parentOf.get(id);
            JsonObject node = byId.get(id);
            if (parentId == null || byId.get(parentId) == null) {
                roots.add(node);
            } else {
                byId.get(parentId).getAsJsonArray("children").add(node);
                counts.get(parentId)[0] += counts.get(id)[0];
            }
        }
        // eventCount erst nach Aggregation setzen
        for (Long id : order) {
            byId.get(id).addProperty("eventCount", counts.get(id)[0]);
        }
        return roots;
    }
}
