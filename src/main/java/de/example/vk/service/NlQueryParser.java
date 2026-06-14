package de.example.vk.service;

import com.google.gson.JsonObject;
import de.example.vk.util.Json;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Übersetzt eine natürlichsprachige Suchanfrage in strukturierte Filter
 * (z. B. „kostenlose kinderkonzerte am wochenende" →
 * {@code {free:true, category:kinder-familie, from/to:Wochenende, q:konzerte}}).
 *
 * <p><b>GenAI-Quick-Win, bewusst leichtgewichtig:</b> Diese Implementierung ist
 * rein regelbasiert (deutsch) – ohne Modell-Aufruf, also <em>null Latenz und null
 * Kosten</em> im heißen Pfad. Sie dient zugleich als <em>harter Fallback</em>.
 * Ein LLM-gestützter Parser (z. B. Claude Haiku für Tempo/Kosten) kann später als
 * alternative Implementierung dieselbe {@link #parse} liefern; die Anwendung bleibt
 * funktionsfähig, falls das Modell ausfällt oder ein Timeout läuft.</p>
 */
@Component
public class NlQueryParser {

    private static final Map<String, String> CATEGORY_SYNONYMS = new HashMap<String, String>();
    static {
        put("musik", "musik", "konzert", "konzerte", "jazz", "chor", "orgel");
        put("theater", "theater", "auffuehrung", "auffuehrungen", "buehne", "schauspiel");
        put("ausstellung", "ausstellung", "ausstellungen", "vernissage", "museum");
        put("sport", "sport", "turnier");
        put("laufveranstaltung", "lauf", "marathon", "stadtlauf");
        put("fussball", "fussball");
        put("radsport", "rad", "radsport", "radtour", "fahrrad");
        put("bildung", "bildung");
        put("vortrag", "vortrag", "lesung");
        put("kurs", "kurs", "kurse");
        put("workshop", "workshop", "workshops");
        put("kinder-familie", "kinder", "kind", "familie", "familien", "kindertheater");
    }

    private static void put(String slug, String... words) {
        for (String w : words) {
            CATEGORY_SYNONYMS.put(w, slug);
        }
    }

    private static final Set<String> FREE_WORDS = new HashSet<String>(Arrays.asList(
            "kostenlos", "kostenlose", "kostenloser", "gratis", "umsonst", "frei", "freier"));

    /**
     * @param text             freie Nutzereingabe
     * @param knownCategorySlugs gültige Kategorie-Slugs des aktuellen VK (zur Validierung)
     */
    public JsonObject parse(String text, Set<String> knownCategorySlugs) {
        JsonObject out = new JsonObject();
        if (text == null || text.trim().isEmpty()) {
            Json.str(out, "q", "");
            return out;
        }
        String lower = text.toLowerCase(Locale.GERMAN);
        Set<String> consumed = new HashSet<String>();

        // Datum
        LocalDate today = LocalDate.now();
        LocalDate from = null;
        LocalDate to = null;
        if (containsPhrase(lower, "uebermorgen") || containsPhrase(lower, "übermorgen")) {
            from = to = today.plusDays(2); consume(consumed, "uebermorgen", "übermorgen");
        } else if (containsPhrase(lower, "morgen")) {
            from = to = today.plusDays(1); consume(consumed, "morgen");
        } else if (containsPhrase(lower, "heute")) {
            from = to = today; consume(consumed, "heute");
        } else if (containsPhrase(lower, "wochenende")) {
            int daysToSat = (6 - today.getDayOfWeek().getValue() % 7 + 7) % 7;
            from = today.plusDays(daysToSat); to = from.plusDays(1);
            consume(consumed, "wochenende", "am", "dieses");
        } else if (containsPhrase(lower, "naechste woche") || containsPhrase(lower, "nächste woche")) {
            int daysToNextMon = (8 - today.getDayOfWeek().getValue());
            from = today.plusDays(daysToNextMon); to = from.plusDays(6);
            consume(consumed, "naechste", "nächste", "woche");
        } else if (containsPhrase(lower, "diese woche")) {
            from = today; to = today.plusDays(7 - today.getDayOfWeek().getValue());
            consume(consumed, "diese", "woche");
        } else if (containsPhrase(lower, "monat")) {
            from = today; to = today.withDayOfMonth(today.lengthOfMonth());
            consume(consumed, "monat", "diesen", "im");
        }
        if (from != null) {
            Json.str(out, "from", from.toString());
            Json.str(out, "to", to.toString());
        }

        // Tokens prüfen: kostenlos, Modus, Kategorie
        boolean free = false;
        String attendanceMode = null;
        String category = null;
        StringBuilder residual = new StringBuilder();

        for (String tokenRaw : lower.split("[^\\p{L}\\p{Nd}]+")) {
            if (tokenRaw.isEmpty() || consumed.contains(tokenRaw)) {
                continue;
            }
            if (FREE_WORDS.contains(tokenRaw)) { free = true; continue; }
            if (tokenRaw.equals("online")) { attendanceMode = "ONLINE"; continue; }
            if (tokenRaw.equals("hybrid")) { attendanceMode = "MIXED"; continue; }
            if (tokenRaw.equals("praesenz") || tokenRaw.equals("präsenz")) { attendanceMode = "OFFLINE"; continue; }
            if (category == null) {
                String slug = matchCategory(tokenRaw, knownCategorySlugs);
                if (slug != null) { category = slug; continue; }
            }
            // Füllwörter ohne Suchwert weglassen
            if (isStopword(tokenRaw)) { continue; }
            if (residual.length() > 0) residual.append(' ');
            residual.append(tokenRaw);
        }

        if (free) out.addProperty("free", true);
        if (attendanceMode != null) Json.str(out, "attendanceMode", attendanceMode);
        if (category != null) Json.str(out, "category", category);
        Json.str(out, "q", residual.toString());
        return out;
    }

    // Teilwort-Treffer für deutsche Komposita (Reihenfolge = Priorität),
    // z. B. „sommerkonzert"/„kinderkonzert" -> musik, „kindertheater" -> theater.
    private static final String[][] COMPOUND = {
            {"konzert", "musik"}, {"theater", "theater"}, {"ausstellung", "ausstellung"},
            {"vortrag", "vortrag"}, {"workshop", "workshop"}, {"fussball", "fussball"},
            {"radsport", "radsport"}, {"familie", "kinder-familie"}, {"kinder", "kinder-familie"}
    };

    private String matchCategory(String token, Set<String> known) {
        String slug = CATEGORY_SYNONYMS.get(token);
        if (slug == null && known != null && known.contains(token)) {
            slug = token; // exakter Slug-Treffer
        }
        if (slug == null && token.length() >= 6) {
            for (String[] pair : COMPOUND) {
                if (token.contains(pair[0])) { slug = pair[1]; break; }
            }
        }
        if (slug != null && (known == null || known.contains(slug))) {
            return slug;
        }
        return null;
    }

    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            "am", "in", "im", "fuer", "für", "und", "oder", "der", "die", "das", "ein", "eine",
            "mit", "an", "auf", "zum", "zur", "veranstaltung", "veranstaltungen", "events", "event"));

    private boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }

    private static boolean containsPhrase(String haystack, String phrase) {
        return haystack.contains(phrase);
    }

    private static void consume(Set<String> consumed, String... phrases) {
        for (String p : phrases) {
            for (String w : p.split("\\s+")) {
                consumed.add(w);
            }
        }
    }

    /** Hilfs-Set-Builder, falls Aufrufer Slugs sammeln. */
    public static Set<String> slugSet(Iterable<String> slugs) {
        Set<String> s = new LinkedHashSet<String>();
        for (String slug : slugs) {
            s.add(slug);
        }
        return s;
    }
}
