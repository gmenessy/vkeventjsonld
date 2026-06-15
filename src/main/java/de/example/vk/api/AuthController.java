package de.example.vk.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.repository.UserRepository;
import de.example.vk.service.AuthService;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;

/** Anmeldung/Registrierung über Server-Session + CSRF (Spezifikation 15.2/26.1). */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public JsonObject login(@RequestBody JsonObject body, HttpServletRequest request) {
        UserRepository.UserRow user = authService.authenticate(
                Json.optString(body, "email"), Json.optString(body, "password"));
        if (user == null) {
            return Json.error("AUTH_INVALID", null, "E-Mail oder Passwort ist falsch.");
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate(); // neue Session-ID nach Login -> Session-Fixation vermeiden
        }
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", user.id);
        session.setAttribute("mandantId", user.mandantId);
        session.setAttribute("displayName", user.displayName);
        session.setAttribute("roles", String.join(",", user.roles));
        String csrf = newCsrf();
        session.setAttribute("csrf", csrf);
        return Json.ok(userJson(user.id, user.displayName, user.mandantId, String.join(",", user.roles), csrf));
    }

    @PostMapping("/register")
    public JsonObject register(@RequestBody JsonObject body, HttpServletRequest request) {
        long userId = authService.register(
                Json.optString(body, "email"),
                Json.optString(body, "displayName"),
                Json.optString(body, "password"));
        // direkt anmelden
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", userId);
        session.setAttribute("mandantId", ConfigVk.requireMandant());
        session.setAttribute("displayName", Json.optString(body, "displayName"));
        session.setAttribute("roles", "REGISTERED");
        String csrf = newCsrf();
        session.setAttribute("csrf", csrf);
        return Json.ok(userJson(userId, Json.optString(body, "displayName"),
                ConfigVk.requireMandant(), "REGISTERED", csrf));
    }

    @PostMapping("/logout")
    public JsonObject logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Json.ok(com.google.gson.JsonNull.INSTANCE);
    }

    @GetMapping("/me")
    public JsonObject me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return Json.ok(com.google.gson.JsonNull.INSTANCE); // nicht angemeldet
        }
        return Json.ok(userJson(
                (Long) session.getAttribute("userId"),
                (String) session.getAttribute("displayName"),
                (Long) session.getAttribute("mandantId"),
                (String) session.getAttribute("roles"),
                (String) session.getAttribute("csrf")));
    }

    private static JsonObject userJson(long userId, String displayName, long mandantId,
                                       String roles, String csrf) {
        JsonObject o = new JsonObject();
        o.addProperty("userId", userId);
        Json.str(o, "displayName", displayName);
        o.addProperty("mandant", mandantId);
        JsonArray roleArr = new JsonArray();
        if (roles != null && !roles.isEmpty()) {
            for (String r : roles.split(",")) {
                roleArr.add(r);
            }
        }
        o.add("roles", roleArr);
        Json.str(o, "csrfToken", csrf);
        return o;
    }

    private static String newCsrf() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
