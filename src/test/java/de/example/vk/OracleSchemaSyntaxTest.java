package de.example.vk;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Syntaktische Prüfung des Oracle-DDL (Thema #9 bleibt offen, der SQL-Syntax wird
 * aber abgesichert): Das produktive Oracle-Schema {@code V1__schema.sql} wird in eine
 * H2-Datenbank im <b>Oracle-Kompatibilitätsmodus</b> geladen. Schlägt eine Anweisung
 * fehl (Tippfehler, ungültige Typen, nicht auflösbare Fremdschlüssel/Spalten), bricht
 * der Test ab. Der Oracle-Text-Index ({@code V2}) ist CTXSYS-spezifisch und wird
 * strukturell (nicht ausführend) geprüft.
 */
public class OracleSchemaSyntaxTest {

    @Test
    public void oracleV1SchemaIsSyntacticallyValid() throws Exception {
        String raw = readResource("/db/oracle/V1__schema.sql");
        // Das Schema nutzt bewusst Oracle-native Typen, die H2 nicht implementiert.
        assertTrue("Oracle-Schema sollte WITH LOCAL TIME ZONE nutzen",
                raw.toUpperCase().contains("WITH LOCAL TIME ZONE"));
        assertTrue("Oracle-Schema sollte SYSTIMESTAMP nutzen",
                raw.toUpperCase().contains("SYSTIMESTAMP"));

        // Für die strukturelle/syntaktische H2-Prüfung diese Oracle-Only-Token auf das
        // H2-Äquivalent normalisieren (sie sind in Oracle gültig, in H2 nicht parsebar).
        String ddl = raw
                .replaceAll("(?i)WITH LOCAL TIME ZONE", "")
                .replaceAll("(?i)SYSTIMESTAMP", "CURRENT_TIMESTAMP");
        List<String> statements = splitStatements(ddl);
        assertTrue("V1 sollte mehrere DDL-Anweisungen enthalten", statements.size() > 20);

        // H2 im Oracle-Modus akzeptiert NUMBER/VARCHAR2/GENERATED ... IDENTITY usw. So
        // werden Tippfehler, ungültige Typen, doppelte Spalten und nicht auflösbare
        // Fremdschlüssel/Spalten erkannt.
        String url = "jdbc:h2:mem:oraclesyntax_" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=0;DATABASE_TO_UPPER=TRUE";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            int executed = 0;
            for (String stmt : statements) {
                try (Statement s = con.createStatement()) {
                    s.execute(stmt);
                    executed++;
                } catch (Exception e) {
                    fail("Oracle-DDL-Anweisung #" + (executed + 1) + " ungültig:\n"
                            + preview(stmt) + "\n-> " + e.getMessage());
                }
            }
            assertEquals(statements.size(), executed);
        }
    }

    @Test
    public void oracleTextIndexV2IsStructurallySound() throws Exception {
        String ddl = readResource("/db/oracle/V2__oracle_text.sql").toUpperCase();
        // CTXSYS.CONTEXT ist Oracle-Text-spezifisch und in H2 nicht ausführbar; daher
        // nur strukturelle Prüfung der wesentlichen Bestandteile.
        assertTrue("V2 sollte einen CONTEXT-Index anlegen", ddl.contains("INDEXTYPE IS CTXSYS.CONTEXT"));
        assertTrue("V2 sollte auf VK_EVENT(SEARCH_TEXT) zeigen",
                ddl.contains("VK_EVENT(SEARCH_TEXT)") || ddl.contains("VK_EVENT (SEARCH_TEXT)"));
        assertTrue("V2 sollte SYNC-Parameter setzen", ddl.contains("SYNC"));
        // Klammern müssen ausgewogen sein
        assertEquals("Unausgewogene Klammern in V2", count(ddl, '('), count(ddl, ')'));
    }

    @Test
    public void h2AndOracleSchemasCoverSameTables() throws Exception {
        List<String> oracle = tableNames(readResource("/db/oracle/V1__schema.sql"));
        List<String> h2 = tableNames(readResource("/db/h2/schema.sql"));
        assertFalse(oracle.isEmpty());
        for (String t : oracle) {
            assertTrue("Tabelle " + t + " fehlt im H2-Schema", h2.contains(t));
        }
        for (String t : h2) {
            assertTrue("Tabelle " + t + " fehlt im Oracle-Schema", oracle.contains(t));
        }
    }

    // ------------------------------------------------------------------

    private static List<String> splitStatements(String sql) {
        StringBuilder noComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            noComments.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        List<String> out = new ArrayList<String>();
        for (String part : noComments.toString().split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> tableNames(String sql) {
        List<String> out = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)CREATE\\s+TABLE\\s+([A-Z_]+)").matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toUpperCase());
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String preview(String stmt) {
        String s = stmt.trim().replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + " …" : s;
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = OracleSchemaSyntaxTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Ressource nicht gefunden: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }

    private static void assertEquals(String msg, int expected, int actual) {
        org.junit.Assert.assertEquals(msg, expected, actual);
    }
}
