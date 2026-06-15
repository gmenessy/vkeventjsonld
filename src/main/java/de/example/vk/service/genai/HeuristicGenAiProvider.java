package de.example.vk.service.genai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Regelbasierte, offline-fähige GenAI-Assistenz – Standardanbieter und zugleich
 * harter Fallback. Erzeugt deterministische, kostenfreie Vorschläge ohne externe
 * Aufrufe; dadurch immer verfügbar und ohne Latenz im Editor.
 */
public class HeuristicGenAiProvider implements GenAiProvider {

    /** Empfohlene Höchstlänge für Alt-Text (Screenreader-freundlich). */
    private static final int ALT_MAX = 125;

    @Override
    public String altText(String title, String categoryName, String placeLabel, String shortDescription) {
        String t = trimToNull(title);
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Veranstaltungsbild: ").append(t);
        String cat = trimToNull(categoryName);
        if (cat != null && !containsWord(t, cat)) {
            sb.append(", Kategorie ").append(cat);
        }
        String place = trimToNull(placeLabel);
        if (place != null && !containsWord(t, place)) {
            sb.append(", Ort ").append(place);
        }
        sb.append('.');
        return truncate(sb.toString(), ALT_MAX);
    }

    @Override
    public String simplify(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "";
        }
        String expanded = expandAbbreviations(t);
        List<String> sentences = splitSentences(expanded);
        List<String> lines = new ArrayList<String>();
        for (String s : sentences) {
            for (String part : breakLongSentence(s)) {
                String clean = part.trim();
                if (!clean.isEmpty()) {
                    lines.add(ensureSentenceEnd(clean));
                }
            }
        }
        // „Einfache Sprache": ein Satz pro Zeile.
        return joinLines(lines);
    }

    @Override
    public String name() {
        return "heuristik";
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private static final Map<String, String> ABBREV = new LinkedHashMap<String, String>();
    static {
        ABBREV.put("z. B.", "zum Beispiel");
        ABBREV.put("z.B.", "zum Beispiel");
        ABBREV.put("u. a.", "unter anderem");
        ABBREV.put("u.a.", "unter anderem");
        ABBREV.put("u. v. m.", "und vieles mehr");
        ABBREV.put("usw.", "und so weiter");
        ABBREV.put("etc.", "und so weiter");
        ABBREV.put("ca.", "etwa");
        ABBREV.put("inkl.", "mit");
        ABBREV.put("exkl.", "ohne");
        ABBREV.put("ggf.", "vielleicht");
        ABBREV.put("bzw.", "oder");
        ABBREV.put("d. h.", "das heißt");
        ABBREV.put("d.h.", "das heißt");
        ABBREV.put("p. P.", "pro Person");
        ABBREV.put("p.P.", "pro Person");
        ABBREV.put("Erw.", "Erwachsene");
        ABBREV.put("erm.", "ermäßigt");
    }

    private static String expandAbbreviations(String s) {
        String out = s;
        for (Map.Entry<String, String> e : ABBREV.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Trennt an Satzenden (., !, ?), respektiert aber Dezimal-/Uhrzeitpunkte grob. */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            cur.append(c);
            if (c == '.' || c == '!' || c == '?') {
                boolean digitAround = i > 0 && Character.isDigit(s.charAt(i - 1))
                        && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1));
                boolean nextSpaceOrEnd = i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1));
                if (!digitAround && nextSpaceOrEnd) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static final String[] CONJUNCTIONS = {" sowie ", " sodass ", " sodaß ", " sowohl ", " außerdem ", " ausserdem "};

    /** Zerlegt sehr lange Sätze (> 18 Wörter) an Konjunktionen in mehrere Zeilen. */
    private static List<String> breakLongSentence(String sentence) {
        List<String> out = new ArrayList<String>();
        String s = sentence.trim();
        if (countWords(s) <= 18) {
            out.add(s);
            return out;
        }
        String working = s;
        for (String conj : CONJUNCTIONS) {
            int idx = working.toLowerCase(Locale.GERMAN).indexOf(conj);
            if (idx > 0 && countWords(working.substring(0, idx)) >= 4) {
                out.add(working.substring(0, idx).trim());
                working = capitalize(working.substring(idx + conj.length()).trim());
                break;
            }
        }
        out.add(working);
        return out;
    }

    private static int countWords(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return 0;
        }
        return t.split("\\s+").length;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String ensureSentenceEnd(String s) {
        char last = s.charAt(s.length() - 1);
        if (last == '.' || last == '!' || last == '?' || last == ':') {
            return s;
        }
        return s + ".";
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean containsWord(String haystack, String needle) {
        return haystack.toLowerCase(Locale.GERMAN).contains(needle.toLowerCase(Locale.GERMAN));
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String cut = s.substring(0, max - 1);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > max / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut + "…";
    }
}
