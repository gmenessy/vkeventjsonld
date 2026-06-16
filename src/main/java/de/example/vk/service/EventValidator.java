package de.example.vk.service;

import de.example.vk.repository.EventWriteRepository.AssetRef;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.repository.EventWriteRepository.Offer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Serverseitige Validierung von Event-Eingaben (Spezifikation 28.1). */
@Component
public class EventValidator {

    /** ISO-8601-Dauer, z. B. PT2H30M, P1DT2H, PT45M (mind. eine Komponente). */
    private static final Pattern DURATION = Pattern.compile(
            "^P(?!$)(\\d+W)?(\\d+D)?(T(?!$)(\\d+H)?(\\d+M)?(\\d+S)?)?$");
    private static final Pattern HTTP_URL = Pattern.compile("(?i)^https?://.+");

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
        validateDoorTimeAndDuration(in);
        validateOffers(in);
        validateAssets(in);
    }

    private void validateDoorTimeAndDuration(EventInput in) {
        if (in.doorTime != null && in.startAt != null && in.doorTime.after(in.startAt)) {
            throw new ValidationException("doorTime", "Der Einlass darf nicht nach dem Beginn liegen.");
        }
        if (in.durationIso != null && !in.durationIso.trim().isEmpty()
                && !DURATION.matcher(in.durationIso.trim()).matches()) {
            throw new ValidationException("durationIso",
                    "Die Dauer muss im ISO-8601-Format vorliegen, z. B. PT2H30M.");
        }
    }

    private void validateOffers(EventInput in) {
        if (in.offers == null) {
            return;
        }
        for (Offer o : in.offers) {
            if (o.price != null && o.price.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("offers", "Ein Preis darf nicht negativ sein.");
            }
            if (o.priceCurrency != null && !o.priceCurrency.trim().isEmpty()
                    && !o.priceCurrency.trim().matches("[A-Za-z]{3}")) {
                throw new ValidationException("offers", "Die Währung muss ein 3-Buchstaben-Code sein (z. B. EUR).");
            }
            if (o.url != null && !o.url.trim().isEmpty() && !HTTP_URL.matcher(o.url.trim()).matches()) {
                throw new ValidationException("offers", "Ein Ticket-Link muss mit http(s):// beginnen.");
            }
        }
    }

    private void validateAssets(EventInput in) {
        if (in.images != null) {
            for (AssetRef img : in.images) {
                if (img.url == null || img.url.trim().isEmpty()) {
                    continue;
                }
                if (!HTTP_URL.matcher(img.url.trim()).matches()) {
                    throw new ValidationException("images", "Bild-URLs müssen mit http(s):// beginnen.");
                }
                // Barrierefreiheit (WCAG 1.1.1): Bild ohne Alternativtext ist nicht zulässig.
                if (img.altText == null || img.altText.trim().isEmpty()) {
                    throw new ValidationException("images",
                            "Jedes Bild benötigt einen Alternativtext (Barrierefreiheit).");
                }
            }
        }
        if (in.documents != null) {
            for (AssetRef doc : in.documents) {
                if (doc.url != null && !doc.url.trim().isEmpty()
                        && !HTTP_URL.matcher(doc.url.trim()).matches()) {
                    throw new ValidationException("documents", "Dokument-URLs müssen mit http(s):// beginnen.");
                }
            }
        }
    }
}
