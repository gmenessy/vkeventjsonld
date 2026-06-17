# VK Veranstaltungskalender — Gesamtauslieferung

| | |
|---|---|
| **Stand** | 2026-06-17 |
| **Commit** | `209bfba` (Branch `claude/busy-clarke-b3aplk`, identisch auf `main`) |
| **Artefakt** | `vk.war` (≈ 17 MB, Tomcat 9 / Servlet 3.1, `javax.*`) |
| **Build** | Java 8+ (`release 8`), Maven; 43 Tests grün |

Relationaler, redaktionell nutzbarer Veranstaltungskalender auf Basis von
`schema.org/Event` — intern normalisiert (Oracle 19c), nach außen leichte
JSON-APIs für eine Vanilla-JS-SPA plus JSON-LD. Mehrmandantenfähig, strikt
isoliert, optimiert auf schnelle Suche bei vielen Events.

---

## 1. Lieferumfang

**Öffentlicher Teil**
- Drei-gliedrige Oberfläche: Suche → Ergebnisübersicht → Detailansicht
- Schnelle Volltextsuche (denormalisierter Suchtext, tenant-führender Index,
  DB-seitige Pagination, INSTR-Standard / optional Oracle Text)
- `schema.org/Event`-JSON-LD je Event
- Velocity-sichere SPA-Shell (CSP-Nonce, escaptes Bootstrap-JSON)

**Mehrmandantenfähigkeit**
- Ein System = ein (Mandant, VK), serverseitig fix über `ConfigVk` (fail-closed)
- Jede Abfrage tenant-gescoped; Treiberindex führt mit `(MANDANT_ID, VK_ID, …)`

**Redaktion & Selbsteintrag**
- Auth/Login (Session, PBKDF2, CSRF), Rollen EDITOR/ADMIN
- Selbsteintrag + redaktioneller Workflow (Entwurf → Einreichen → Review →
  Freigeben/Änderungen → Veröffentlichen), Audit
- Editor-Cockpit: Live-Vorschau, vierstufige Qualitätsampel, Smart Hints, Autosave
- **Vollständige Eventpflege**: Einlass, Dauer (ISO-8601), Preise/Tickets,
  Mitwirkende & Sponsoren, Bilder & Dokumente (Bild erfordert Alt-Text, WCAG 1.1.1)
- **Datei-Upload** über `ZMUploadService` → absolute URL
- **Versions-Historie**: ansehen + wiederherstellen
- **Audit-Log-Einsicht** (tenant-gescoped, eigene Spalten)
- Import (CSV/JSON) und CSV-Export

**GenAI (Mehrwert, austauschbar, mit hartem Fallback)**
- Natürlichsprachige Suche → strukturierte Filter (regelbasiert, null Latenz)
- Editor-Assistenz: Alt-Text-Vorschlag, „Einfache Sprache", Kategorie-/Schlagwort-
  Vorschlag — Standard regelbasiert, optional Claude (`ANTHROPIC_API_KEY`)

**Barrierefreiheit & Sicherheit**
- WCAG 2.2-orientiert; CI-A11y-Gate (axe-core) über Liste, Detail, Editor, Redaktion
- CSP + Sicherheits-Header, HTML-Sanitizing, Caching-Strategie für Assets

---

## 2. Deployment

### Voraussetzungen
- Servlet-Container **Tomcat 9** (oder kompatibel, `javax.servlet`)
- **Oracle 19c** (Produktion) oder eingebettete **H2** (Dev/Demo)
- JDBC-Treiber im Container-Classpath (Oracle: `ojdbc`)

### Schritte
1. Schema einspielen: `src/main/resources/db/oracle/V1__schema.sql`
   (optional `V2__oracle_text.sql` für Oracle-Text-Volltextsuche).
2. `vk.war` als `vk.war` in `$CATALINA_BASE/webapps/` deployen.
3. Umgebungsvariablen setzen (siehe unten), Container starten.
4. Anwendung unter `http://<host>:8080/vk/` (Kontextpfad = WAR-Name).

### Konfiguration (Umgebungsvariablen)
| Variable | Default | Bedeutung |
|---|---|---|
| `VK_DB_MODE` | `h2` | `h2` (Dev) oder `oracle` |
| `VK_DB_URL` / `VK_DB_USER` / `VK_DB_PASSWORD` | – | Oracle-Zugang |
| `VK_MANDANT_ID` / `VK_VK_ID` | `1` / `1` | Mandant/VK dieses Systems |
| `VK_BASE_URL` | leer | Basis-URL für JSON-LD und Upload-URLs |
| `VK_SEARCH_ORACLE_TEXT` | `false` | Oracle-Text-Volltextsuche |
| `ANTHROPIC_API_KEY` | leer | Aktiviert Claude für die GenAI-Assistenz |
| `VK_GENAI_PROVIDER` / `VK_GENAI_MODEL` | `auto` / `claude-haiku-4-5` | GenAI-Anbieter/Modell |
| `VK_UPLOAD_DIR` | `<tmp>/vk-uploads` | Verzeichnis des Standard-Upload-Service |

> **Hinweis Upload:** Mitgeliefert ist eine lauffähige Standard-Implementierung
> von `ZMUploadService` (lokaler Datei-Store, Auslieferung über `/uploads/*`).
> In Produktion mit Objekt-Speicher/CDN wird diese Bean durch die umgebungseigene
> Implementierung ersetzt; die Signatur `uploadFile(HttpServletRequest)` mit
> absoluter Rückgabe-URL bleibt gleich.

### Mehrere Mandanten/VKs
Pro (Mandant, VK) eine eigene WAR-Instanz mit eigenen `VK_MANDANT_ID`/`VK_VK_ID`
deployen. Dasselbe WAR, unterschiedliche Konfiguration — strikte Isolation.

---

## 3. Qualität & Tests

- **43 automatisierte Tests** (JUnit), u. a. Mandanten-Isolation, Suchperformance
  (~200 ms bei 15.500 Demo-Events), Sicherheits-Utils, NL-/CSV-Parser,
  GenAI-Heuristik, Upload-Service.
- **`SqlOperationsIntegrationTest`** übt alle SQL-Operationen (Lebenszyklus mit
  allen Feldern, Workflow inkl. Versions-Snapshot, Export, Import, Audit-Isolation).
- **`OracleSchemaSyntaxTest`** prüft das Oracle-DDL syntaktisch (H2/Oracle-Modus)
  und die Tabellengleichheit beider Schemata.
- **CI** (`.github/workflows/ci.yml`): Build/Test + A11y-Gate (axe-core).

---

## 4. Offene Punkte (bewusst zurückgestellt)

- **Veranstaltungsserien/Wiederholungen** (`VK_EVENT_SERIES`) — Schema vorbereitet,
  Feature noch nicht verdrahtet.
- **Testcontainers/echtes Oracle in der CI** (Thema #9) — SQL-Syntax ist
  stattdessen über `OracleSchemaSyntaxTest` abgesichert.
- **Frontend-Modularisierung/Bundling** von `app.js` — Verbesserung, kein Fehler.

Reservierte, noch nicht verdrahtete Tabellen sind im Schema entsprechend
kommentiert (`VK_EVENT_SERIES`, `VK_EXPORT_JOB`, `VK_*_DETAILS`, `VK_USER_PARTY`).

---

## 5. Paketinhalt (`vk-auslieferung.zip`)

```
vk.war                        – deploybares Artefakt (Tomcat 9)
README.md                     – Projektdokumentation
AUSLIEFERUNG.md               – dieses Dokument
db/oracle/V1__schema.sql      – Oracle-19c-Schema + Indizes
db/oracle/V2__oracle_text.sql – optionaler Oracle-Text-Index
db/h2/schema.sql              – strukturgleiches H2-Schema (Dev)
```
