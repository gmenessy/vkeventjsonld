package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.repository.VkRegistryRepository;
import de.example.vk.util.Json;
import de.example.vk.util.VkConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liefert den aktuellen Mandanten-/VK-Kontext und die waehlbaren VKs des
 * Mandanten. Die SPA laedt das beim Start und zeigt ggf. einen VK-Umschalter.
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
        data.addProperty("mandant", VkConfig.requireMandant());
        data.addProperty("vk", VkConfig.requireVkId());
        data.add("vks", registryRepository.listForCurrentMandant());
        return Json.ok(data);
    }
}
