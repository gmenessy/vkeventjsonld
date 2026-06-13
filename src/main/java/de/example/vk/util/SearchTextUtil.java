package de.example.vk.util;

import java.util.Locale;

/**
 * Erzeugt den denormalisierten Suchtext (VK_EVENT.SEARCH_TEXT).
 *
 * Performance-Konzept: Statt zur Suchzeit ueber viele Tabellen zu joinen, wird
 * beim Schreiben eines Events ein normalisierter Suchtext (Titel, Kurzbeschreibung,
 * Ort, Stadt, Veranstalter, Kategorien, Schlagworte) klein geschrieben abgelegt.
 * Die Suche prueft dann nur eine einzige Spalte (INSTR bzw. Oracle Text CONTAINS).
 */
public final class SearchTextUtil {

    private SearchTextUtil() {
    }

    public static String normalizeTerm(String term) {
        if (term == null) {
            return "";
        }
        return term.trim().toLowerCase(Locale.GERMAN);
    }

    public static String build(String... parts) {
        StringBuilder sb = new StringBuilder(256);
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim().toLowerCase(Locale.GERMAN));
            }
        }
        return sb.toString();
    }
}
