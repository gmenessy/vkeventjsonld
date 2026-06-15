package de.example.vk.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Redaktioneller Vorschlagsdienst zur Schreibzeit: leitet Kategorie und Schlagworte
 * aus Titel und Beschreibung ab. Bewusst <b>regelbasiert</b> (gleiche Philosophie wie
 * {@link NlQueryParser}) – null Latenz/Kosten, offline-fähig, deterministisch und
 * damit ein verlässlicher GenAI-Quick-Win ohne Hot-Path-Risiko.
 */
@Service
public class EditorSuggestService {

    private static final int MAX_KEYWORDS = 6;
    private static final int MIN_TOKEN_LEN = 4;

    private final NlQueryParser parser;

    public EditorSuggestService(NlQueryParser parser) {
        this.parser = parser;
    }

    /**
     * @return {@code {category, keywords[]}} – Kategorie nur, wenn eindeutig ableitbar.
     */
    public JsonObject suggest(String title, String description, Set<String> knownSlugs) {
        String safeTitle = title == null ? "" : HtmlSanitizer.stripAll(title);
        String safeDesc = description == null ? "" : HtmlSanitizer.stripAll(description);
        String combined = (safeTitle + " " + safeDesc).trim();

        JsonObject out = new JsonObject();

        // Kategorie über denselben Parser wie die natürlichsprachige Suche
        JsonObject parsed = parser.parse(combined, knownSlugs);
        String category = Json.optString(parsed, "category");
        if (category != null) {
            Json.str(out, "category", category);
        }

        JsonArray keywords = new JsonArray();
        for (String kw : extractKeywords(safeTitle, safeDesc)) {
            keywords.add(kw);
        }
        out.add("keywords", keywords);
        return out;
    }

    /** Frequenzbasierte Schlagwort-Extraktion mit Titel-Bonus; gibt großgeschriebene Begriffe zurück. */
    List<String> extractKeywords(String title, String description) {
        Map<String, Integer> score = new LinkedHashMap<String, Integer>();
        Set<String> titleTokens = new HashSet<String>(tokenize(title));
        for (String t : titleTokens) {
            bump(score, t, 3); // Titelwörter wiegen schwerer
        }
        for (String t : tokenize(description)) {
            bump(score, t, 1);
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(score.entrySet());
        // Stabil: nach Score absteigend, bei Gleichstand Einfügereihenfolge (LinkedHashMap)
        entries.sort(new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });

        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : entries) {
            out.add(capitalize(e.getKey()));
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return out;
    }

    private static void bump(Map<String, Integer> score, String token, int by) {
        Integer cur = score.get(token);
        score.put(token, (cur == null ? 0 : cur) + by);
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String lower = text.toLowerCase(Locale.GERMAN);
        for (String raw : lower.split("[^\\p{L}\\p{Nd}]+")) {
            if (raw.length() < MIN_TOKEN_LEN || STOPWORDS.contains(raw) || isNumeric(raw)) {
                continue;
            }
            out.add(raw);
        }
        return out;
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Erweiterte deutsche Stoppwortliste (Füllwörter ohne Schlagwortwert). */
    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            "aber", "alle", "allen", "aller", "alles", "also", "andere", "auch", "auf", "aus",
            "bei", "beim", "bereits", "bietet", "bist", "dabei", "damit", "dann", "dass",
            "dein", "denn", "dessen", "dich", "diese", "diesem", "diesen", "dieser", "dieses",
            "durch", "eine", "einem", "einen", "einer", "eines", "etwa", "euch", "euer", "fuer",
            "für", "ganz", "gegen", "gibt", "habe", "haben", "halt", "hier", "ihre", "ihren",
            "ihrer", "immer", "jede", "jeden", "kann", "kein", "keine", "lassen", "machen", "mehr",
            "mein", "mich", "muss", "nach", "nein", "nicht", "noch", "oder", "ohne", "schon",
            "sehr", "sein", "seine", "sich", "sind", "sodass", "sollen", "somit", "sonst", "sowie",
            "statt", "ueber", "über", "unser", "unter", "viel", "vom", "von", "vor", "wann", "warum",
            "was", "weil", "wenn", "werden", "wieder", "will", "wird", "wirst", "zum", "zur",
            "zwei", "zwischen", "veranstaltung", "veranstaltungen", "event", "events", "termin"));
}
