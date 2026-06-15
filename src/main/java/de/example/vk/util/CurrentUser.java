package de.example.vk.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Aktueller angemeldeter Benutzer für die Dauer eines Requests (gesetzt vom AuthFilter
 * aus der HttpSession, am Ende geleert). Rollen z. B. REGISTERED, EDITOR, ADMIN.
 */
public final class CurrentUser {

    private static final ThreadLocal<CurrentUser> CTX = new ThreadLocal<CurrentUser>();

    public final long userId;
    public final String displayName;
    public final long mandantId;
    public final Set<String> roles;

    public CurrentUser(long userId, String displayName, long mandantId, Set<String> roles) {
        this.userId = userId;
        this.displayName = displayName;
        this.mandantId = mandantId;
        this.roles = Collections.unmodifiableSet(new HashSet<String>(roles));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isEditor() {
        return roles.contains("EDITOR") || roles.contains("ADMIN");
    }

    public static void set(CurrentUser user) {
        CTX.set(user);
    }

    public static CurrentUser get() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }

    public static long requireUserId() {
        CurrentUser u = CTX.get();
        if (u == null) {
            throw new IllegalStateException("Kein angemeldeter Benutzer im Kontext.");
        }
        return u.userId;
    }
}
