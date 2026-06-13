package de.example.vk.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.repository.PartyRepository;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizers")
public class PartyController {

    private final PartyRepository partyRepository;

    public PartyController(PartyRepository partyRepository) {
        this.partyRepository = partyRepository;
    }

    @GetMapping
    public JsonObject suggest(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 2) {
            return Json.ok(new JsonArray());
        }
        return Json.ok(partyRepository.suggestOrganizers(q, 10));
    }
}
