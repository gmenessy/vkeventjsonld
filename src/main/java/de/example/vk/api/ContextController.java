package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.repository.VkRegistryRepository;
import de.example.vk.util.Json;
import de.example.vk.util.ConfigVk;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liefert den Mandanten-/VK-Kontext, den dieses System bedient. Jedes System hat
 * seinen eigenen Spring-Kontext und damit genau ein (MANDANT_ID, VK_ID); die SPA
 * zeigt nur den Namen dieses VK an (kein In-App-Umschalter).
 */
@RestController
@RequestMapping("/context")
public class ContextController {

    private final VkRegistryRepository registryRepository;

    public ContextController(VkRegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    @GetMapping
    public JsonObject context() {
        JsonObject data = new JsonObject();
        data.addProperty("mandant", ConfigVk.requireMandant());
        data.addProperty("vk", ConfigVk.requireVkId());
        Json.str(data, "name", registryRepository.currentName());
        return Json.ok(data);
    }
}
