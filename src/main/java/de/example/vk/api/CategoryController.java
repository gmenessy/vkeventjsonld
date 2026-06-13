package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.repository.CategoryRepository;
import de.example.vk.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public JsonObject tree() {
        return Json.ok(categoryRepository.findTree());
    }
}
