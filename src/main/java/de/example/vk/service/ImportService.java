package de.example.vk.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.example.vk.config.GsonFactory;
import de.example.vk.repository.ApprovalRepository;
import de.example.vk.repository.EventWriteRepository;
import de.example.vk.repository.EventWriteRepository.EventInput;
import de.example.vk.repository.ImportRepository;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.CurrentUser;
import de.example.vk.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Import-Pipeline (Spezifikation 27): Parsen (CSV/JSON) → Normalisieren → Mapping →
 * Validieren → Dublettenprüfung → Anlage als Entwurf (oder Einreichung). Jede Zeile
 * läuft eigenständig (eine fehlerhafte Zeile bricht den Import nicht ab) und wird als
 * VK_IMPORT_ITEM protokolliert.
 */
@Service
public class ImportService {

    private static final int MAX_ROWS = 5000;

    private final ImportRepository importRepository;
    private final EventWriteRepository writeRepository;
    private final ApprovalRepository approvalRepository;
    private final EventInputMapper mapper;
    private final EventValidator validator;
    private final AuditService auditService;
    private final Gson gson = GsonFactory.create();

    public ImportService(ImportRepository importRepository, EventWriteRepository writeRepository,
                         ApprovalRepository approvalRepository, EventInputMapper mapper,
                         EventValidator validator, AuditService auditService) {
        this.importRepository = importRepository;
        this.writeRepository = writeRepository;
        this.approvalRepository = approvalRepository;
        this.mapper = mapper;
        this.validator = validator;
        this.auditService = auditService;
    }

    /**
     * @param format "csv" oder "json"
     * @param asStatus "DRAFT" (Standard) oder "SUBMITTED"
     */
    public JsonObject importData(String format, String content, String fileName, String asStatus) {
        long userId = CurrentUser.requireUserId();
        String fmt = format == null ? "csv" : format.trim().toLowerCase();
        boolean submit = "SUBMITTED".equalsIgnoreCase(asStatus);

        List<JsonObject> rows;
        if ("json".equals(fmt)) {
            rows = parseJson(content);
        } else if ("csv".equals(fmt)) {
            rows = parseCsv(content);
        } else {
            throw new ValidationException("format", "Nur format=csv oder format=json wird unterstützt.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ValidationException("rows", "Zu viele Zeilen (max. " + MAX_ROWS + ").");
        }

        String jobPublicId = UUID.randomUUID().toString();
        long jobId = importRepository.createJob(fmt.toUpperCase(), fileName, jobPublicId, userId);

        int imported = 0, skipped = 0, errors = 0, rowNo = 0;
        for (JsonObject row : rows) {
            rowNo++;
            try {
                // Ort per Name auflösen, falls keine place-ID angegeben.
                if (Json.optString(row, "place") == null) {
                    String placeName = Json.optString(row, "placeName");
                    if (placeName != null) {
                        String pid = importRepository.resolvePlacePublicIdByName(placeName);
                        if (pid != null) {
                            row.addProperty("place", pid);
                        }
                    }
                }
                EventInput in = mapper.fromJson(row);
                validator.validate(in);
                if (importRepository.duplicateExists(in.title, in.startAt)) {
                    importRepository.addItem(jobId, rowNo, "SKIPPED_DUPLICATE", in.title, null, null,
                            "Bereits vorhanden (Titel + Startzeit).");
                    skipped++;
                    continue;
                }
                String pub = writeRepository.createDraft(in, userId);
                Long eventId = importRepository.eventIdByPublicId(pub);
                if (submit && eventId != null) {
                    writeRepository.setWorkflowStatus(eventId, "SUBMITTED");
                    approvalRepository.insertSubmission(eventId, userId);
                }
                importRepository.addItem(jobId, rowNo, "IMPORTED", pub, eventId, null, null);
                imported++;
            } catch (ValidationException ve) {
                importRepository.addItem(jobId, rowNo, "ERROR", null, null, null,
                        (ve.getField() == null ? "" : ve.getField() + ": ") + ve.getMessage());
                errors++;
            } catch (Exception e) {
                importRepository.addItem(jobId, rowNo, "ERROR", null, null, null, e.getMessage());
                errors++;
            }
        }
        importRepository.finishJob(jobId, errors == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        auditService.log(userId, "IMPORT_EVENT", "IMPORT_JOB", jobId,
                "imported=" + imported + ", skipped=" + skipped + ", errors=" + errors);

        JsonObject result = importRepository.getJob(jobPublicId);
        JsonObject summary = new JsonObject();
        summary.addProperty("total", rows.size());
        summary.addProperty("imported", imported);
        summary.addProperty("skipped", skipped);
        summary.addProperty("errors", errors);
        result.add("summary", summary);
        return result;
    }

    public JsonArray listJobs() {
        return importRepository.listJobs();
    }

    public JsonObject getJob(String publicId) {
        JsonObject job = importRepository.getJob(publicId);
        if (job == null) {
            throw new NotFoundException("Import-Job nicht gefunden: " + publicId);
        }
        return job;
    }

    // ------------------------------------------------------------------

    private List<JsonObject> parseJson(String content) {
        List<JsonObject> rows = new ArrayList<JsonObject>();
        try {
            JsonElement root = com.google.gson.JsonParser.parseString(content);
            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("events")) {
                arr = root.getAsJsonObject().getAsJsonArray("events");
            } else {
                arr = new JsonArray();
                arr.add(root);
            }
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    rows.add(el.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            throw new ValidationException("content", "JSON konnte nicht gelesen werden: " + e.getMessage());
        }
        return rows;
    }

    private static final java.util.Set<String> BOOLEAN_COLS =
            new java.util.HashSet<String>(java.util.Arrays.asList("isfree", "isaccessibleforfree", "free"));

    private List<JsonObject> parseCsv(String content) {
        List<List<String>> table = csvToTable(content);
        List<JsonObject> rows = new ArrayList<JsonObject>();
        if (table.isEmpty()) {
            return rows;
        }
        List<String> header = table.get(0);
        for (int r = 1; r < table.size(); r++) {
            List<String> cols = table.get(r);
            if (cols.size() == 1 && cols.get(0).trim().isEmpty()) {
                continue; // Leerzeile
            }
            JsonObject row = new JsonObject();
            for (int c = 0; c < header.size() && c < cols.size(); c++) {
                String key = header.get(c).trim();
                String val = cols.get(c);
                if (key.isEmpty()) {
                    continue;
                }
                String lower = key.toLowerCase();
                if (BOOLEAN_COLS.contains(lower)) {
                    row.addProperty("isAccessibleForFree", isTruthy(val));
                } else {
                    row.addProperty(key, val);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static boolean isTruthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("ja") || s.equals("y") || s.equals("yes");
    }

    /** RFC-4180-naher CSV-Parser (Quotes, doppelte Quotes, Komma/Zeilenumbruch in Feldern). */
    static List<List<String>> csvToTable(String content) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> cur = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content == null ? 0 : content.length();
        while (i < n) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    field.append(ch);
                    i++;
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                    i++;
                } else if (ch == ',') {
                    cur.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (ch == '\r') {
                    i++;
                } else if (ch == '\n') {
                    cur.add(field.toString());
                    field.setLength(0);
                    rows.add(cur);
                    cur = new ArrayList<String>();
                    i++;
                } else {
                    field.append(ch);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            rows.add(cur);
        }
        return rows;
    }
}
