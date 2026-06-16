package de.example.vk.service.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Nimmt Datei-Uploads (Bilder und Dokumente) aus einem multipart/form-data-Request
 * entgegen und liefert eine <b>absolute URL</b> auf die gespeicherte Datei zurück.
 *
 * <p>Dies ist die mitgelieferte, lauffähige Standard-Implementierung (lokaler
 * Datei-Store unter {@code vk.upload.dir}); ausgeliefert werden die Dateien vom
 * {@code UploadServlet} unter {@code /uploads/*}. In Produktion kann diese Bohne
 * durch eine umgebungseigene {@code ZMUploadService}-Implementierung (z. B. Objekt-
 * Speicher/CDN) ersetzt werden – die Signatur {@code uploadFile(HttpServletRequest)}
 * mit absoluter Rückgabe-URL bleibt gleich.</p>
 */
@Service
public class ZMUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(ZMUploadService.class);

    /** Erlaubte MIME-Typen (Bilder + gängige Dokumente). */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final Path uploadDir;
    private final String baseUrl;
    private final long maxBytes;

    public ZMUploadService(
            @Value("${vk.upload.dir:${VK_UPLOAD_DIR:}}") String dir,
            @Value("${vk.upload.baseUrl:${VK_BASE_URL:}}") String baseUrl,
            @Value("${vk.upload.maxBytes:10485760}") long maxBytes) {
        this.uploadDir = resolveDir(dir);
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.maxBytes = maxBytes;
    }

    /**
     * Speichert die hochgeladene Datei und gibt deren absolute URL zurück.
     *
     * @throws UploadException bei fehlender/zu großer/unerlaubter Datei oder I/O-Fehlern
     */
    public String uploadFile(HttpServletRequest request) {
        Part part = firstFilePart(request);
        if (part == null) {
            throw new UploadException("Keine Datei im Upload gefunden.");
        }
        if (part.getSize() <= 0) {
            throw new UploadException("Die hochgeladene Datei ist leer.");
        }
        if (part.getSize() > maxBytes) {
            throw new UploadException("Datei zu groß (max. " + (maxBytes / (1024 * 1024)) + " MB).");
        }
        String contentType = part.getContentType() == null ? "" : part.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(contentType)) {
            throw new UploadException("Dateityp nicht erlaubt: " + contentType);
        }

        String ext = extensionFor(contentType, part.getSubmittedFileName());
        String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(storedName);
            try (InputStream in = part.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Datei hochgeladen: {} ({} Bytes, {})", storedName, part.getSize(), contentType);
            return absoluteUrl(request, storedName);
        } catch (IOException e) {
            throw new UploadException("Datei konnte nicht gespeichert werden: " + e.getMessage(), e);
        }
    }

    private static Part firstFilePart(HttpServletRequest request) {
        try {
            for (Part p : request.getParts()) {
                if (p.getSubmittedFileName() != null && !p.getSubmittedFileName().isEmpty()) {
                    return p;
                }
            }
        } catch (Exception e) {
            throw new UploadException("Upload konnte nicht gelesen werden (kein multipart/form-data?): "
                    + e.getMessage(), e);
        }
        return null;
    }

    private String absoluteUrl(HttpServletRequest request, String storedName) {
        if (!baseUrl.isEmpty()) {
            return baseUrl + "/uploads/" + storedName;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        sb.append(request.getContextPath()).append("/uploads/").append(storedName);
        return sb.toString();
    }

    private static String extensionFor(String contentType, String original) {
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                String ext = original.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        switch (contentType) {
            case "image/png": return ".png";
            case "image/jpeg": return ".jpg";
            case "image/webp": return ".webp";
            case "image/gif": return ".gif";
            case "image/svg+xml": return ".svg";
            case "application/pdf": return ".pdf";
            default: return "";
        }
    }

    /** Verzeichnis, aus dem das UploadServlet die Dateien ausliefert. */
    public Path getUploadDir() {
        return uploadDir;
    }

    /** Gemeinsame Verzeichnisauflösung (Service + UploadServlet): Konfig, sonst Standard. */
    public static Path resolveDir(String configured) {
        String d = (configured == null || configured.trim().isEmpty())
                ? System.getProperty("java.io.tmpdir") + "/vk-uploads" : configured.trim();
        return Paths.get(d);
    }
}
