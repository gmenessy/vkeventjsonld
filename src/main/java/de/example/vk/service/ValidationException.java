package de.example.vk.service;

/** Fachlicher Validierungsfehler – wird in eine 400-Antwort mit Feldbezug übersetzt. */
public class ValidationException extends RuntimeException {

    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
