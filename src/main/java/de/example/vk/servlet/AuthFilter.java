package de.example.vk.servlet;

import de.example.vk.util.CurrentUser;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Schützt die geschützten API-Bereiche:
 * <ul>
 *   <li>{@code /api/me/*}    – angemeldeter Benutzer erforderlich</li>
 *   <li>{@code /api/admin/*} – Rolle EDITOR oder ADMIN erforderlich</li>
 * </ul>
 * Bei schreibenden Methoden wird zusätzlich das CSRF-Token geprüft. Der angemeldete
 * Benutzer wird für die Dauer des Requests in {@link CurrentUser} bereitgestellt.
 */
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();
        boolean adminArea = path.contains("/api/admin/");

        HttpSession session = request.getSession(false);
        Long userId = session == null ? null : (Long) session.getAttribute("userId");
        if (userId == null) {
            deny(response, 401, "AUTH_REQUIRED", "Bitte zuerst anmelden.");
            return;
        }

        String rolesStr = (String) session.getAttribute("roles");
        Set<String> roles = rolesStr == null || rolesStr.isEmpty()
                ? new HashSet<String>() : new HashSet<String>(Arrays.asList(rolesStr.split(",")));
        if (adminArea && !(roles.contains("EDITOR") || roles.contains("ADMIN"))) {
            deny(response, 403, "FORBIDDEN", "Keine Berechtigung für den Redaktionsbereich.");
            return;
        }

        if (isWrite(request.getMethod())) {
            String sent = request.getHeader("X-CSRF-Token");
            String expected = (String) session.getAttribute("csrf");
            if (expected == null || !expected.equals(sent)) {
                deny(response, 403, "CSRF_INVALID", "Ungültiges oder fehlendes CSRF-Token.");
                return;
            }
        }

        Long mandantId = (Long) session.getAttribute("mandantId");
        String displayName = (String) session.getAttribute("displayName");
        try {
            CurrentUser.set(new CurrentUser(userId, displayName, mandantId == null ? 0L : mandantId, roles));
            chain.doFilter(req, res);
        } finally {
            CurrentUser.clear();
        }
    }

    private static boolean isWrite(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
    }

    private static void deny(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"data\":null,\"messages\":[],\"errors\":[{\"code\":\""
                        + code + "\",\"message\":\"" + message + "\"}]}");
    }

    @Override
    public void init(FilterConfig filterConfig) { }

    @Override
    public void destroy() { }
}
