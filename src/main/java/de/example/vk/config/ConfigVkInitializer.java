package de.example.vk.config;

import de.example.vk.util.ConfigVk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Setzt den Mandanten-/VK-Kontext dieses Systems beim Start aus der
 * Spring-Konfiguration. Jedes System bringt seinen eigenen Spring-Kontext mit
 * und damit genau ein (MANDANT_ID, VK_ID).
 *
 * <p>Konfiguration ueber {@code vk.mandantId} / {@code vk.vkId} bzw. die
 * Umgebungsvariablen {@code VK_MANDANT_ID} / {@code VK_VK_ID}.</p>
 */
@Component
public class ConfigVkInitializer implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigVkInitializer.class);

    private final long mandantId;
    private final long vkId;

    public ConfigVkInitializer(@Value("${vk.mandantId:${VK_MANDANT_ID:1}}") long mandantId,
                               @Value("${vk.vkId:${VK_VK_ID:1}}") long vkId) {
        this.mandantId = mandantId;
        this.vkId = vkId;
    }

    @Override
    public void afterPropertiesSet() {
        ConfigVk.set(mandantId, vkId);
        LOG.info("Mandanten-Kontext gesetzt: MANDANT_ID={}, VK_ID={}", mandantId, vkId);
    }
}
