package de.example.vk.servlet;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Setzt Sicherheits-Header inkl. Content Security Policy (Spezifikation 25.7, 26).
 *
 * <p>Pro Request wird ein CSP-Nonce erzeugt, als Request-Attribut {@code cspNonce}
 * bereitgestellt (von der Velocity-Shell genutzt) und in {@code script-src}
 * aufgenommen. Externe Skripte sind über {@code 'self'} weiterhin erlaubt.</p>
 */
public class SecurityHeadersFilter implements Filter {

    public static final String NONCE_ATTRIBUTE = "cspNonce";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse http = (HttpServletResponse) response;
        String nonce = newNonce();
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        http.setHeader("Content-Security-Policy",
                "default-src 'self'; "
              + "script-src 'self' 'nonce-" + nonce + "'; "
              + "style-src 'self' 'unsafe-inline'; "
              + "img-src 'self' data:; "
              + "connect-src 'self'; "
              + "frame-ancestors 'none'; "
              + "base-uri 'self'; "
              + "form-action 'self'");
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("Referrer-Policy", "same-origin");
        applyCaching((HttpServletRequest) request, http);
        chain.doFilter(request, response);
    }

    /**
     * Caching-Strategie (Performance, ohne Frische zu opfern):
     * <ul>
     *   <li>Statische Assets ({@code /assets/*}): eine Stunde cachebar; nach Ablauf
     *       revalidiert der Browser über das vom Default-Servlet gesetzte ETag.</li>
     *   <li>SPA-Shell (Kontext-Root): {@code no-cache}, da sie pro Request ein frisches
     *       CSP-Nonce und Bootstrap-JSON trägt und nie veraltet ausgeliefert werden darf.</li>
     *   <li>API-Antworten bleiben unangetastet (Controller setzen eigene Header,
     *       z. B. der Kategoriebaum).</li>
     * </ul>
     */
    private static void applyCaching(HttpServletRequest request, HttpServletResponse http) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (uri.startsWith(ctx + "/assets/")) {
            http.setHeader("Cache-Control", "public, max-age=3600");
        } else if (uri.equals(ctx + "/") || uri.equals(ctx) || uri.isEmpty()) {
            http.setHeader("Cache-Control", "no-cache");
        }
    }

    private static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void destroy() { }
}
