package de.example.vk.api;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.example.vk.service.SelfServiceEventService;
import de.example.vk.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Selbsteintrag für angemeldete Nutzer (Spezifikation 15.3). Geschützt durch AuthFilter. */
@RestController
@RequestMapping("/me/events")
public class SelfServiceEventController {

    private final SelfServiceEventService service;

    public SelfServiceEventController(SelfServiceEventService service) {
        this.service = service;
    }

    @GetMapping
    public JsonObject myEvents() {
        return Json.ok(service.listOwn());
    }

    @GetMapping("/{publicId}")
    public JsonObject getOne(@PathVariable String publicId) {
        return Json.ok(service.getForEdit(publicId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JsonObject create(@RequestBody JsonObject body) {
        String publicId = service.create(body);
        JsonObject data = new JsonObject();
        Json.str(data, "id", publicId);
        return Json.ok(data);
    }

    @PutMapping("/{publicId}")
    public JsonObject update(@PathVariable String publicId, @RequestBody JsonObject body) {
        service.update(publicId, body);
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/submit")
    public JsonObject submit(@PathVariable String publicId) {
        service.submit(publicId);
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/withdraw")
    public JsonObject withdraw(@PathVariable String publicId) {
        service.withdraw(publicId);
        return Json.ok(JsonNull.INSTANCE);
    }
}
