package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.service.ImportService;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import im Redaktionsbereich (Spezifikation 15.5/27). Geschützt durch AuthFilter
 * (Rolle EDITOR/ADMIN) inkl. CSRF. Body: {@code {format, content, fileName, asStatus}}.
 * Verarbeitung erfolgt synchron; die Antwort enthält Job + Items + Zusammenfassung.
 */
@RestController
@RequestMapping("/admin/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping
    public JsonObject list() {
        return Json.ok(importService.listJobs());
    }

    @GetMapping("/{publicId}")
    public JsonObject job(@PathVariable String publicId) {
        return Json.ok(importService.getJob(publicId));
    }

    @PostMapping
    public JsonObject create(@RequestBody JsonObject body) {
        JsonObject result = importService.importData(
                Json.optString(body, "format"),
                Json.optString(body, "content"),
                Json.optString(body, "fileName"),
                Json.optString(body, "asStatus"));
        return Json.ok(result);
    }
}
