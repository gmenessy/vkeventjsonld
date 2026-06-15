package de.example.vk.config;

import de.example.vk.service.genai.ClaudeGenAiProvider;
import de.example.vk.service.genai.FallbackGenAiProvider;
import de.example.vk.service.genai.GenAiProvider;
import de.example.vk.service.genai.HeuristicGenAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wählt den GenAI-Anbieter beim Start aus der Konfiguration.
 *
 * <p>Standard ist die regelbasierte, kostenfreie {@link HeuristicGenAiProvider}.
 * Ist ein Anthropic-Schlüssel gesetzt ({@code vk.genai.apiKey} bzw.
 * {@code ANTHROPIC_API_KEY}) und der Anbieter nicht explizit auf {@code heuristik}
 * gestellt, wird {@link ClaudeGenAiProvider} mit hartem Fallback auf die Heuristik
 * aktiviert – höhere Qualität, ohne die Verfügbarkeit zu gefährden.</p>
 */
@Configuration
public class GenAiConfig {

    private static final Logger LOG = LoggerFactory.getLogger(GenAiConfig.class);

    @Bean
    public GenAiProvider genAiProvider(
            @Value("${vk.genai.provider:${VK_GENAI_PROVIDER:auto}}") String provider,
            @Value("${vk.genai.apiKey:${ANTHROPIC_API_KEY:}}") String apiKey,
            @Value("${vk.genai.model:${VK_GENAI_MODEL:claude-haiku-4-5}}") String model,
            @Value("${vk.genai.connectTimeoutMs:3000}") int connectTimeoutMs,
            @Value("${vk.genai.readTimeoutMs:9000}") int readTimeoutMs) {

        HeuristicGenAiProvider heuristic = new HeuristicGenAiProvider();
        boolean wantClaude = !"heuristik".equalsIgnoreCase(provider)
                && apiKey != null && !apiKey.trim().isEmpty();

        if (wantClaude) {
            LOG.info("GenAI-Anbieter: Claude (Modell {}), Fallback Heuristik.", model);
            ClaudeGenAiProvider claude = new ClaudeGenAiProvider(
                    apiKey.trim(), model, connectTimeoutMs, readTimeoutMs);
            return new FallbackGenAiProvider(claude, heuristic);
        }
        LOG.info("GenAI-Anbieter: Heuristik (regelbasiert, kostenfrei). "
                + "Setze ANTHROPIC_API_KEY für Claude-Qualität.");
        return heuristic;
    }
}
