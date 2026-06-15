package de.example.vk.service;

import com.google.gson.JsonObject;
import de.example.vk.service.NlQueryParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Sichert den regelbasierten, offline-fähigen Editor-Vorschlagsdienst ab
 * (Kategorie via NlQueryParser, frequenzbasierte Schlagworte).
 */
public class EditorSuggestServiceTest {

    private final EditorSuggestService service = new EditorSuggestService(new NlQueryParser());
    private final Set<String> slugs = new HashSet<String>(Arrays.asList(
            "musik", "theater", "ausstellung", "sport", "kinder-familie"));

    @Test
    public void derivesCategoryFromText() {
        JsonObject out = service.suggest("Großes Sommerkonzert", "Ein Abend mit Live-Musik im Park.", slugs);
        assertEquals("musik", out.get("category").getAsString());
        assertTrue(out.get("keywords").getAsJsonArray().size() > 0);
    }

    @Test
    public void keywordsDropStopwordsAndShortTokens() {
        List<String> kw = service.extractKeywords(
                "Lesung mit Autorin", "Die Autorin liest aus ihrem neuen Roman und beantwortet Fragen.");
        // Stoppwörter wie "und", "aus", "mit" dürfen nicht erscheinen
        assertFalse(kw.contains("Und"));
        assertFalse(kw.contains("Aus"));
        assertFalse(kw.contains("Mit"));
        // inhaltlich tragende Begriffe schon
        assertTrue(kw.contains("Autorin"));
    }

    @Test
    public void titleTokensRankHigher() {
        List<String> kw = service.extractKeywords(
                "Jazzfestival", "Der Park bietet Platz für viele Gäste beim Festival.");
        // Titelwort hat Bonus -> steht vorn
        assertEquals("Jazzfestival", kw.get(0));
    }

    @Test
    public void keywordsAreCapitalizedAndDeduped() {
        List<String> kw = service.extractKeywords("Theater Theater Theater", "theater theater");
        assertTrue(kw.contains("Theater"));
        assertEquals(1, kw.size());
    }

    @Test
    public void emptyTextYieldsNoCategoryAndNoKeywords() {
        JsonObject out = service.suggest("", "", slugs);
        assertFalse(out.has("category"));
        assertEquals(0, out.get("keywords").getAsJsonArray().size());
    }
}
