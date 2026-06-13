package de.example.vk.service;

import com.google.gson.JsonObject;
import de.example.vk.repository.EventRepository;
import org.springframework.stereotype.Service;

/** Oeffentliche Lesezugriffe auf veroeffentlichte Events. */
@Service
public class EventQueryService {

    public static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;

    public EventQueryService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public void normalize(EventRepository.Query query) {
        if (query.page < 1) {
            query.page = 1;
        }
        if (query.size < 1) {
            query.size = 20;
        }
        if (query.size > MAX_PAGE_SIZE) {
            query.size = MAX_PAGE_SIZE;
        }
    }

    public long count(EventRepository.Query query) {
        return eventRepository.count(query);
    }

    public com.google.gson.JsonArray search(EventRepository.Query query) {
        return eventRepository.search(query);
    }

    public JsonObject getPublicEvent(String publicId) {
        JsonObject event = eventRepository.findPublishedByPublicId(publicId);
        if (event == null) {
            throw new NotFoundException("Veranstaltung nicht gefunden: " + publicId);
        }
        return event;
    }
}
