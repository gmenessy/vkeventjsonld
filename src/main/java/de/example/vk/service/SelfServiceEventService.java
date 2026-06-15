package de.example.vk.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.EventRepository;
import de.example.vk.repository.EventWriteRepository;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fachlogik für den Selbsteintrag (anlegen, ändern, einreichen, zurückziehen). */
@Service
public class SelfServiceEventService {

    private final EventWriteRepository writeRepository;
    private final EventRepository eventRepository;
    private final ApprovalRepository approvalRepository;
    private final EventValidator validator;
    private final EventInputMapper mapper;
    private final AuditService auditService;

    public SelfServiceEventService(EventWriteRepository writeRepository, EventRepository eventRepository,
                                   ApprovalRepository approvalRepository, EventValidator validator,
                                   EventInputMapper mapper, AuditService auditService) {
        this.writeRepository = writeRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.validator = validator;
        this.mapper = mapper;
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
        EventInput in = mapper.fromJson(body);
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
        EventInput in = mapper.fromJson(body);
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
}
