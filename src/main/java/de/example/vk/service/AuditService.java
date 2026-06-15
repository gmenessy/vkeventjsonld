package de.example.vk.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Schreibt das Audit-Log für schreibende Aktionen (Spezifikation 26.4). */
@Service
public class AuditService {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(Long userId, String action, String entityType, Long entityId, String note) {
        jdbc.update("INSERT INTO VK_AUDIT_LOG (USER_ID, ENTITY_TYPE, ENTITY_ID, ACTION, NEW_VALUE_JSON, CREATED_AT) "
                + "VALUES (:uid, :etype, :eid, :action, :note, CURRENT_TIMESTAMP)",
                new MapSqlParameterSource().addValue("uid", userId).addValue("etype", entityType)
                        .addValue("eid", entityId).addValue("action", action).addValue("note", note));
    }
}
