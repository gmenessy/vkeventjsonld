package de.example.vk.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.example.vk.config.GsonFactory;
import de.example.vk.repository.AdminEventRepository;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.EventRepository;
import de.example.vk.repository.EventWriteRepository;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.util.CurrentUser;
import de.example.vk.util.Json;
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
    private final EventWriteRepository writeRepository;
    private final EventInputMapper mapper;
    private final EventValidator validator;
    private final Gson gson = GsonFactory.create();

    public AdminEventService(AdminEventRepository adminRepository, EventRepository eventRepository,
                             ApprovalRepository approvalRepository, AuditService auditService,
                             EventWriteRepository writeRepository, EventInputMapper mapper,
                             EventValidator validator) {
        this.adminRepository = adminRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.auditService = auditService;
        this.writeRepository = writeRepository;
        this.mapper = mapper;
        this.validator = validator;
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

    // ---- Versions-Historie ----

    /** Versionsliste eines Events (neueste zuerst). */
    public JsonArray versions(String publicId) {
        Object[] row = require(publicId);
        return adminRepository.listVersions((Long) row[0]);
    }

    /** Snapshot einer Version als Event-Detail-JSON. */
    public JsonObject versionSnapshot(String publicId, int versionNo) {
        Object[] row = require(publicId);
        String json = adminRepository.getVersionSnapshot((Long) row[0], versionNo);
        if (json == null) {
            throw new NotFoundException("Version " + versionNo + " nicht gefunden.");
        }
        JsonElement parsed = JsonParser.parseString(json);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    /** Stellt den Inhalt einer früheren Version wieder her (Status bleibt unverändert). */
    @Transactional
    public void restoreVersion(String publicId, int versionNo) {
        Object[] row = require(publicId);
        long eventId = (Long) row[0];
        String json = adminRepository.getVersionSnapshot(eventId, versionNo);
        if (json == null) {
            throw new NotFoundException("Version " + versionNo + " nicht gefunden.");
        }
        JsonObject snapshot = JsonParser.parseString(json).getAsJsonObject();
        EventInput in = mapper.fromJson(snapshotToInput(snapshot));
        validator.validate(in);
        long userId = CurrentUser.requireUserId();
        writeRepository.updateDraft(eventId, in, userId);
        // Die Wiederherstellung selbst als neue Version festhalten.
        JsonObject restored = eventRepository.findByPublicId(publicId, false);
        adminRepository.insertVersion(eventId, gson.toJson(restored), userId);
        auditService.log(userId, "RESTORE_VERSION", "EVENT", eventId, "Version " + versionNo);
    }

    /** Audit-Trail der Event-Aktionen dieses Mandanten/VK. */
    public JsonArray auditTrail(int limit) {
        int lim = limit <= 0 || limit > 500 ? 100 : limit;
        return adminRepository.listEventAudit(lim);
    }

    /** Wandelt ein Detail-Snapshot-JSON in die Eingabeform (Slugs/Freitext) um. */
    private JsonObject snapshotToInput(JsonObject s) {
        JsonObject in = new JsonObject();
        copy(in, s, "title", "shortDescription", "startAt", "endAt", "doorTime", "durationIso",
                "attendanceMode", "schemaType");
        Json.str(in, "description", Json.optString(s, "description"));
        if (s.has("isAccessibleForFree")) {
            in.addProperty("isAccessibleForFree", Json.optBool(s, "isAccessibleForFree"));
        }
        if (s.has("place") && s.get("place").isJsonObject()) {
            Json.str(in, "place", Json.optString(s.getAsJsonObject("place"), "id"));
        }
        if (s.has("virtualLocation") && s.get("virtualLocation").isJsonObject()) {
            Json.str(in, "virtualUrl", Json.optString(s.getAsJsonObject("virtualLocation"), "url"));
        }
        JsonArray cats = s.getAsJsonArray("categories");
        if (cats != null && cats.size() > 0) {
            Json.str(in, "category", Json.optString(cats.get(0).getAsJsonObject(), "id"));
        }
        JsonArray orgs = s.getAsJsonArray("organizers");
        if (orgs != null && orgs.size() > 0) {
            JsonObject o = orgs.get(0).getAsJsonObject();
            Json.str(in, "organizerName", Json.optString(o, "displayName"));
            Json.str(in, "organizerEmail", Json.optString(o, "email"));
        }
        carryArray(in, s, "keywords");
        carryArray(in, s, "offers");
        carryArray(in, s, "performers");
        carryArray(in, s, "sponsors");
        carryArray(in, s, "images");
        carryArray(in, s, "documents");
        return in;
    }

    private static void copy(JsonObject target, JsonObject src, String... keys) {
        for (String k : keys) {
            String v = Json.optString(src, k);
            if (v != null) {
                Json.str(target, k, v);
            }
        }
    }

    private static void carryArray(JsonObject target, JsonObject src, String key) {
        if (src.has(key) && src.get(key).isJsonArray()) {
            target.add(key, src.getAsJsonArray(key));
        }
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
