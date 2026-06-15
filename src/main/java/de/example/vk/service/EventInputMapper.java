package de.example.vk.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.Json;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Wandelt ein eingehendes JSON-Objekt in ein {@link EventInput} um – mit
 * serverseitigem Sanitizing (Titel/Kurzbeschreibung als Reintext, Beschreibung
 * über die Tag-Allowlist) und toleranter Datumsanalyse. Gemeinsam genutzt von
 * Selbsteintrag und Import, damit beide identisch validieren/säubern.
 */
@Component
public class EventInputMapper {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]");

    public EventInput fromJson(JsonObject body) {
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
            for (String k : el.getAsString().split("[,;]")) {
                if (!k.trim().isEmpty()) {
                    out.add(HtmlSanitizer.stripAll(k.trim()));
                }
            }
        }
        return out;
    }

    public Timestamp parseTs(String s) {
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
            // evtl. nur Datum
        }
        try {
            return Timestamp.valueOf(LocalDate.parse(v).atStartOfDay());
        } catch (Exception e) {
            throw new ValidationException("startAt", "Ungültiges Datumsformat: " + v);
        }
    }
}
