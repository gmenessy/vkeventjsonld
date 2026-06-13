# VK – Veranstaltungskalender

Relationaler, redaktionell nutzbarer Veranstaltungskalender auf Basis von
`schema.org/Event`. Intern relational normalisiert (Oracle 19c), nach außen
leichte JSON-APIs für eine Vanilla-JS-SPA sowie `schema.org/Event`-JSON-LD.

Umgesetzt ist der **öffentliche Teil** der Spezifikation: die drei-gliedrige
Oberfläche aus **Suche → Ergebnisübersicht → Detailansicht**, optimiert auf
schnelle Suche auch bei sehr vielen Events.

## Architektur (leichtgewichtig, ohne DTO-Schicht)

```
Browser-SPA (Vanilla JS, Fetch, Hash-Router)
  → /api/*  (Spring MVC DispatcherServlet)
  → Controller  (geben Gson-JsonObject direkt zurück)
  → Service / Repository  (Spring JDBC)
  → Oracle 19c  bzw.  H2 (Dev/Demo)
```

Bewusste Entscheidungen:

- **Keine DTO-Klassen.** Antworten werden direkt als Gson `JsonObject`/`JsonArray`
  aus den DB-Zeilen gebaut (`de.example.vk.util.Json`). Das hält das System klein.
- **Spring JDBC statt JPA**, weil das Modell stark relational und Oracle-nah ist.
- **Vanilla-Frontend**, kein Framework, Material-Design-3-Optik mit
  zurückhaltendem Liquid-Glass.

## Schnelle Suche bei vielen Events

Das war die zentrale Anforderung. Maßnahmen:

1. **Denormalisierter Suchtext** (`VK_EVENT.SEARCH_TEXT`): Titel, Kurzbeschreibung,
   Ort, Stadt, Veranstalter, Kategorien und Schlagworte werden beim Schreiben
   klein-normalisiert abgelegt. Die Volltextsuche prüft nur **eine** Spalte –
   keine Joins über Keyword-/Kategorie-/Party-Tabellen zur Suchzeit.
2. **Schlanke Listenabfrage**: lädt nur Listenspalten, **kein** `DESCRIPTION`-CLOB.
3. **DB-seitige Pagination** (`OFFSET/FETCH`, Oracle 12c+ und H2).
4. **Treiberindex** `IDX_EVENT_PUB_START (WORKFLOW_STATUS, START_AT, ID)`.
5. **Optional Oracle Text** (`CONTAINS`) statt `INSTR` für sehr große Datenmengen
   (`vk.search.oracleText=true`, Index siehe `V2__oracle_text.sql`).
6. **Frontend**: debounced Suche (250 ms) und **`AbortController`**, der jede
   noch laufende Suchanfrage beim nächsten Tastendruck abbricht – keine
   veralteten Ergebnisse, keine unnötige Last.

Messung (Integrationstest, H2, ~12.000 Events): Ø **~58 ms** pro
`count + Seite` – deutlich unter dem Spezifikationsziel von 500 ms.

## Bauen, Testen, Starten

Voraussetzung: Java 8+ (gebaut mit `release 8`), Maven.

```bash
# Tests (startet eingebettete H2 mit ~12.000 Demo-Events)
mvn test

# Dev-Server (H2, automatisch befüllt) auf http://localhost:8080
mvn org.eclipse.jetty:jetty-maven-plugin:9.4.57.v20241219:run

# WAR für Tomcat o. Ä.
mvn package      # → target/vk.war
```

Im Dev-Modus (`VK_DB_MODE=h2`, Standard) legt `DevDataInitializer` Schema und
Demo-Datensatz beim Start automatisch an.

## Konfiguration (Umgebungsvariablen)

| Variable | Default | Bedeutung |
|---|---|---|
| `VK_DB_MODE` | `h2` | `h2` (Dev/Demo, eingebettet) oder `oracle` |
| `VK_DB_URL` | – | JDBC-URL der Oracle-DB (bei `oracle`) |
| `VK_DB_USER` / `VK_DB_PASSWORD` | – | Oracle-Zugangsdaten |
| `VK_BASE_URL` | leer | Basis-URL für JSON-LD `@id`/`url` |
| `VK_SEARCH_ORACLE_TEXT` | `false` | Oracle-Text-Volltextsuche aktivieren |

## Öffentliche API

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/events` | Suche/Liste (Parameter: `from,to,q,category,place,organizer,attendanceMode,free,page,size,sort`) |
| GET | `/api/events/{id}` | Eventdetail |
| GET | `/api/events/{id}/jsonld` | schema.org/Event als JSON-LD |
| GET | `/api/categories` | Kategoriebaum inkl. Event-Zähler |
| GET | `/api/places?q=` | Ort-Autocomplete |
| GET | `/api/organizers?q=` | Veranstalter-Autocomplete |

Einheitliches Antwortformat:
`{ "success": true, "data": …, "messages": [], "errors": [], "meta": { page, size, total } }`

## Datenbank

- `src/main/resources/db/oracle/V1__schema.sql` – Oracle-19c-Schema + Indizes
- `src/main/resources/db/oracle/V2__oracle_text.sql` – optionaler Volltextindex
- `src/main/resources/db/h2/schema.sql` – strukturgleiches H2-Schema (Dev)

## Sicherheit / Barrierefreiheit

- Content-Security-Policy & Sicherheits-Header (`SecurityHeadersFilter`),
  alle Skripte extern (`script-src 'self'`).
- Frontend rendert Nutzerdaten DOM-sicher (`textContent`); Rich-Text wird gegen
  eine Tag-Allowlist gefiltert.
- WCAG-orientiert: Skip-Link, sichtbarer Fokus, Tastaturbedienung, `aria-live`,
  44px-Touch-Targets, `prefers-reduced-motion` und `prefers-reduced-transparency`.

## Noch nicht umgesetzt (Spezifikation, spätere Sprints)

Selbsteintrag, redaktioneller Editor, Auth/Login, Import/Export und
Versionierung sind in der Spezifikation beschrieben, aber noch nicht Teil dieser
Implementierung. Schema und API-Struktur sind darauf vorbereitet.
