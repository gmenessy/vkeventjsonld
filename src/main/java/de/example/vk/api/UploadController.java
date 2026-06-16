package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.service.upload.ZMUploadService;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * Datei-Upload für Bilder und Dokumente im Editor (Selbsteintrag/Redaktion).
 * Liegt unter {@code /api/me/*} (AuthFilter + CSRF). Die eigentliche Speicherung
 * übernimmt {@link ZMUploadService}; zurück kommt die absolute URL, die der Client
 * anschließend als Bild-/Dokument-Asset am Event mitschickt.
 */
@RestController
@RequestMapping("/me/uploads")
public class UploadController {

    private final ZMUploadService uploadService;

    public UploadController(ZMUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping
    public JsonObject upload(HttpServletRequest request) {
        String url = uploadService.uploadFile(request);
        JsonObject data = new JsonObject();
        Json.str(data, "url", url);
        return Json.ok(data);
    }
}
