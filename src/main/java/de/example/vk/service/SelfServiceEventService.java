package de.example.vk.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.EventRepository;
import de.example.vk.repository.EventWriteRepository;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.util.CurrentUser;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Fachlogik für den Selbsteintrag (anlegen, ändern, einreichen, zurückziehen). */
@Service
public class SelfServiceEventService {

    private final EventWriteRepository writeRepository;
    private final EventRepository eventRepository;
    private final ApprovalRepository approvalRepository;
    private final EventValidator validator;
    private final AuditService auditService;

    public SelfServiceEventService(EventWriteRepository writeRepository, EventRepository eventRepository,
                                   ApprovalRepository approvalRepository,
                                   EventValidator validator, AuditService auditService) {
        this.writeRepository = writeRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.validator = validator;
        this.auditService = auditService;
    }

    public JsonArray listOwn() {
        return writeRepository.listOwn(CurrentUser.requireUserId());
    }

    /** Eigenes Event zum Bearbeiten (prüft Besitz). */
    public JsonObject getForEdit(String publicId) {
        long userId = CurrentUser.requireUserId();
        String[] status = new String[1];
        if (writeRepository.findOwnIdAndStatus(publicId, userId, status) == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        return eventRepository.findByPublicId(publicId, false);
    }

    @Transactional
    public String create(JsonObject body) {
        long userId = CurrentUser.requireUserId();
        EventInput in = parse(body);
        validator.validate(in);
        String publicId = writeRepository.createDraft(in, userId);
        auditService.log(userId, "CREATE_EVENT", "EVENT", null, publicId);
        return publicId;
    }

    @Transactional
    public void update(String publicId, JsonObject body) {
        long userId = CurrentUser.requireUserId();
        String[] status = new String[1];
        long[] idRow = writeRepository.findOwnIdAndStatus(publicId, userId, status);
        if (idRow == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        if (!"DRAFT".equals(status[0]) && !"CHANGES_REQUESTED".equals(status[0])) {
            throw new ValidationException("status", "Nur Entwürfe oder zurückgegebene Events sind editierbar.");
        }
        EventInput in = parse(body);
        validator.validate(in);
        writeRepository.updateDraft(idRow[0], in, userId);
        auditService.log(userId, "UPDATE_EVENT", "EVENT", idRow[0], publicId);
    }

    @Transactional
    public void submit(String publicId) {
        long userId = CurrentUser.requireUserId();
        String[] status = new String[1];
        long[] idRow = writeRepository.findOwnIdAndStatus(publicId, userId, status);
        if (idRow == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        if (!"DRAFT".equals(status[0]) && !"CHANGES_REQUESTED".equals(status[0])) {
            throw new ValidationException("status", "Nur Entwürfe können eingereicht werden.");
        }
        writeRepository.setWorkflowStatus(idRow[0], "SUBMITTED");
        approvalRepository.insertSubmission(idRow[0], userId);
        auditService.log(userId, "SUBMIT_EVENT", "EVENT", idRow[0], publicId);
    }

    @Transactional
    public void withdraw(String publicId) {
        long userId = CurrentUser.requireUserId();
        String[] status = new String[1];
        long[] idRow = writeRepository.findOwnIdAndStatus(publicId, userId, status);
        if (idRow == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        if (!"SUBMITTED".equals(status[0])) {
            throw new ValidationException("status", "Nur eingereichte Events können zurückgezogen werden.");
        }
        writeRepository.setWorkflowStatus(idRow[0], "DRAFT");
        auditService.log(userId, "WITHDRAW_EVENT", "EVENT", idRow[0], publicId);
    }

    // ------------------------------------------------------------------

    private EventInput parse(JsonObject body) {
        EventInput in = new EventInput();
        String type = Json.optString(body, "schemaType");
        in.schemaType = (type == null || type.isEmpty()) ? "Event" : HtmlSanitizer.stripAll(type);
        in.title = HtmlSanitizer.stripAll(Json.optString(body, "title"));
        in.shortDescription = HtmlSanitizer.stripAll(Json.optString(body, "shortDescription"));
        in.descriptionHtml = HtmlSanitizer.sanitize(Json.optString(body, "description"));
        in.startAt = parseTs(Json.optString(body, "startAt"));
        in.endAt = parseTs(Json.optString(body, "endAt"));
        String mode = Json.optString(body, "attendanceMode");
        in.attendanceMode = mode == null ? "OFFLINE" : mode.trim().toUpperCase();
        in.accessibleForFree = Json.optBool(body, "isAccessibleForFree");
        in.categorySlug = Json.optString(body, "category");
        in.placePublicId = Json.optString(body, "place");
        in.virtualUrl = Json.optString(body, "virtualUrl");
        in.organizerName = Json.optString(body, "organizerName");
        in.organizerEmail = Json.optString(body, "organizerEmail");
        in.keywords = parseKeywords(body.get("keywords"));
        return in;
    }

    private List<String> parseKeywords(JsonElement el) {
        List<String> out = new ArrayList<String>();
        if (el == null || el.isJsonNull()) {
            return out;
        }
        if (el.isJsonArray()) {
            for (JsonElement k : el.getAsJsonArray()) {
                if (!k.isJsonNull()) {
                    out.add(HtmlSanitizer.stripAll(k.getAsString()));
                }
            }
        } else if (el.isJsonPrimitive()) {
            for (String k : el.getAsString().split(",")) {
                if (!k.trim().isEmpty()) {
                    out.add(HtmlSanitizer.stripAll(k.trim()));
                }
            }
        }
        return out;
    }

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]");

    private Timestamp parseTs(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        String v = s.trim();
        try {
            return Timestamp.valueOf(OffsetDateTime.parse(v).toLocalDateTime());
        } catch (Exception ignore) {
            // kein Offset -> als lokale Zeit interpretieren
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(v, LOCAL));
        } catch (Exception ignore) {
            // nur Datum
        }
        try {
            return Timestamp.valueOf(java.time.LocalDate.parse(v).atStartOfDay());
        } catch (Exception e) {
            throw new ValidationException("startAt", "Ungültiges Datumsformat: " + v);
        }
    }
}
