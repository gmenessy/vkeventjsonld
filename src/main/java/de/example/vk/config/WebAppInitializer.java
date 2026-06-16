package de.example.vk.config;

import de.example.vk.servlet.AuthFilter;
import de.example.vk.servlet.SecurityHeadersFilter;
import de.example.vk.servlet.ShellServlet;
import de.example.vk.servlet.UploadServlet;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
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
        // Multipart aktivieren, damit der Upload-Endpunkt request.getParts() lesen kann.
        registration.setMultipartConfig(new MultipartConfigElement(
                null, 10L * 1024 * 1024, 12L * 1024 * 1024, 0));
    }

    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        super.onStartup(servletContext);
        servletContext.addFilter("securityHeaders", new SecurityHeadersFilter())
                .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
        // Schützt Selbsteintrag und Redaktion (Auth + Rollen + CSRF).
        servletContext.addFilter("auth", new AuthFilter())
                .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/api/me/*", "/api/admin/*");
        // Der Mandanten-/VK-Kontext kommt aus ConfigVk (Spring-konfiguriert, ein
        // VK je System) – kein Request-basierter Tenant-Filter noetig.

        // Velocity-sichere SPA-Shell am Kontext-Root ("" = exakt "/"); Assets bleiben
        // beim Default-Servlet. SPA nutzt Hash-Routing, daher genügt "/".
        ServletRegistration.Dynamic shell = servletContext.addServlet("shell", new ShellServlet());
        shell.setLoadOnStartup(2);
        shell.addMapping("");

        // Lokale Auslieferung hochgeladener Dateien (Standard-Upload-Service);
        // in Produktion mit externem Speicher/CDN i. d. R. nicht aktiv genutzt.
        ServletRegistration.Dynamic uploads = servletContext.addServlet("uploads", new UploadServlet());
        uploads.addMapping("/uploads/*");
    }
}
