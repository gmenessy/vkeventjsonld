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

## Mandantenfähigkeit (Multi-Tenant)

Mehrere Mandanten, je Mandant mehrere Veranstaltungskalender (VK); jeder VK hält
typisch 1.000–20.000 Events. Mandant und VK sind **strikt isoliert**.

- **Ein System = ein (Mandant, VK).** Jedes System bringt seinen eigenen
  Spring-Kontext mit; der Tenant ist für die Laufzeit fix, nicht pro Request.
- Tenant-Kontext: `de.example.vk.util.ConfigVk` mit statischen `getMandant()` /
  `getVkId()` (beide `Long`). Gesetzt wird er beim Start durch
  `ConfigVkInitializer` aus der Spring-Konfiguration (`vk.mandantId` / `vk.vkId`
  bzw. `VK_MANDANT_ID` / `VK_VK_ID`, Default 1/1).
- **Jede** Abfrage filtert auf `MANDANT_ID` + `VK_ID`; ohne Kontext bricht sie ab
  (fail-closed). Der Treiberindex führt mit `(MANDANT_ID, VK_ID, …)`, sodass jeder
  VK ein zusammenhängender Indexbereich ist – Isolation und Tempo zugleich.
- Die Tenant-Bestimmung ist **serverseitig**; die SPA sendet keine Tenant-Daten
  und kann den Mandanten nicht beeinflussen. `mandant`/`vk` als Request-Parameter
  werden ignoriert.
- Datenzuordnung: Events, Orte und Veranstalter gehören zu einem VK; die
  Kategorie-Taxonomie und Keywords sind global, die Event-Zählung ist gescoped.
- `GET /api/context` liefert Mandant/VK + Anzeigename des Systems; die SPA zeigt
  diesen Namen in der Kopfzeile.

Der Isolationstest (`EventSearchIntegrationTest`) belegt: ein Event aus VK 1 ist
unter VK 3 nicht abrufbar, und ohne Kontext schlägt jede Suche fehl. Live
verifiziert: dasselbe WAR liefert mit `-Dvk.mandantId=2 -Dvk.vkId=3` einen
anderen, isolierten Kalender.

## CI & Accessibility-Gate

`.github/workflows/ci.yml` läuft bei Push/PR und hat zwei Jobs:

- **build-test:** `mvn test` (JUnit, inkl. Mandanten-Isolation, Suchperformance,
  Sicherheits-Utils, NL-Parser, CSV-Parser).
- **a11y:** startet die App (Jetty mit H2-Demodaten) und scannt Listen- und
  Detailansicht mit **axe-core** (Playwright/Chromium) gegen WCAG 2.1 A/AA. Der
  Build schlägt fehl, sobald ein Verstoß der Schwere *serious* oder *critical*
  auftritt – so kann die in den A11y-Sprints erreichte Barrierefreiheit nicht
  unbemerkt regredieren. Harness unter `a11y/` (`npm install` + `axe-scan.mjs`).

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
| `VK_MANDANT_ID` | `1` | Mandant dieses Systems (`vk.mandantId`) |
| `VK_VK_ID` | `1` | VK dieses Systems (`vk.vkId`) |
| `VK_BASE_URL` | leer | Basis-URL für JSON-LD `@id`/`url` |
| `VK_SEARCH_ORACLE_TEXT` | `false` | Oracle-Text-Volltextsuche aktivieren |
| `ANTHROPIC_API_KEY` | leer | Aktiviert Claude für die Redaktions-Assistenz (sonst Heuristik) |
| `VK_GENAI_PROVIDER` | `auto` | `auto` (Claude wenn Key da), `heuristik` (erzwingt regelbasiert) |
| `VK_GENAI_MODEL` | `claude-haiku-4-5` | Claude-Modell für die Assistenz |
| `VK_UPLOAD_DIR` | `<tmp>/vk-uploads` | Ablageverzeichnis des Standard-Upload-Service |
| `vk.upload.maxBytes` | `10485760` | Maximale Upload-Größe (Bytes) |

## Öffentliche API

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/events` | Suche/Liste (Parameter: `from,to,q,category,place,organizer,attendanceMode,free,page,size,sort`) |
| GET | `/api/events/{id}` | Eventdetail |
| GET | `/api/events/{id}/jsonld` | schema.org/Event als JSON-LD |
| GET | `/api/categories` | Kategoriebaum inkl. Event-Zähler |
| GET | `/api/places?q=` | Ort-Autocomplete |
| GET | `/api/organizers?q=` | Veranstalter-Autocomplete |
| GET | `/api/context` | Aktueller Mandant/VK + wählbare VKs |
| POST/GET | `/api/admin/imports` | CSV-/JSON-Import (Redaktion): Job anlegen, Jobs listen |
| GET | `/api/admin/imports/{id}` | Import-Job mit Zeilen-Ergebnissen |
| GET | `/api/admin/export/events?format=csv` | CSV-Export (Redaktion) |
| POST | `/api/me/assist/alt-text` | GenAI: Bild-Alt-Text-Vorschlag (angemeldet) |
| POST | `/api/me/assist/simplify` | GenAI: „Einfache Sprache" (angemeldet) |
| POST | `/api/me/assist/suggest` | GenAI: Kategorie- & Schlagwort-Vorschlag (angemeldet) |
| POST | `/api/me/uploads` | Datei-Upload (Bild/Dokument) → absolute URL (angemeldet) |

Alle Endpunkte sind mandantengescoped; der Kontext kommt aus `ConfigVk`
(serverseitig, je System konfiguriert), nicht aus dem Request.

Einheitliches Antwortformat:
`{ "success": true, "data": …, "messages": [], "errors": [], "meta": { page, size, total } }`

## Datenbank

- `src/main/resources/db/oracle/V1__schema.sql` – Oracle-19c-Schema + Indizes
- `src/main/resources/db/oracle/V2__oracle_text.sql` – optionaler Volltextindex
- `src/main/resources/db/h2/schema.sql` – strukturgleiches H2-Schema (Dev)

## SPA-Shell (Velocity-sicher)

Der Kontext-Root (`/`) wird über ein Velocity-Template (`templates/index.vm`,
`ShellServlet`) ausgeliefert statt als statische Datei (Spezifikation 12/25):

- pro Request ein **CSP-Nonce** (gesetzt im `SecurityHeadersFilter`, identisch in
  Header und `<script nonce>`),
- **sicher escaptes Bootstrap-JSON** (`$esc.html`) als nicht-ausführbare
  JSON-Insel (`<script type="application/json">`), die die SPA beim Start liest,
- statische Assets (CSS/JS) liefert weiterhin der Default-Servlet des Containers.

## Sicherheit / Barrierefreiheit

- Content-Security-Policy & Sicherheits-Header (`SecurityHeadersFilter`),
  alle Skripte extern (`script-src 'self'`).
- Frontend rendert Nutzerdaten DOM-sicher (`textContent`); Rich-Text wird gegen
  eine Tag-Allowlist gefiltert.
- WCAG-orientiert: Skip-Link, sichtbarer Fokus, Tastaturbedienung,
  44px-Touch-Targets, `prefers-reduced-motion`/`-transparency`/`-contrast`.
- UX/Performance (Sprint 3): „Beste Treffer"-Relevanzsortierung (Titel höher
  gewichtet), entfernbare Aktive-Filter-Chips, Datums-Presets (Heute/Wochenende/
  Monat), Detail-Bilder mit `aspect-ratio` gegen CLS, JSON-LD client-seitig (kein
  zweiter Request), `Cache-Control` für den Kategoriebaum.
- GenAI-Quick-Win (Sprint 4): natürlichsprachige Suche → strukturierte Filter
  (`GET /api/search/parse`, „✨"-Button). Regelbasiert (deutsch), **null Latenz/
  Kosten** im Hot-Path und zugleich harter Fallback; ein LLM-Parser (z. B. Claude
  Haiku) kann `NlQueryParser.parse` later als Drop-in ersetzen.
- GenAI-Redaktions-Assistenz (Schreibzeit, **nie** im Such-Hot-Path):
  **Alt-Text-Vorschlag** (WCAG 1.1.1) und **„Einfache Sprache"** im Editor
  (`POST /api/me/assist/alt-text`, `…/simplify`). Austauschbarer Anbieter
  (`GenAiProvider`): Standard ist eine regelbasierte, kostenfreie Heuristik;
  mit gesetztem `ANTHROPIC_API_KEY` übernimmt Claude mit **hartem Fallback** auf
  die Heuristik (`FallbackGenAiProvider`) – der Editor bleibt bei Modellausfall/
  Timeout funktionsfähig.
- GenAI-Editor-Vorschläge: **Kategorie & Schlagworte** aus Titel/Beschreibung
  (`POST /api/me/assist/suggest`, „✨"-Button im Cockpit). Regelbasiert
  (`EditorSuggestService`, derselbe Parser wie die NL-Suche) – null Latenz/Kosten,
  füllt nur leere Felder bzw. ergänzt Schlagworte und überschreibt nichts.
- A11y-Härtung (Sprint 2): Ort-/Veranstalter-Autocomplete als vollwertiges
  ARIA-1.2-Combobox (Pfeiltasten, `aria-activedescendant`, Enter/Escape, Home/End);
  Filter als gelabelte Toggle-Gruppen (`role="group"`); mobiler Filter-Drawer als
  Dialog (`role="dialog"`, `aria-modal`, Fokus-Trap, Fokus-Restore, Schließen-Button).
- A11y-Härtung (Sprint 1): genau eine `h1` + saubere Überschriften-/Landmark-
  Hierarchie; **kein Fokusverlust** beim Filtern (Toggles aktualisieren in-place);
  **eine** Status-Live-Region (Trefferzahl), Kartenliste selbst nicht live, Toast
  `polite`; Fokus wandert beim Öffnen ins Detail (Überschrift) und beim Schließen
  zurück zur Karte; `scroll-margin` gegen „Focus Not Obscured" (WCAG 2.2 / 2.4.11);
  `aria-busy` während der Suche.

## Redaktion & Selbsteintrag (umgesetzt)

Aufbauend auf dem öffentlichen Teil sind inzwischen umgesetzt:

- **Auth/Login** (Session-basiert, PBKDF2-Passwörter, CSRF-Token bei Schreib-
  zugriffen) mit `AuthFilter` für `/api/me/*` (angemeldet) und `/api/admin/*`
  (Rolle EDITOR/ADMIN).
- **Selbsteintrag** für angemeldete Nutzer und **redaktioneller Workflow**
  (Entwurf → Einreichen → Review-Queue → Freigeben/Änderungen erbitten →
  Veröffentlichen), inkl. Audit.
- **Editor-Cockpit** (Spec Kap. 19): Live-Vorschau, vierstufige Qualitätsampel,
  regelbasierte Smart Hints und lokales Autosave mit Wiederherstellen.
- **GenAI-Assistenz** im Editor: Alt-Text-Vorschlag und „Einfache Sprache"
  (siehe oben), austauschbarer Anbieter mit hartem Fallback.
- **Vollständige Eventpflege** im Editor, Selbsteintrag und Import: Einlasszeit,
  Dauer (ISO-8601), Preise/Tickets (VK_OFFER), Mitwirkende & Sponsoren
  (VK_PARTY/VK_EVENT_PARTY_ROLE), Bilder und Dokumente (VK_ASSET). Bilder
  erfordern serverseitig einen Alternativtext (WCAG 1.1.1).
- **Datei-Upload** über `ZMUploadService` (`uploadFile(HttpServletRequest)` →
  absolute URL). Mitgeliefert ist eine lauffähige Standard-Implementierung
  (lokaler Datei-Store, Auslieferung über `/uploads/*`); in Produktion durch eine
  umgebungseigene Implementierung (Objekt-Speicher/CDN) ersetzbar.
- **Import/Export** (CSV/JSON) im Redaktionsbereich. CSV unterstützt zusätzlich die
  Flachspalten `doorTime`, `durationIso`, `price`, `performerNames`/`sponsorNames`
  (mit `;` getrennt), `imageUrl`/`imageAlt`, `documentUrl`/`documentName`; JSON
  unterstützt verschachtelte Arrays `offers`/`performers`/`sponsors`/`images`/`documents`.

## SQL-Qualitätssicherung

- `OracleSchemaSyntaxTest`: lädt das produktive Oracle-DDL (`V1__schema.sql`) in
  H2 im **Oracle-Kompatibilitätsmodus** und prüft so jede Anweisung syntaktisch
  (Oracle-native Token wie `TIMESTAMP WITH LOCAL TIME ZONE`/`SYSTIMESTAMP` werden
  für die H2-Prüfung normalisiert); prüft außerdem Tabellengleichheit zwischen
  Oracle- und H2-Schema sowie die Struktur des Oracle-Text-Index (`V2`).
- `SqlOperationsIntegrationTest`: übt **alle SQL-Operationen** der
  Repositories/Services gegen H2 (Anlage mit allen Feldern, Wiederlesen,
  Beziehungen ersetzen, Selbsteintrags- und Redaktions-Workflow inkl.
  Versions-Snapshot, Export, CSV-/JSON-Import, Audit, Kategorie-Baum).

## Noch nicht umgesetzt (Spezifikation, spätere Sprints)

Eine **Versions-Ansicht/Wiederherstellung** im UI fehlt noch (Snapshots werden
beim Veröffentlichen geschrieben, sind aber noch nicht einsehbar). Ebenfalls offen
(bewusst zurückgestellt): Veranstaltungsserien, Audit-Log-Einsicht und
Testcontainers/echtes Oracle in der CI (Thema #9 – der SQL-Syntax wird stattdessen
wie oben beschrieben gegen H2/Oracle-Modus geprüft).
