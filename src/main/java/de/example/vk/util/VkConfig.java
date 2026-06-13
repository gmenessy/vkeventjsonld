package de.example.vk.util;

/**
 * Mandanten-Kontext fuer den aktuellen Request.
 *
 * <p>Multi-Tenant-Betrieb: Jeder Datensatz gehoert zu genau einem Mandanten
 * ({@code MANDANT_ID}) und innerhalb dessen zu genau einem Veranstaltungskalender
 * ({@code VK_ID}). Beide sind {@code Long}. Mandant und VK sind strikt isoliert –
 * keine Query darf ohne diesen Kontext laufen.</p>
 *
 * <p>Der Kontext wird pro Request vom {@code TenantFilter} gesetzt und am Ende
 * wieder geleert (ThreadLocal). Repositories lesen ihn ueber die statischen
 * Methoden {@link #getMandant()} und {@link #getVkId()}.</p>
 */
public final class VkConfig {

    private static final ThreadLocal<Long> MANDANT = new ThreadLocal<Long>();
    private static final ThreadLocal<Long> VK = new ThreadLocal<Long>();

    private VkConfig() {
    }

    /** Aktueller Mandant oder {@code null}, wenn kein Kontext gesetzt ist. */
    public static Long getMandant() {
        return MANDANT.get();
    }

    /** Aktueller VK oder {@code null}, wenn kein Kontext gesetzt ist. */
    public static Long getVkId() {
        return VK.get();
    }

    public static void set(Long mandant, Long vkId) {
        MANDANT.set(mandant);
        VK.set(vkId);
    }

    public static void clear() {
        MANDANT.remove();
        VK.remove();
    }

    /**
     * Liefert den Mandanten oder wirft, wenn kein Kontext gesetzt ist (fail-closed).
     * So kann keine Abfrage versehentlich mandantenuebergreifend laufen.
     */
    public static long requireMandant() {
        Long m = MANDANT.get();
        if (m == null) {
            throw new IllegalStateException("Kein Mandanten-Kontext gesetzt (MANDANT_ID).");
        }
        return m;
    }

    public static long requireVkId() {
        Long vk = VK.get();
        if (vk == null) {
            throw new IllegalStateException("Kein VK-Kontext gesetzt (VK_ID).");
        }
        return vk;
    }
}
