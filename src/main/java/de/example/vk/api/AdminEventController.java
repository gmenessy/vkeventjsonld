package de.example.vk.api;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import de.example.vk.service.AdminEventService;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Redaktionsbereich (Spezifikation 15.4). Geschützt durch AuthFilter (Rolle EDITOR/ADMIN). */
@RestController
@RequestMapping("/admin/events")
public class AdminEventController {

    private final AdminEventService service;

    public AdminEventController(AdminEventService service) {
        this.service = service;
    }

    @GetMapping
    public JsonObject queue(@RequestParam(required = false, defaultValue = "SUBMITTED") String status) {
        return Json.ok(service.queue(status));
    }

    @GetMapping("/{publicId}")
    public JsonObject review(@PathVariable String publicId) {
        return Json.ok(service.getForReview(publicId));
    }

    @PostMapping("/{publicId}/approve")
    public JsonObject approve(@PathVariable String publicId, @RequestBody(required = false) JsonObject body) {
        service.approve(publicId, Json.optString(body, "note"));
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/request-changes")
    public JsonObject requestChanges(@PathVariable String publicId, @RequestBody(required = false) JsonObject body) {
        service.requestChanges(publicId, Json.optString(body, "note"));
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/reject")
    public JsonObject reject(@PathVariable String publicId, @RequestBody(required = false) JsonObject body) {
        service.reject(publicId, Json.optString(body, "note"));
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/publish")
    public JsonObject publish(@PathVariable String publicId) {
        service.publish(publicId);
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/cancel")
    public JsonObject cancel(@PathVariable String publicId) {
        service.cancel(publicId);
        return Json.ok(JsonNull.INSTANCE);
    }

    @PostMapping("/{publicId}/archive")
    public JsonObject archive(@PathVariable String publicId) {
        service.archive(publicId);
        return Json.ok(JsonNull.INSTANCE);
    }

    @GetMapping("/{publicId}/versions")
    public JsonObject versions(@PathVariable String publicId) {
        return Json.ok(service.versions(publicId));
    }

    @GetMapping("/{publicId}/versions/{versionNo}")
    public JsonObject version(@PathVariable String publicId, @PathVariable int versionNo) {
        return Json.ok(service.versionSnapshot(publicId, versionNo));
    }

    @PostMapping("/{publicId}/versions/{versionNo}/restore")
    public JsonObject restore(@PathVariable String publicId, @PathVariable int versionNo) {
        service.restoreVersion(publicId, versionNo);
        return Json.ok(JsonNull.INSTANCE);
    }
}
