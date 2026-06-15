package de.example.vk.api;

import de.example.vk.service.ExportService;
import de.example.vk.service.ValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Export im Redaktionsbereich (Spezifikation 15.5). Geschützt durch AuthFilter (EDITOR/ADMIN). */
@RestController
@RequestMapping("/admin/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/events")
    public ResponseEntity<String> events(@RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new ValidationException("format", "Nur format=csv wird derzeit unterstützt.");
        }
        String csv = exportService.exportPublishedCsv();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vk-events.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }
}
