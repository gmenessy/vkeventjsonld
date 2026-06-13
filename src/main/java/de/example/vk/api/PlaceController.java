package de.example.vk.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.repository.PlaceRepository;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/places")
public class PlaceController {

    private final PlaceRepository placeRepository;

    public PlaceController(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    @GetMapping
    public JsonObject suggest(@RequestParam(required = false) String q) {
        if (q == null || q.trim().length() < 2) {
            return Json.ok(new JsonArray());
        }
        return Json.ok(placeRepository.suggest(q, 10));
    }
}
