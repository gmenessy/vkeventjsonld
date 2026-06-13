package de.example.vk.api;

import com.google.gson.JsonObject;
import de.example.vk.service.NotFoundException;
import de.example.vk.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Wandelt Fehler in das einheitliche Fehlerformat um (Spezifikation 14.2). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public JsonObject notFound(NotFoundException ex) {
        return Json.error("NOT_FOUND", null, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JsonObject badParam(MethodArgumentTypeMismatchException ex) {
        return Json.error("VALIDATION_INVALID", ex.getName(),
                "Ungueltiger Wert fuer Parameter '" + ex.getName() + "'.");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public JsonObject internal(Exception ex) {
        LOG.error("Unerwarteter Fehler", ex);
        return Json.error("INTERNAL_ERROR", null, "Es ist ein unerwarteter Fehler aufgetreten.");
    }
}
