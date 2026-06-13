package de.example.vk.servlet;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Setzt Sicherheits-Header inkl. Content Security Policy (Spezifikation 25.7, 26).
 *
 * <p>Alle Skripte werden aus externen Dateien geladen (script-src 'self'),
 * daher ist kein Inline-Script-Nonce noetig. Inline-Styles sind im MVP erlaubt.</p>
 */
public class SecurityHeadersFilter implements Filter {

    private static final String CSP =
            "default-src 'self'; "
          + "script-src 'self'; "
          + "style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data:; "
          + "connect-src 'self'; "
          + "frame-ancestors 'none'; "
          + "base-uri 'self'; "
          + "form-action 'self'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse http = (HttpServletResponse) response;
        http.setHeader("Content-Security-Policy", CSP);
        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("Referrer-Policy", "same-origin");
        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void destroy() { }
}
