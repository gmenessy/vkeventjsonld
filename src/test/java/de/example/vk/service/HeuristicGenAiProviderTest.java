package de.example.vk.service;

import de.example.vk.service.genai.GenAiProvider;
import de.example.vk.service.genai.HeuristicGenAiProvider;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Sichert die deterministische, offline-fähige GenAI-Heuristik ab (Standardanbieter
 * und harter Fallback). Keine externen Aufrufe – läuft in der CI ohne Netz.
 */
public class HeuristicGenAiProviderTest {

    private final GenAiProvider genAi = new HeuristicGenAiProvider();

    @Test
    public void altTextCombinesContextAndStaysShort() {
        String alt = genAi.altText("Sommerkonzert", "Musik", "Stadtpark", null);
        assertTrue(alt.startsWith("Veranstaltungsbild: Sommerkonzert"));
        assertTrue(alt.contains("Kategorie Musik"));
        assertTrue(alt.contains("Ort Stadtpark"));
        assertTrue("Alt-Text sollte <= 125 Zeichen sein", alt.length() <= 125);
    }

    @Test
    public void altTextAvoidsRedundantPlaceAlreadyInTitle() {
        String alt = genAi.altText("Konzert im Stadtpark", "Musik", "Stadtpark", null);
        // "Stadtpark" steckt schon im Titel -> nicht erneut als Ort anhängen
        assertEquals(1, countOccurrences(alt, "Stadtpark"));
    }

    @Test
    public void altTextEmptyWithoutTitle() {
        assertEquals("", genAi.altText(null, "Musik", "Stadtpark", null));
        assertEquals("", genAi.altText("   ", null, null, null));
    }

    @Test
    public void simplifyPutsOneSentencePerLineAndExpandsAbbreviations() {
        String out = genAi.simplify("Das Konzert beginnt um 19 Uhr. Eintritt ca. 10 Euro, z. B. fuer Studierende.");
        String[] lines = out.split("\n");
        assertEquals(2, lines.length);
        assertTrue(out.contains("etwa"));        // ca. -> etwa
        assertTrue(out.contains("zum Beispiel")); // z. B. -> zum Beispiel
        assertFalse(out.contains("ca."));
        assertFalse(out.contains("z. B."));
    }

    @Test
    public void simplifyKeepsDecimalPointsIntact() {
        String out = genAi.simplify("Die Strecke ist 3.5 Kilometer lang.");
        // 3.5 darf nicht als Satzende interpretiert werden
        assertEquals(1, out.split("\n").length);
        assertTrue(out.contains("3.5"));
    }

    @Test
    public void simplifyEmptyInput() {
        assertEquals("", genAi.simplify(null));
        assertEquals("", genAi.simplify("   "));
    }

    @Test
    public void nameIsHeuristik() {
        assertEquals("heuristik", genAi.name());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
