package de.example.vk.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.config.GsonFactory;
import de.example.vk.repository.AdminEventRepository;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.EventRepository;
import de.example.vk.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Redaktioneller Workflow (Spezifikation 8/15.4): prüfen, freigeben, veröffentlichen. */
@Service
public class AdminEventService {

    private final AdminEventRepository adminRepository;
    private final EventRepository eventRepository;
    private final ApprovalRepository approvalRepository;
    private final AuditService auditService;
    private final Gson gson = GsonFactory.create();

    public AdminEventService(AdminEventRepository adminRepository, EventRepository eventRepository,
                             ApprovalRepository approvalRepository, AuditService auditService) {
        this.adminRepository = adminRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.auditService = auditService;
    }

    public JsonArray queue(String status) {
        return adminRepository.listByStatus(status);
    }

    public JsonObject getForReview(String publicId) {
        JsonObject ev = eventRepository.findByPublicId(publicId, false);
        if (ev == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        return ev;
    }

    @Transactional
    public void approve(String publicId, String note) {
        long[] ctx = transition(publicId, setOf("SUBMITTED", "IN_REVIEW"), "APPROVED", false);
        approvalRepository.insertReview(ctx[0], "APPROVED", CurrentUser.requireUserId(), note);
        auditService.log(CurrentUser.requireUserId(), "APPROVE_EVENT", "EVENT", ctx[0], note);
    }

    @Transactional
    public void requestChanges(String publicId, String note) {
        long[] ctx = transition(publicId, setOf("SUBMITTED", "IN_REVIEW"), "CHANGES_REQUESTED", false);
        approvalRepository.insertReview(ctx[0], "CHANGES_REQUESTED", CurrentUser.requireUserId(), note);
        auditService.log(CurrentUser.requireUserId(), "REQUEST_CHANGES", "EVENT", ctx[0], note);
    }

    @Transactional
    public void reject(String publicId, String note) {
        long[] ctx = transition(publicId, setOf("SUBMITTED", "IN_REVIEW"), "REJECTED", false);
        approvalRepository.insertReview(ctx[0], "REJECTED", CurrentUser.requireUserId(), note);
        auditService.log(CurrentUser.requireUserId(), "REJECT_EVENT", "EVENT", ctx[0], note);
    }

    @Transactional
    public void publish(String publicId) {
        long[] ctx = transition(publicId, setOf("APPROVED", "SUBMITTED"), "PUBLISHED", true);
        // Versions-Snapshot der veröffentlichten Fassung (Spec 8/9.22)
        JsonObject snapshot = eventRepository.findByPublicId(publicId, false);
        adminRepository.insertVersion(ctx[0], gson.toJson(snapshot), CurrentUser.requireUserId());
        auditService.log(CurrentUser.requireUserId(), "PUBLISH_EVENT", "EVENT", ctx[0], publicId);
    }

    @Transactional
    public void cancel(String publicId) {
        Object[] row = require(publicId);
        // Abgesagt: bleibt veröffentlicht sichtbar, aber als EventCancelled markiert.
        adminRepository.setEventStatus((Long) row[0], "EventCancelled");
        auditService.log(CurrentUser.requireUserId(), "CANCEL_EVENT", "EVENT", (Long) row[0], publicId);
    }

    @Transactional
    public void archive(String publicId) {
        long[] ctx = transition(publicId, setOf("PUBLISHED", "REJECTED", "CANCELLED"), "ARCHIVED", false);
        auditService.log(CurrentUser.requireUserId(), "ARCHIVE_EVENT", "EVENT", ctx[0], publicId);
    }

    // ------------------------------------------------------------------

    private long[] transition(String publicId, Set<String> allowedFrom, String to, boolean publish) {
        Object[] row = require(publicId);
        long eventId = (Long) row[0];
        String current = (String) row[1];
        if (!allowedFrom.contains(current)) {
            throw new ValidationException("status",
                    "Übergang von " + current + " nach " + to + " ist nicht erlaubt.");
        }
        adminRepository.setWorkflowStatus(eventId, to, publish);
        return new long[]{eventId};
    }

    private Object[] require(String publicId) {
        Object[] row = adminRepository.findIdAndStatus(publicId);
        if (row == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        return row;
    }

    private static Set<String> setOf(String... s) {
        return new HashSet<String>(Arrays.asList(s));
    }
}
