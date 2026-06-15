package de.example.vk.service.genai;

/**
 * Redaktionelle GenAI-Assistenz zur <em>Schreibzeit</em> (nicht im heißen Such-Pfad):
 * Vorschlag eines Bild-Alternativtexts und Umschreiben in „Einfache Sprache".
 *
 * <p>Bewusst als austauschbare Schnittstelle: Standard ist eine regelbasierte,
 * offline-fähige Implementierung ({@link HeuristicGenAiProvider}) mit <em>null Kosten
 * und null externer Abhängigkeit</em>. Ist ein Anthropic-Schlüssel konfiguriert, wird
 * {@link ClaudeGenAiProvider} vorgeschaltet und liefert höhere Qualität; bei Fehler
 * oder Timeout greift über {@link FallbackGenAiProvider} stets die Heuristik
 * (gleiche Philosophie wie der natürlichsprachige Such-Parser).</p>
 */
public interface GenAiProvider {

    /**
     * Schlägt einen knappen, beschreibenden Alternativtext (WCAG 1.1.1) für ein
     * Veranstaltungsbild vor – abgeleitet aus dem Kontext der Veranstaltung.
     *
     * @param title            Titel der Veranstaltung (Pflichtkontext)
     * @param categoryName     Anzeigename der Kategorie (oder {@code null})
     * @param placeLabel       Ortsbezeichnung (oder {@code null})
     * @param shortDescription Kurzbeschreibung (oder {@code null})
     * @return Vorschlag, höchstens ~125 Zeichen, deutsch
     */
    String altText(String title, String categoryName, String placeLabel, String shortDescription);

    /** Schreibt einen deutschen Text in „Einfache Sprache" um (kurze Sätze, ein Satz pro Zeile). */
    String simplify(String text);

    /** Kennung des aktiven Anbieters (Diagnose/Anzeige), z. B. {@code "heuristik"} oder {@code "claude"}. */
    String name();
}
