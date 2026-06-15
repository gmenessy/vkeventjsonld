package de.example.vk.service.genai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schaltet einen primären Anbieter (z. B. Claude) vor einen lokalen Fallback
 * (Heuristik). Schlägt der primäre Aufruf fehl oder läuft in ein Timeout, liefert
 * der Fallback ein Ergebnis – der Editor bleibt jederzeit funktionsfähig, ohne
 * Kosten- oder Verfügbarkeitsrisiko (gleiche Philosophie wie der Such-Parser).
 */
public class FallbackGenAiProvider implements GenAiProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackGenAiProvider.class);

    private final GenAiProvider primary;
    private final GenAiProvider fallback;

    public FallbackGenAiProvider(GenAiProvider primary, GenAiProvider fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String altText(String title, String categoryName, String placeLabel, String shortDescription) {
        try {
            String out = primary.altText(title, categoryName, placeLabel, shortDescription);
            if (out != null && !out.trim().isEmpty()) {
                return out;
            }
        } catch (RuntimeException e) {
            logFallback("altText", e);
        }
        return fallback.altText(title, categoryName, placeLabel, shortDescription);
    }

    @Override
    public String simplify(String text) {
        try {
            String out = primary.simplify(text);
            if (out != null && !out.trim().isEmpty()) {
                return out;
            }
        } catch (RuntimeException e) {
            logFallback("simplify", e);
        }
        return fallback.simplify(text);
    }

    @Override
    public String name() {
        return primary.name();
    }

    private void logFallback(String op, RuntimeException e) {
        LOG.warn("GenAI-Anbieter '{}' für '{}' fehlgeschlagen, nutze Fallback '{}': {}",
                primary.name(), op, fallback.name(), e.getMessage());
    }
}
