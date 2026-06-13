package de.example.vk.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Zentrale Gson-Konfiguration (Spezifikation Kapitel 13).
 *
 * <p>disableHtmlEscaping() gilt nur fuer den JSON-Transport. HTML-Ausgabe erfolgt
 * im Frontend ausschliesslich DOM-sicher (textContent), niemals per innerHTML.</p>
 *
 * <p>Datumswerte werden bereits als ISO-8601-Strings in die JSON-Struktur gelegt
 * (siehe {@code Json.iso}), daher ist kein Typadapter noetig.</p>
 */
public final class GsonFactory {

    private GsonFactory() {
    }

    public static Gson create() {
        return new GsonBuilder()
                .serializeNulls()
                .disableHtmlEscaping()
                .create();
    }
}
