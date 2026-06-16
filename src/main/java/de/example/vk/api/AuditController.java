package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.service.AdminEventService;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit-Trail der Event-Aktionen im Redaktionsbereich (Spezifikation 26.4).
 * Geschützt durch AuthFilter (Rolle EDITOR/ADMIN), tenant-gescoped.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AdminEventService service;

    public AuditController(AdminEventService service) {
        this.service = service;
    }

    @GetMapping
    public JsonObject trail(@RequestParam(required = false, defaultValue = "100") int limit) {
        return Json.ok(service.auditTrail(limit));
    }
}
