package de.example.vk.service.upload;

/** Fehler beim Datei-Upload (ungültige/zu große Datei, I/O). */
public class UploadException extends RuntimeException {

    public UploadException(String message) {
        super(message);
    }

    public UploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
