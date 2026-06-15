package de.example.vk.servlet;

import com.google.gson.JsonObject;
import de.example.vk.config.GsonFactory;
import de.example.vk.util.ConfigVk;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.tools.generic.EscapeTool;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

/**
 * Liefert die SPA-Shell über ein Velocity-Template aus (Spezifikation 12/25):
 * sicher escaptes Bootstrap-JSON ({@code $esc.html}) und ein CSP-Nonce. Statische
 * Assets (CSS/JS) liefert weiterhin der Default-Servlet des Containers.
 *
 * <p>Gemappt auf den Kontext-Root; Deep-Links der SPA laufen über Hash-Routing,
 * daher genügt das Rendern von „/".</p>
 */
public class ShellServlet extends HttpServlet {

    private VelocityEngine engine;
    private final EscapeTool esc = new EscapeTool();

    @Override
    public void init() {
        Properties p = new Properties();
        p.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class");
        p.setProperty("resource.loader.class.class",
                "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        p.setProperty(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        engine = new VelocityEngine(p);
        engine.init();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Object nonce = req.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE);

        JsonObject bootstrap = new JsonObject();
        bootstrap.addProperty("mandant", ConfigVk.getMandant());
        bootstrap.addProperty("vk", ConfigVk.getVkId());

        VelocityContext ctx = new VelocityContext();
        ctx.put("esc", esc);
        ctx.put("nonce", nonce == null ? "" : nonce.toString());
        ctx.put("bootstrapJson", GsonFactory.create().toJson(bootstrap));

        resp.setContentType("text/html;charset=UTF-8");
        Template template = engine.getTemplate("templates/index.vm", "UTF-8");
        try (PrintWriter writer = resp.getWriter()) {
            template.merge(ctx, writer);
        }
    }
}
