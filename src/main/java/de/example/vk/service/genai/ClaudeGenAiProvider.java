package de.example.vk.service.genai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * GenAI-Anbieter auf Basis der Anthropic Messages API (Claude). Wird nur erzeugt,
 * wenn ein API-Schlüssel konfiguriert ist; Aufrufe geschehen ausschließlich zur
 * Schreibzeit im Editor (nicht im Such-Hot-Path). Fehler/Timeouts werden vom
 * vorgelagerten {@link FallbackGenAiProvider} aufgefangen.
 *
 * <p>Implementiert bewusst ohne zusätzliche Abhängigkeit über {@link HttpURLConnection}
 * (Java 8). Antworten werden mit Gson geparst.</p>
 */
public class ClaudeGenAiProvider implements GenAiProvider {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int ALT_MAX = 125;

    private final String apiKey;
    private final String model;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ClaudeGenAiProvider(String apiKey, String model, int connectTimeoutMs, int readTimeoutMs) {
        this.apiKey = apiKey;
        this.model = model;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String altText(String title, String categoryName, String placeLabel, String shortDescription) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("Titel: ").append(nz(title)).append('\n');
        if (notEmpty(categoryName)) ctx.append("Kategorie: ").append(categoryName).append('\n');
        if (notEmpty(placeLabel)) ctx.append("Ort: ").append(placeLabel).append('\n');
        if (notEmpty(shortDescription)) ctx.append("Kurzbeschreibung: ").append(shortDescription).append('\n');

        String system = "Du formulierst barrierefreie Bild-Alternativtexte (WCAG 1.1.1) auf Deutsch. "
                + "Beschreibe knapp und sachlich, was auf einem typischen Veranstaltungsbild zu dieser "
                + "Veranstaltung zu sehen wäre. Maximal " + ALT_MAX + " Zeichen, ein Satz, keine Wendungen "
                + "wie \"Bild von\". Gib ausschließlich den Alternativtext zurück, ohne Anführungszeichen.";
        String user = "Veranstaltungsdaten:\n" + ctx;
        String out = call(system, user, 200);
        return truncate(firstLine(out), ALT_MAX);
    }

    @Override
    public String simplify(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String system = "Du schreibst deutschen Text in Einfache Sprache um. Regeln: kurze, klare Sätze; "
                + "ein Satz pro Zeile; gängige Wörter; keine Schachtelsätze; keine Abkürzungen; Inhalt und "
                + "Fakten bleiben unverändert. Gib ausschließlich den umgeschriebenen Text zurück.";
        String out = call(system, text.trim(), 1024);
        return out.trim();
    }

    @Override
    public String name() {
        return "claude";
    }

    // ------------------------------------------------------------------

    private String call(String system, String userText, int maxTokens) {
        JsonObject req = new JsonObject();
        req.addProperty("model", model);
        req.addProperty("max_tokens", maxTokens);
        req.addProperty("system", system);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", userText);
        messages.add(msg);
        req.add("messages", messages);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                throw new GenAiException("Anthropic-API antwortete mit HTTP " + status + ": "
                        + readStream(conn.getErrorStream()));
            }
            String responseBody = readStream(conn.getInputStream());
            return extractText(responseBody);
        } catch (IOException e) {
            throw new GenAiException("Anthropic-API nicht erreichbar: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Liest {@code content[0].text} aus der Messages-Antwort. */
    private static String extractText(String responseBody) {
        JsonElement parsed = JsonParser.parseString(responseBody);
        if (!parsed.isJsonObject()) {
            throw new GenAiException("Unerwartete Antwort der Anthropic-API.");
        }
        JsonObject obj = parsed.getAsJsonObject();
        JsonArray content = obj.getAsJsonArray("content");
        if (content == null || content.size() == 0) {
            throw new GenAiException("Leere Antwort der Anthropic-API.");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement block : content) {
            JsonObject b = block.getAsJsonObject();
            if (b.has("text") && "text".equals(optType(b))) {
                sb.append(b.get("text").getAsString());
            }
        }
        if (sb.length() == 0) {
            throw new GenAiException("Antwort der Anthropic-API enthielt keinen Text.");
        }
        return sb.toString();
    }

    private static String optType(JsonObject b) {
        return b.has("type") && !b.get("type").isJsonNull() ? b.get("type").getAsString() : "text";
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return sc.hasNext() ? sc.next() : "";
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
