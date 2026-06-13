package de.example.vk.repository;

import de.example.vk.util.ConfigVk;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VkRegistryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public VkRegistryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Anzeigename des VK, den dieses System bedient (aus VK_REGISTRY). */
    public String currentName() {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("mandant", ConfigVk.requireMandant())
                .addValue("vk", ConfigVk.requireVkId());
        try {
            return jdbc.queryForObject(
                    "SELECT NAME FROM VK_REGISTRY WHERE MANDANT_ID = :mandant AND VK_ID = :vk",
                    params, String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }
}
