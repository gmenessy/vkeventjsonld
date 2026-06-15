package de.example.vk.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Schreibt Einträge in VK_EVENT_APPROVAL (Redaktionsprozess, Spezifikation 9.21). */
@Repository
public class ApprovalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSubmission(long eventId, long submittedBy) {
        jdbc.update("INSERT INTO VK_EVENT_APPROVAL (EVENT_ID, STATUS, SUBMITTED_BY, SUBMITTED_AT) "
                + "VALUES (:eid, 'SUBMITTED', :by, CURRENT_TIMESTAMP)",
                new MapSqlParameterSource().addValue("eid", eventId).addValue("by", submittedBy));
    }

    public void insertReview(long eventId, String status, long reviewedBy, String note) {
        jdbc.update("INSERT INTO VK_EVENT_APPROVAL (EVENT_ID, STATUS, REVIEWED_BY, REVIEWED_AT, REVIEW_NOTE) "
                + "VALUES (:eid, :status, :by, CURRENT_TIMESTAMP, :note)",
                new MapSqlParameterSource().addValue("eid", eventId).addValue("status", status)
                        .addValue("by", reviewedBy).addValue("note", note));
    }
}
