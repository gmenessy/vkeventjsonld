package de.example.vk;

import com.google.gson.JsonObject;
import de.example.vk.service.NlQueryParser;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NlQueryParserTest {

    private final NlQueryParser parser = new NlQueryParser();
    private final Set<String> slugs = new HashSet<String>(Arrays.asList(
            "musik", "theater", "ausstellung", "sport", "fussball", "radsport",
            "vortrag", "kurs", "workshop", "kinder-familie", "bildung"));

    @Test
    public void extractsFreeCategoryAndWeekend() {
        JsonObject r = parser.parse("kostenlose kinderkonzerte am wochenende", slugs);
        assertTrue(r.get("free").getAsBoolean());
        // Kompositum „kinderkonzerte" enthält „konzert" -> musik
        assertEquals("musik", r.get("category").getAsString());
        assertTrue("Wochenende sollte ein from setzen", r.has("from") && !r.get("from").isJsonNull());
    }

    @Test
    public void mapsKonzertToMusic() {
        JsonObject r = parser.parse("konzert online", slugs);
        assertEquals("musik", r.get("category").getAsString());
        assertEquals("ONLINE", r.get("attendanceMode").getAsString());
    }

    @Test
    public void todaySetsBothDates() {
        JsonObject r = parser.parse("theater heute", slugs);
        String today = LocalDate.now().toString();
        assertEquals(today, r.get("from").getAsString());
        assertEquals(today, r.get("to").getAsString());
        assertEquals("theater", r.get("category").getAsString());
    }

    @Test
    public void keepsResidualAsQuery() {
        JsonObject r = parser.parse("flohmarkt", slugs);
        assertEquals("flohmarkt", r.get("q").getAsString());
        assertFalse(r.has("category"));
    }

    @Test
    public void emptyInputIsSafe() {
        JsonObject r = parser.parse("", slugs);
        assertEquals("", r.get("q").getAsString());
    }
}
