package de.example.vk.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Serverseitiges HTML-Sanitizing für Event-Beschreibungen (Spezifikation 25.6/26.3).
 *
 * <p>Erlaubt nur die in der Spezifikation genannten Tags
 * ({@code p, br, strong, em, ul, ol, li, a}); {@code a} nur mit http(s)/mailto.
 * Alles andere (Scripts, Event-Handler, Styles, fremde Tags) wird entfernt.
 * Nutzt jsoup – kein handgeschriebener Parser, der bei XSS leicht Lücken hätte.</p>
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "ul", "ol", "li", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer");

    private HtmlSanitizer() {
    }

    /** Liefert sicheres HTML zurück (oder {@code null}, wenn Eingabe null ist). */
    public static String sanitize(String html) {
        if (html == null) {
            return null;
        }
        return Jsoup.clean(html, SAFELIST);
    }

    /** Reiner Text ohne jegliche Tags – z. B. für Titel/Kurzbeschreibung. */
    public static String stripAll(String text) {
        if (text == null) {
            return null;
        }
        return Jsoup.clean(text, Safelist.none());
    }
}
