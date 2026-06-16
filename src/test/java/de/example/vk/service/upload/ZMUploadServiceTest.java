package de.example.vk.service.upload;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Prüft den Standard-Upload-Service (lokaler Datei-Store, absolute Rückgabe-URL). */
public class ZMUploadServiceTest {

    private static Path tempDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "vk-upload-test-" + System.nanoTime());
    }

    private static MockHttpServletRequest requestWith(String filename, String contentType, byte[] bytes) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/me/uploads");
        MockPart part = new MockPart("file", filename, bytes);
        part.getHeaders().add("Content-Type", contentType);
        req.addPart(part);
        return req;
    }

    @Test
    public void storesImageAndReturnsAbsoluteUrl() throws Exception {
        Path dir = tempDir();
        ZMUploadService svc = new ZMUploadService(dir.toString(), "", 10_485_760L);
        byte[] bytes = "PNGDATA".getBytes(StandardCharsets.UTF_8);
        String url = svc.uploadFile(requestWith("foto.png", "image/png", bytes));

        assertTrue("URL absolut", url.startsWith("http://localhost/uploads/"));
        assertTrue("Endung erhalten", url.endsWith(".png"));
        String name = url.substring(url.lastIndexOf('/') + 1);
        Path stored = dir.resolve(name);
        assertTrue("Datei gespeichert", Files.isRegularFile(stored));
        assertEquals("Bytes identisch", "PNGDATA", new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
    }

    @Test
    public void usesConfiguredBaseUrlWhenSet() {
        ZMUploadService svc = new ZMUploadService(tempDir().toString(), "https://cdn.example.org/", 10_485_760L);
        String url = svc.uploadFile(requestWith("a.pdf", "application/pdf", "PDF".getBytes(StandardCharsets.UTF_8)));
        assertTrue(url.startsWith("https://cdn.example.org/uploads/"));
        assertTrue(url.endsWith(".pdf"));
    }

    @Test
    public void rejectsDisallowedContentType() {
        ZMUploadService svc = new ZMUploadService(tempDir().toString(), "", 10_485_760L);
        try {
            svc.uploadFile(requestWith("evil.exe", "application/x-msdownload", new byte[]{1, 2, 3}));
            fail("Unerlaubter Typ hätte abgelehnt werden müssen");
        } catch (UploadException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("nicht erlaubt"));
        }
    }

    @Test
    public void rejectsTooLargeFile() {
        ZMUploadService svc = new ZMUploadService(tempDir().toString(), "", 4L);
        try {
            svc.uploadFile(requestWith("big.png", "image/png", new byte[]{1, 2, 3, 4, 5, 6}));
            fail("Zu große Datei hätte abgelehnt werden müssen");
        } catch (UploadException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("groß"));
        }
    }

    @Test
    public void rejectsRequestWithoutFile() {
        ZMUploadService svc = new ZMUploadService(tempDir().toString(), "", 10_485_760L);
        try {
            svc.uploadFile(new MockHttpServletRequest("POST", "/api/me/uploads"));
            fail("Request ohne Datei hätte abgelehnt werden müssen");
        } catch (UploadException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("keine datei"));
        }
    }
}
