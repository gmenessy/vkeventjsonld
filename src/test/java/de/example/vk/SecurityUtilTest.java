package de.example.vk;

import de.example.vk.util.HtmlSanitizer;
import de.example.vk.util.PasswordHasher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecurityUtilTest {

    @Test
    public void passwordRoundtrip() {
        String hash = PasswordHasher.hash("geheim123");
        assertTrue(PasswordHasher.verify("geheim123", hash));
        assertFalse(PasswordHasher.verify("falsch", hash));
        assertTrue(hash.startsWith("pbkdf2$"));
    }

    @Test
    public void sanitizerRemovesScriptKeepsAllowedTags() {
        String dirty = "<p>Hallo <strong>Welt</strong><script>alert(1)</script>"
                + "<a href=\"javascript:evil()\">x</a><a href=\"https://ok.example\">ok</a></p>";
        String clean = HtmlSanitizer.sanitize(dirty);
        assertFalse("script muss entfernt sein", clean.toLowerCase().contains("<script"));
        assertFalse("javascript:-URL muss entfernt sein", clean.toLowerCase().contains("javascript:"));
        assertTrue("erlaubtes <strong> bleibt", clean.contains("<strong>Welt</strong>"));
        assertTrue("erlaubter https-Link bleibt", clean.contains("https://ok.example"));
    }

    @Test
    public void stripAllRemovesEveryTag() {
        assertEquals("Hallo Welt", HtmlSanitizer.stripAll("<b>Hallo</b> <i>Welt</i>"));
    }
}
