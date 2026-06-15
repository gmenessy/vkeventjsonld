package de.example.vk.service.genai;

/** Signalisiert ein Problem beim externen GenAI-Aufruf; wird vom Fallback aufgefangen. */
public class GenAiException extends RuntimeException {

    public GenAiException(String message) {
        super(message);
    }

    public GenAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
