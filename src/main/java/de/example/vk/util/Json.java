package de.example.vk.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Leichtgewichtige JSON-Helfer. Das System verzichtet bewusst auf DTO-Klassen
 * und baut alle Antworten direkt als Gson-{@link JsonObject}/{@link JsonArray}.
 *
 * <p>Enthaelt das einheitliche Antwort-Format (Spezifikation Kapitel 14) sowie
 * Null-sichere Setter und Konverter fuer JDBC-Werte.</p>
 */
public final class Json {

    // Feste Anwendungs-Zeitzone, unabhaengig von der Server-Zeitzone. So liefert
    // dieselbe gespeicherte Wandzeit immer denselben Offset (kein Server-TZ-Drift).
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Json() {
    }

    // ------------------------------------------------------------------
    // Antwort-Envelope
    // ------------------------------------------------------------------

    public static JsonObject ok(JsonElement data) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.add("data", data == null ? JsonNull.INSTANCE : data);
        root.add("messages", new JsonArray());
        root.add("errors", new JsonArray());
        return root;
    }

    public static JsonObject ok(JsonElement data, int page, int size, long total) {
        JsonObject root = ok(data);
        JsonObject meta = new JsonObject();
        meta.addProperty("page", page);
        meta.addProperty("size", size);
        meta.addProperty("total", total);
        root.add("meta", meta);
        return root;
    }

    public static JsonObject error(String code, String field, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.add("data", JsonNull.INSTANCE);
        root.add("messages", new JsonArray());
        JsonArray errors = new JsonArray();
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        if (field != null) {
            err.addProperty("field", field);
        }
        err.addProperty("message", message);
        errors.add(err);
        root.add("errors", errors);
        return root;
    }

    // ------------------------------------------------------------------
    // Null-sichere Setter
    // ------------------------------------------------------------------

    public static void str(JsonObject obj, String key, String value) {
        obj.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    /** Liest einen String aus einem JsonObject oder gibt null zurück. */
    public static String optString(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /** Liest einen boolean aus einem JsonObject (Default false). */
    public static boolean optBool(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).getAsBoolean();
    }

    /** Setzt nur, wenn der Wert vorhanden und nicht leer ist. */
    public static void strIfPresent(JsonObject obj, String key, String value) {
        if (value != null && !value.isEmpty()) {
            obj.addProperty(key, value);
        }
    }

    public static void num(JsonObject obj, String key, Number value) {
        obj.add(key, value == null ? JsonNull.INSTANCE : new JsonPrimitive(value));
    }

    // ------------------------------------------------------------------
    // JDBC-Konverter
    // ------------------------------------------------------------------

    /** ISO-8601 mit Zeitzonenoffset, z. B. 2026-07-18T19:00:00+02:00. */
    public static String iso(Timestamp ts) {
        return ts == null ? null : ISO.format(ts.toLocalDateTime().atZone(ZONE).toOffsetDateTime());
    }

    public static void isoField(JsonObject obj, String key, Timestamp ts) {
        str(obj, key, iso(ts));
    }

    public static void bool(JsonObject obj, String key, String yn) {
        obj.addProperty(key, "Y".equals(yn));
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int value = rs.getInt(col);
        return rs.wasNull() ? null : value;
    }

    public static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double value = rs.getDouble(col);
        return rs.wasNull() ? null : value;
    }

    public static BigDecimal bigDecimalOrNull(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }
}
