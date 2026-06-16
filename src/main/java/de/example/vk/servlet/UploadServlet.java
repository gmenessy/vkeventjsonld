package de.example.vk.servlet;

import de.example.vk.service.upload.ZMUploadService;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Liefert die vom {@link ZMUploadService} (Standard-Implementierung) lokal
 * gespeicherten Upload-Dateien unter {@code /uploads/*} aus. In Produktion mit
 * externem Objekt-Speicher/CDN wird dieser Servlet typischerweise nicht benötigt.
 *
 * <p>Pfad-Traversal ist ausgeschlossen: ausgeliefert werden nur Dateinamen aus dem
 * vom Service erzeugten Muster (Hex-Name + kurze Endung), keine Unterpfade.</p>
 */
public class UploadServlet extends HttpServlet {

    private final Path dir = ZMUploadService.resolveDir(System.getenv("VK_UPLOAD_DIR"));

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String name = req.getPathInfo(); // z. B. "/abc123.png"
        if (name == null || name.length() < 2) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        name = name.substring(1);
        if (!name.matches("[A-Za-z0-9]+\\.[A-Za-z0-9]{1,8}")) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String mime = URLConnection.guessContentTypeFromName(name);
        resp.setContentType(mime == null ? "application/octet-stream" : mime);
        resp.setHeader("Cache-Control", "public, max-age=86400");
        resp.setContentLengthLong(Files.size(file));
        try (OutputStream out = resp.getOutputStream()) {
            Files.copy(file, out);
        }
    }
}
