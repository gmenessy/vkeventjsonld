package de.example.vk.repository;

import de.example.vk.util.ConfigVk;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Schlichter Werte-Träger für einen Benutzer (kein API-DTO). */
    public static final class UserRow {
        public long id;
        public String publicId;
        public long mandantId;
        public String email;
        public String displayName;
        public String passwordHash;
        public Set<String> roles = new HashSet<String>();
    }

    /** Findet einen aktiven Benutzer im aktuellen Mandanten anhand der E-Mail. */
    public UserRow findActiveByEmail(String email) {
        long mandant = ConfigVk.requireMandant();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("email", email == null ? null : email.trim().toLowerCase())
                .addValue("mandant", mandant);
        UserRow user;
        try {
            user = jdbc.queryForObject(
                    "SELECT ID, PUBLIC_ID, MANDANT_ID, EMAIL, DISPLAY_NAME, PASSWORD_HASH "
                  + "FROM VK_USER WHERE LOWER(EMAIL) = :email AND MANDANT_ID = :mandant AND ACTIVE = 'Y'",
                    p, (rs, n) -> {
                        UserRow u = new UserRow();
                        u.id = rs.getLong("ID");
                        u.publicId = rs.getString("PUBLIC_ID");
                        u.mandantId = rs.getLong("MANDANT_ID");
                        u.email = rs.getString("EMAIL");
                        u.displayName = rs.getString("DISPLAY_NAME");
                        u.passwordHash = rs.getString("PASSWORD_HASH");
                        return u;
                    });
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
        loadRoles(user);
        return user;
    }

    public boolean emailExists(String email) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM VK_USER WHERE LOWER(EMAIL) = :email",
                new MapSqlParameterSource("email", email == null ? null : email.trim().toLowerCase()),
                Integer.class);
        return n != null && n > 0;
    }

    /** Legt einen neuen Selbsteintrags-Nutzer im aktuellen Mandanten an (Rolle REGISTERED). */
    public long createRegisteredUser(String email, String displayName, String passwordHash) {
        long mandant = ConfigVk.requireMandant();
        String publicId = java.util.UUID.randomUUID().toString();
        org.springframework.jdbc.support.GeneratedKeyHolder kh =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("pid", publicId).addValue("mandant", mandant)
                .addValue("email", email.trim()).addValue("name", displayName)
                .addValue("hash", passwordHash);
        jdbc.update("INSERT INTO VK_USER (PUBLIC_ID, MANDANT_ID, EMAIL, DISPLAY_NAME, PASSWORD_HASH) "
                + "VALUES (:pid, :mandant, :email, :name, :hash)", p, kh, new String[]{"ID"});
        long userId = kh.getKey().longValue();
        jdbc.update("INSERT INTO VK_USER_ROLE (USER_ID, ROLE_ID) "
                + "SELECT :uid, ID FROM VK_ROLE WHERE NAME = 'REGISTERED'",
                new MapSqlParameterSource("uid", userId));
        return userId;
    }

    private void loadRoles(UserRow user) {
        jdbc.query("SELECT r.NAME FROM VK_USER_ROLE ur JOIN VK_ROLE r ON r.ID = ur.ROLE_ID "
                 + "WHERE ur.USER_ID = :uid",
                new MapSqlParameterSource("uid", user.id),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> user.roles.add(rs.getString("NAME")));
    }
}
