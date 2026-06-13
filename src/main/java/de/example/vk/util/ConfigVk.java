package de.example.vk.util;

/**
 * Mandanten-/VK-Kontext des Systems.
 *
 * <p>Multi-Tenant-Betrieb: <b>jedes System hat seinen eigenen Spring-Kontext</b>
 * und bedient genau einen Mandanten ({@code MANDANT_ID}) und einen
 * Veranstaltungskalender ({@code VK_ID}). Beide Werte sind {@code Long} und werden
 * beim Start aus der Spring-Konfiguration gesetzt (siehe {@code ConfigVkInitializer})
 * und bleiben fuer die gesamte Laufzeit konstant.</p>
 *
 * <p>Der Zugriff erfolgt ueber die statischen Methoden {@link #getMandant()} und
 * {@link #getVkId()}. Repositories scopen damit jede Abfrage auf den eigenen
 * Mandanten/VK – ein System kann fremde Daten nicht sehen.</p>
 */
public final class ConfigVk {

    private static volatile Long mandant;
    private static volatile Long vkId;

    private ConfigVk() {
    }

    /** Mandant dieses Systems (oder {@code null}, falls nicht konfiguriert). */
    public static Long getMandant() {
        return mandant;
    }

    /** VK dieses Systems (oder {@code null}, falls nicht konfiguriert). */
    public static Long getVkId() {
        return vkId;
    }

    /** Wird beim Start aus der Spring-Konfiguration gesetzt (bzw. von Tests). */
    public static void set(Long mandantId, Long vk) {
        mandant = mandantId;
        vkId = vk;
    }

    public static void clear() {
        mandant = null;
        vkId = null;
    }

    /** Mandant oder Fehler, falls nicht konfiguriert (fail-closed). */
    public static long requireMandant() {
        Long m = mandant;
        if (m == null) {
            throw new IllegalStateException("ConfigVk: MANDANT_ID ist nicht konfiguriert.");
        }
        return m;
    }

    public static long requireVkId() {
        Long vk = vkId;
        if (vk == null) {
            throw new IllegalStateException("ConfigVk: VK_ID ist nicht konfiguriert.");
        }
        return vk;
    }
}
