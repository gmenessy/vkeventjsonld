package de.example.vk.servlet;

import de.example.vk.util.VkConfig;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Loest den Mandanten-Kontext (MANDANT_ID, VK_ID) pro Request auf und legt ihn
 * in {@link VkConfig} ab. Der Kontext wird am Ende garantiert geleert.
 *
 * <p>Aufloesung (erste Quelle gewinnt):</p>
 * <ol>
 *   <li>Header {@code X-Mandant-Id} / {@code X-Vk-Id}</li>
 *   <li>Query-Parameter {@code mandant} / {@code vk}</li>
 *   <li>Dev-Default (mandant=1, vk=1)</li>
 * </ol>
 *
 * <p><b>Produktion:</b> Hier muss die Aufloesung an die sichere Quelle gebunden
 * werden – z. B. Host/Subdomain-Mapping oder den authentifizierten Benutzer –,
 * nicht an frei setzbare Parameter. Die Methodensignatur bleibt gleich.</p>
 */
public class TenantFilter implements Filter {

    private static final long DEFAULT_MANDANT = 1L;
    private static final long DEFAULT_VK = 1L;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        try {
            Long mandant = firstLong(http.getHeader("X-Mandant-Id"), http.getParameter("mandant"), DEFAULT_MANDANT);
            Long vk = firstLong(http.getHeader("X-Vk-Id"), http.getParameter("vk"), DEFAULT_VK);
            VkConfig.set(mandant, vk);
            chain.doFilter(request, response);
        } finally {
            VkConfig.clear();
        }
    }

    private static Long firstLong(String headerValue, String paramValue, long fallback) {
        Long fromHeader = parse(headerValue);
        if (fromHeader != null) {
            return fromHeader;
        }
        Long fromParam = parse(paramValue);
        if (fromParam != null) {
            return fromParam;
        }
        return fallback;
    }

    private static Long parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void destroy() { }
}
