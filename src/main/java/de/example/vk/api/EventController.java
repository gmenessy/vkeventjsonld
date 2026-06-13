package de.example.vk.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.repository.EventRepository;
import de.example.vk.service.EventQueryService;
import de.example.vk.service.JsonLdService;
import de.example.vk.util.Json;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;

/** Oeffentliche Event-API (Spezifikation 15.1). Antworten direkt als JSON. */
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventQueryService eventQueryService;
    private final JsonLdService jsonLdService;

    public EventController(EventQueryService eventQueryService, JsonLdService jsonLdService) {
        this.eventQueryService = eventQueryService;
        this.jsonLdService = jsonLdService;
    }

    @GetMapping
    public JsonObject search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String place,
            @RequestParam(required = false) String organizer,
            @RequestParam(required = false) String attendanceMode,
            @RequestParam(required = false) Boolean free,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "date") String sort) {

        EventRepository.Query query = new EventRepository.Query();
        query.from = from;
        query.to = to;
        query.q = q;
        query.category = category;
        query.place = place;
        query.organizer = organizer;
        query.attendanceMode = normalizeMode(attendanceMode);
        query.free = free;
        query.page = page;
        query.size = size;
        query.sort = sort;

        eventQueryService.normalize(query);
        long total = eventQueryService.count(query);
        JsonArray items = total == 0 ? new JsonArray() : eventQueryService.search(query);
        return Json.ok(items, query.page, query.size, total);
    }

    @GetMapping("/{publicId}")
    public JsonObject get(@PathVariable String publicId) {
        return Json.ok(eventQueryService.getPublicEvent(publicId));
    }

    @GetMapping(value = "/{publicId}/jsonld", produces = "application/ld+json;charset=UTF-8")
    public JsonObject jsonLd(@PathVariable String publicId) {
        // Als JsonObject zurueckgeben, damit der Gson-Konverter es als echtes JSON
        // schreibt (ein String wuerde wegen application/*+json doppelt kodiert).
        return jsonLdService.buildJsonLd(eventQueryService.getPublicEvent(publicId));
    }

    private static String normalizeMode(String mode) {
        if (mode == null) {
            return null;
        }
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if ("OFFLINE".equals(upper) || "ONLINE".equals(upper) || "MIXED".equals(upper)) {
            return upper;
        }
        return null;
    }
}
