package de.example.vk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

/** Spring-MVC-Konfiguration: JSON-Kommunikation ausschliesslich ueber Gson. */
@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "de.example.vk")
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertyPlaceholder() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setIgnoreUnresolvablePlaceholders(true);
        return configurer;
    }

    @Bean
    public GsonHttpMessageConverter gsonHttpMessageConverter() {
        GsonHttpMessageConverter converter = new GsonHttpMessageConverter();
        converter.setGson(GsonFactory.create());
        return converter;
    }

    /**
     * Spring-7-API (ersetzt das zur Entfernung markierte {@code configureMessageConverters(List)}).
     * Bewusst schlank: keine automatisch erkannten Standard-Konverter (z. B. Jackson),
     * sondern JSON ausschliesslich ueber den konfigurierten Gson-Konverter plus Text/CSV.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.disableDefaults()
                .withJsonConverter(gsonHttpMessageConverter())
                .withStringConverter(new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }
}
