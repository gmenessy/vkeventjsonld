package de.example.vk.service;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** Prüft den handgeschriebenen CSV-Parser (Quotes, Kommas/Zeilenumbrüche in Feldern). */
public class ImportCsvTest {

    @Test
    public void parsesQuotedFieldsWithCommasAndNewlines() {
        String csv = "title,note\n"
                + "\"Konzert, groß\",\"Zeile1\nZeile2\"\n"
                + "Einfach,\"Er sagte \"\"Hallo\"\"\"\n";
        List<List<String>> table = ImportService.csvToTable(csv);
        assertEquals(3, table.size());
        assertEquals("title", table.get(0).get(0));
        assertEquals("Konzert, groß", table.get(1).get(0));   // Komma im Feld
        assertEquals("Zeile1\nZeile2", table.get(1).get(1));  // Zeilenumbruch im Feld
        assertEquals("Er sagte \"Hallo\"", table.get(2).get(1)); // doppelte Quotes
    }

    @Test
    public void plainRows() {
        List<List<String>> table = ImportService.csvToTable("a,b,c\n1,2,3");
        assertEquals(2, table.size());
        assertEquals(3, table.get(1).size());
        assertEquals("3", table.get(1).get(2));
    }
}
