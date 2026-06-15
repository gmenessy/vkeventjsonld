package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.repository.CategoryRepository;
import de.example.vk.service.EditorSuggestService;
import de.example.vk.service.genai.GenAiProvider;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redaktionelle GenAI-Assistenz zur Schreibzeit (Spezifikation #10):
 * Alt-Text-Vorschlag und „Einfache Sprache". Liegt unter {@code /api/me/*} und ist
 * damit angemeldeten Nutzern vorbehalten (AuthFilter + CSRF). Die Aufrufe liegen
 * außerhalb des heißen Such-Pfads; der Anbieter ist austauschbar
 * ({@link GenAiProvider}) mit hartem Fallback auf die Heuristik.
 */
@RestController
@RequestMapping("/me/assist")
public class AssistController {

    /** Schutzgrenze gegen übergroße Eingaben an den Anbieter. */
    private static final int MAX_INPUT = 5000;

    private final GenAiProvider genAi;
    private final EditorSuggestService suggestService;
    private final CategoryRepository categoryRepository;

    public AssistController(GenAiProvider genAi, EditorSuggestService suggestService,
                           CategoryRepository categoryRepository) {
        this.genAi = genAi;
        this.suggestService = suggestService;
        this.categoryRepository = categoryRepository;
    }

    @PostMapping("/alt-text")
    public JsonObject altText(@RequestBody JsonObject body) {
        String title = clean(Json.optString(body, "title"));
        String category = clean(Json.optString(body, "categoryName"));
        String place = clean(Json.optString(body, "placeLabel"));
        String shortDescription = clean(Json.optString(body, "shortDescription"));
        if (title == null || title.isEmpty()) {
            return Json.error("VALIDATION", "title", "Für einen Alt-Text-Vorschlag wird mindestens ein Titel benötigt.");
        }
        String suggestion = genAi.altText(title, category, place, shortDescription);
        return suggestion(suggestion);
    }

    @PostMapping("/simplify")
    public JsonObject simplify(@RequestBody JsonObject body) {
        String text = clean(Json.optString(body, "text"));
        if (text == null || text.isEmpty()) {
            return Json.error("VALIDATION", "text", "Bitte zuerst einen Text eingeben.");
        }
        String suggestion = genAi.simplify(text);
        return suggestion(suggestion);
    }

    @PostMapping("/suggest")
    public JsonObject suggest(@RequestBody JsonObject body) {
        String title = clean(Json.optString(body, "title"));
        String description = clean(Json.optString(body, "description"));
        if ((title == null || title.isEmpty()) && (description == null || description.isEmpty())) {
            return Json.error("VALIDATION", "title", "Für Vorschläge wird Titel oder Beschreibung benötigt.");
        }
        return Json.ok(suggestService.suggest(title, description, categoryRepository.activeSlugs()));
    }

    private JsonObject suggestion(String suggestion) {
        JsonObject data = new JsonObject();
        Json.str(data, "suggestion", suggestion == null ? "" : suggestion);
        Json.str(data, "provider", genAi.name());
        return Json.ok(data);
    }

    /** Entfernt jegliches Markup und begrenzt die Länge, bevor Text an den Anbieter geht. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = HtmlSanitizer.stripAll(raw).trim();
        if (stripped.length() > MAX_INPUT) {
            stripped = stripped.substring(0, MAX_INPUT);
        }
        return stripped;
    }
}
