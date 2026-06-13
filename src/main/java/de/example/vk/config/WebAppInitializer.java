package de.example.vk.config;

import de.example.vk.servlet.SecurityHeadersFilter;
import de.example.vk.servlet.TenantFilter;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import java.util.EnumSet;

/**
 * Programmatische Servlet-Konfiguration (Ersatz fuer das web.xml-DispatcherServlet).
 *
 * <p>Es wird ein einziger Spring-Kontext verwendet: {@link WebConfig} scannt das
 * gesamte Paket {@code de.example.vk} (inkl. {@link DataSourceConfig}), das haelt
 * die Konfiguration leichtgewichtig. Statische Dateien (SPA-Shell, Assets) liefert
 * der Default-Servlet des Containers aus.</p>
 */
public class WebAppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    @Override
    protected Class<?>[] getRootConfigClasses() {
        return null;
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class<?>[]{WebConfig.class};
    }

    @Override
    protected String[] getServletMappings() {
        return new String[]{"/api/*"};
    }

    @Override
    protected void customizeRegistration(ServletRegistration.Dynamic registration) {
        registration.setAsyncSupported(true);
    }

    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        super.onStartup(servletContext);
        servletContext.addFilter("securityHeaders", new SecurityHeadersFilter())
                .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        // Mandanten-Kontext fuer alle API-Aufrufe aufloesen.
        servletContext.addFilter("tenant", new TenantFilter())
                .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/api/*");
    }
}
