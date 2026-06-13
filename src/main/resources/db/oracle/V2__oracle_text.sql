-- Optional: Oracle Text Volltextindex fuer SEARCH_TEXT (Spezifikation Kapitel 10).
-- Voraussetzung: CTXSYS installiert und CTXAPP-Rolle fuer das Schema.
-- Wird dieser Index angelegt, kann die Anwendung mit vk.search.oracleText=true
-- auf CONTAINS()-Suche umgestellt werden (deutlich schneller bei sehr grossen Datenmengen).

CREATE INDEX IDX_EVENT_TEXT
ON VK_EVENT(SEARCH_TEXT)
INDEXTYPE IS CTXSYS.CONTEXT
PARAMETERS ('SYNC (ON COMMIT)');
