package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.repository.CategoryRepository;
import de.example.vk.service.NlQueryParser;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wandelt eine natürlichsprachige Suchanfrage in strukturierte Filter um
 * (GenAI-Quick-Win, regelbasiert mit hartem Fallback – siehe {@link NlQueryParser}).
 * Die SPA ruft das optional auf; bei Fehlern sucht sie unverändert per Keyword weiter.
 */
@RestController
@RequestMapping("/search")
public class SearchParseController {

    private final NlQueryParser parser;
    private final CategoryRepository categoryRepository;

    public SearchParseController(NlQueryParser parser, CategoryRepository categoryRepository) {
        this.parser = parser;
        this.categoryRepository = categoryRepository;
    }

    @GetMapping("/parse")
    public JsonObject parse(@RequestParam(required = false) String q) {
        JsonObject filters = parser.parse(q, categoryRepository.activeSlugs());
        return Json.ok(filters);
    }
}
