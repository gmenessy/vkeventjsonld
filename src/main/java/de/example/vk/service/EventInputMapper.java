package de.example.vk.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.example.vk.repository.EventWriteRepository.AssetRef;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.repository.EventWriteRepository.Offer;
import de.example.vk.repository.EventWriteRepository.PartyRef;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.Json;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
        in.doorTime = parseTs(Json.optString(body, "doorTime"));
        in.durationIso = stripToNull(Json.optString(body, "durationIso"));
        String mode = Json.optString(body, "attendanceMode");
        in.attendanceMode = mode == null ? "OFFLINE" : mode.trim().toUpperCase();
        in.accessibleForFree = Json.optBool(body, "isAccessibleForFree");
        in.categorySlug = Json.optString(body, "category");
        in.placePublicId = Json.optString(body, "place");
        in.virtualUrl = Json.optString(body, "virtualUrl");
        in.organizerName = Json.optString(body, "organizerName");
        in.organizerEmail = Json.optString(body, "organizerEmail");
        in.keywords = parseKeywords(body.get("keywords"));
        in.offers = parseOffers(body.get("offers"), Json.optString(body, "price"));
        in.performers = parseParties(body.get("performers"), Json.optString(body, "performerNames"));
        in.sponsors = parseParties(body.get("sponsors"), Json.optString(body, "sponsorNames"));
        in.images = parseImages(body);
        in.documents = parseDocuments(body);
        return in;
    }

    /** Offers als JSON-Array von Objekten oder – im CSV-Flachformat – ein einzelner Preis. */
    private List<Offer> parseOffers(JsonElement el, String csvPrice) {
        List<Offer> out = new ArrayList<Offer>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                Offer offer = new Offer();
                offer.name = HtmlSanitizer.stripAll(Json.optString(o, "name"));
                offer.price = parsePrice(Json.optString(o, "price"));
                String cur = Json.optString(o, "priceCurrency");
                offer.priceCurrency = cur == null || cur.trim().isEmpty() ? "EUR" : cur.trim();
                offer.url = stripToNull(Json.optString(o, "url"));
                offer.availability = HtmlSanitizer.stripAll(Json.optString(o, "availability"));
                out.add(offer);
            }
        } else if (csvPrice != null && !csvPrice.trim().isEmpty()) {
            Offer offer = new Offer();
            offer.price = parsePrice(csvPrice);
            out.add(offer);
        }
        return out;
    }

    /** Parteien als JSON-Array (Strings oder Objekte) oder – im CSV-Flachformat – „A;B;C". */
    private List<PartyRef> parseParties(JsonElement el, String csvNames) {
        List<PartyRef> out = new ArrayList<PartyRef>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                PartyRef ref = new PartyRef();
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    String name = Json.optString(o, "displayName");
                    if (name == null) {
                        name = Json.optString(o, "name");
                    }
                    ref.displayName = HtmlSanitizer.stripAll(name);
                    ref.email = stripToNull(Json.optString(o, "email"));
                    ref.url = stripToNull(Json.optString(o, "url"));
                } else if (e.isJsonPrimitive()) {
                    ref.displayName = HtmlSanitizer.stripAll(e.getAsString());
                }
                if (ref.displayName != null && !ref.displayName.trim().isEmpty()) {
                    out.add(ref);
                }
            }
        } else if (csvNames != null && !csvNames.trim().isEmpty()) {
            for (String name : csvNames.split("[;|]")) {
                if (!name.trim().isEmpty()) {
                    PartyRef ref = new PartyRef();
                    ref.displayName = HtmlSanitizer.stripAll(name.trim());
                    out.add(ref);
                }
            }
        }
        return out;
    }

    /** Bilder als JSON-Array von {url,altText,copyrightText} oder CSV imageUrl/imageAlt. */
    private List<AssetRef> parseImages(JsonObject body) {
        List<AssetRef> out = new ArrayList<AssetRef>();
        JsonElement el = body.get("images");
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                AssetRef a = new AssetRef();
                a.url = stripToNull(Json.optString(o, "url"));
                a.fileName = stripToNull(Json.optString(o, "fileName"));
                a.mimeType = stripToNull(Json.optString(o, "mimeType"));
                a.altText = HtmlSanitizer.stripAll(Json.optString(o, "altText"));
                a.copyrightText = HtmlSanitizer.stripAll(Json.optString(o, "copyrightText"));
                if (a.url != null) {
                    out.add(a);
                }
            }
        } else {
            String url = stripToNull(Json.optString(body, "imageUrl"));
            if (url != null) {
                AssetRef a = new AssetRef();
                a.url = url;
                a.altText = HtmlSanitizer.stripAll(Json.optString(body, "imageAlt"));
                a.copyrightText = HtmlSanitizer.stripAll(Json.optString(body, "imageCopyright"));
                out.add(a);
            }
        }
        return out;
    }

    /** Dokumente als JSON-Array von {url,fileName} oder CSV documentUrl/documentName. */
    private List<AssetRef> parseDocuments(JsonObject body) {
        List<AssetRef> out = new ArrayList<AssetRef>();
        JsonElement el = body.get("documents");
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                AssetRef a = new AssetRef();
                a.url = stripToNull(Json.optString(o, "url"));
                a.fileName = HtmlSanitizer.stripAll(Json.optString(o, "fileName"));
                a.mimeType = stripToNull(Json.optString(o, "mimeType"));
                if (a.url != null) {
                    out.add(a);
                }
            }
        } else {
            String url = stripToNull(Json.optString(body, "documentUrl"));
            if (url != null) {
                AssetRef a = new AssetRef();
                a.url = url;
                a.fileName = HtmlSanitizer.stripAll(Json.optString(body, "documentName"));
                out.add(a);
            }
        }
        return out;
    }

    private BigDecimal parsePrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String v = raw.trim().replace("€", "").replace("EUR", "").trim().replace(',', '.');
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new ValidationException("price", "Ungültiger Preis: " + raw);
        }
    }

    private static String stripToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
