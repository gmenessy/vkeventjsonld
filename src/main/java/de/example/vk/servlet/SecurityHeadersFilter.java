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
        chain.doFilter(request, response);
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
