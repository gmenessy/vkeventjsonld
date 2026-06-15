package de.example.vk.service;

import de.example.vk.repository.EventWriteRepository.EventInput;
import org.springframework.stereotype.Component;

/** Serverseitige Validierung von Event-Eingaben (Spezifikation 28.1). */
@Component
public class EventValidator {

    public void validate(EventInput in) {
        if (in.title == null || in.title.trim().length() < 3 || in.title.length() > 300) {
            throw new ValidationException("title", "Titel ist erforderlich (3–300 Zeichen).");
        }
        if (in.shortDescription != null && in.shortDescription.length() > 1000) {
            throw new ValidationException("shortDescription", "Kurzbeschreibung max. 1000 Zeichen.");
        }
        if (in.startAt == null) {
            throw new ValidationException("startAt", "Startdatum ist erforderlich.");
        }
        if (in.endAt != null && !in.endAt.after(in.startAt)) {
            throw new ValidationException("endAt", "Das Ende muss nach dem Start liegen.");
        }
        String mode = in.attendanceMode;
        if (!"OFFLINE".equals(mode) && !"ONLINE".equals(mode) && !"MIXED".equals(mode)) {
            throw new ValidationException("attendanceMode", "Ungültiger Anwesenheitsmodus.");
        }
        boolean physical = "OFFLINE".equals(mode) || "MIXED".equals(mode);
        if (physical && (in.placePublicId == null || in.placePublicId.trim().isEmpty())) {
            throw new ValidationException("place", "Bei Veranstaltungen vor Ort ist ein Ort erforderlich.");
        }
        boolean online = "ONLINE".equals(mode) || "MIXED".equals(mode);
        if (online && (in.virtualUrl == null || in.virtualUrl.trim().isEmpty())) {
            throw new ValidationException("virtualUrl", "Bei Online-Veranstaltungen ist ein Link erforderlich.");
        }
        if (in.virtualUrl != null && !in.virtualUrl.trim().isEmpty()
                && !in.virtualUrl.matches("(?i)^https?://.+")) {
            throw new ValidationException("virtualUrl", "Der Online-Link muss mit http(s):// beginnen.");
        }
        if (in.organizerName == null || in.organizerName.trim().isEmpty()) {
            throw new ValidationException("organizer", "Mindestens ein Veranstalter ist erforderlich.");
        }
        if (in.categorySlug == null || in.categorySlug.trim().isEmpty()) {
            throw new ValidationException("category", "Mindestens eine Kategorie ist erforderlich.");
        }
    }
}
