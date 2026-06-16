package de.example.vk.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.example.vk.util.ConfigVk;
import de.example.vk.util.Json;
import de.example.vk.util.SearchTextUtil;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Schreibender Zugriff auf Events (Selbsteintrag/Redaktion), tenant- und besitzergescoped. */
@Repository
public class EventWriteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public EventWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Eingabewerte für Anlage/Änderung (kein API-DTO). */
    public static final class EventInput {
        public String schemaType = "Event";
        public String title;
        public String shortDescription;
        public String descriptionHtml; // bereits sanitisiert
        public Timestamp startAt;
        public Timestamp endAt;
        public Timestamp doorTime;     // Einlass
        public String durationIso;     // ISO-8601-Dauer, z. B. PT2H30M
        public String attendanceMode = "OFFLINE";
        public boolean accessibleForFree;
        public String categorySlug;
        public String placePublicId;   // bestehender Ort
        public String virtualUrl;      // bei ONLINE/MIXED
        public String organizerName;
        public String organizerEmail;
        public List<String> keywords;
        public List<Offer> offers = new java.util.ArrayList<Offer>();
        public List<PartyRef> performers = new java.util.ArrayList<PartyRef>();
        public List<PartyRef> sponsors = new java.util.ArrayList<PartyRef>();
        public List<AssetRef> images = new java.util.ArrayList<AssetRef>();
        public List<AssetRef> documents = new java.util.ArrayList<AssetRef>();
    }

    /** Preis-/Angebotszeile (VK_OFFER). */
    public static final class Offer {
        public String name;
        public java.math.BigDecimal price;
        public String priceCurrency = "EUR";
        public String url;
        public String availability;
    }

    /** Mitwirkende(r)/Sponsor (VK_PARTY + VK_EVENT_PARTY_ROLE), Freitext-Name. */
    public static final class PartyRef {
        public String displayName;
        public String email;
        public String url;
    }

    /** Bild- oder Dokument-Asset (VK_ASSET); URL stammt aus dem Upload-Service. */
    public static final class AssetRef {
        public String url;
        public String fileName;
        public String mimeType;
        public String altText;       // nur Bilder
        public String copyrightText; // nur Bilder
    }

    @Transactional
    public String createDraft(EventInput in, long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        String publicId = UUID.randomUUID().toString();

        Long placeId = resolvePlaceId(in.placePublicId);
        Long vlocId = createVirtualLocation(in.virtualUrl);

        KeyHolder kh = new GeneratedKeyHolder();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("pid", publicId).addValue("mandant", mandant).addValue("vk", vk)
                .addValue("type", in.schemaType).addValue("title", in.title)
                .addValue("shortDesc", in.shortDescription).addValue("desc", in.descriptionHtml)
                .addValue("startAt", in.startAt).addValue("endAt", in.endAt)
                .addValue("doorTime", in.doorTime).addValue("durationIso", emptyToNull(in.durationIso))
                .addValue("mode", in.attendanceMode)
                .addValue("free", in.accessibleForFree ? "Y" : "N")
                .addValue("placeId", placeId).addValue("vlocId", vlocId)
                .addValue("search", buildSearchText(in))
                .addValue("createdBy", userId);
        jdbc.update("INSERT INTO VK_EVENT (PUBLIC_ID, MANDANT_ID, VK_ID, SCHEMA_TYPE, TITLE, SHORT_DESCRIPTION, "
                + "DESCRIPTION, START_AT, END_AT, DOOR_TIME, DURATION_ISO, ATTENDANCE_MODE, EVENT_STATUS, "
                + "WORKFLOW_STATUS, IS_ACCESSIBLE_FOR_FREE, PLACE_ID, VIRTUAL_LOCATION_ID, SEARCH_TEXT, "
                + "CREATED_BY, CREATED_AT) "
                + "VALUES (:pid, :mandant, :vk, :type, :title, :shortDesc, :desc, :startAt, :endAt, :doorTime, "
                + ":durationIso, :mode, 'EventScheduled', 'DRAFT', :free, :placeId, :vlocId, :search, "
                + ":createdBy, CURRENT_TIMESTAMP)",
                p, kh, new String[]{"ID"});
        long eventId = kh.getKey().longValue();
        writeRelations(eventId, in, mandant, vk);
        return publicId;
    }

    @Transactional
    public void updateDraft(long eventId, EventInput in, long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        Long placeId = resolvePlaceId(in.placePublicId);
        Long vlocId = createVirtualLocation(in.virtualUrl);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("type", in.schemaType).addValue("title", in.title)
                .addValue("shortDesc", in.shortDescription).addValue("desc", in.descriptionHtml)
                .addValue("startAt", in.startAt).addValue("endAt", in.endAt)
                .addValue("doorTime", in.doorTime).addValue("durationIso", emptyToNull(in.durationIso))
                .addValue("mode", in.attendanceMode)
                .addValue("free", in.accessibleForFree ? "Y" : "N")
                .addValue("placeId", placeId).addValue("vlocId", vlocId)
                .addValue("search", buildSearchText(in))
                .addValue("updatedBy", userId);
        jdbc.update("UPDATE VK_EVENT SET SCHEMA_TYPE=:type, TITLE=:title, SHORT_DESCRIPTION=:shortDesc, "
                + "DESCRIPTION=:desc, START_AT=:startAt, END_AT=:endAt, DOOR_TIME=:doorTime, "
                + "DURATION_ISO=:durationIso, ATTENDANCE_MODE=:mode, "
                + "IS_ACCESSIBLE_FOR_FREE=:free, PLACE_ID=:placeId, VIRTUAL_LOCATION_ID=:vlocId, "
                + "SEARCH_TEXT=:search, UPDATED_BY=:updatedBy, UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id", p);
        // Beziehungen ersetzen
        MapSqlParameterSource eid = new MapSqlParameterSource("eid", eventId);
        jdbc.update("DELETE FROM VK_EVENT_CATEGORY WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_EVENT_KEYWORD WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_EVENT_PARTY_ROLE WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_OFFER WHERE EVENT_ID = :eid", eid);
        jdbc.update("DELETE FROM VK_ASSET WHERE EVENT_ID = :eid", eid);
        writeRelations(eventId, in, mandant, vk);
    }

    // ------------------------------------------------------------------
    // Eigene Events lesen (Besitzer)
    // ------------------------------------------------------------------

    public JsonArray listOwn(long userId) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        final JsonArray out = new JsonArray();
        jdbc.query("SELECT PUBLIC_ID, TITLE, START_AT, WORKFLOW_STATUS, EVENT_STATUS, UPDATED_AT, CREATED_AT "
                + "FROM VK_EVENT WHERE MANDANT_ID=:m AND VK_ID=:vk AND CREATED_BY=:uid "
                + "ORDER BY COALESCE(UPDATED_AT, CREATED_AT) DESC",
                new MapSqlParameterSource().addValue("m", mandant).addValue("vk", vk).addValue("uid", userId),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonObject o = new JsonObject();
                    Json.str(o, "id", rs.getString("PUBLIC_ID"));
                    Json.str(o, "title", rs.getString("TITLE"));
                    Json.isoField(o, "startAt", rs.getTimestamp("START_AT"));
                    Json.str(o, "workflowStatus", rs.getString("WORKFLOW_STATUS"));
                    Json.str(o, "eventStatus", rs.getString("EVENT_STATUS"));
                    out.add(o);
                });
        return out;
    }

    /** Liefert die interne ID + Status eines eigenen Events oder null. */
    public long[] findOwnIdAndStatus(String publicId, long userId, String[] statusOut) {
        long mandant = ConfigVk.requireMandant();
        long vk = ConfigVk.requireVkId();
        try {
            return jdbc.queryForObject(
                    "SELECT ID, WORKFLOW_STATUS FROM VK_EVENT WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m "
                  + "AND VK_ID=:vk AND CREATED_BY=:uid",
                    new MapSqlParameterSource().addValue("pid", publicId).addValue("m", mandant)
                            .addValue("vk", vk).addValue("uid", userId),
                    (rs, n) -> {
                        statusOut[0] = rs.getString("WORKFLOW_STATUS");
                        return new long[]{rs.getLong("ID")};
                    });
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void setWorkflowStatus(long eventId, String status) {
        jdbc.update("UPDATE VK_EVENT SET WORKFLOW_STATUS=:s, UPDATED_AT=CURRENT_TIMESTAMP WHERE ID=:id",
                new MapSqlParameterSource().addValue("s", status).addValue("id", eventId));
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private void writeRelations(long eventId, EventInput in, long mandant, long vk) {
        if (in.categorySlug != null && !in.categorySlug.isEmpty()) {
            jdbc.update("INSERT INTO VK_EVENT_CATEGORY (EVENT_ID, CATEGORY_ID, PRIMARY_FLAG) "
                    + "SELECT :eid, ID, 'Y' FROM VK_CATEGORY WHERE SLUG = :slug",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("slug", in.categorySlug));
        }
        if (in.keywords != null) {
            for (String kw : in.keywords) {
                String name = kw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                long kwId = resolveKeyword(name);
                jdbc.update("INSERT INTO VK_EVENT_KEYWORD (EVENT_ID, KEYWORD_ID) VALUES (:eid, :kid)",
                        new MapSqlParameterSource().addValue("eid", eventId).addValue("kid", kwId));
            }
        }
        if (in.organizerName != null && !in.organizerName.trim().isEmpty()) {
            long partyId = resolveParty(in.organizerName.trim(), in.organizerEmail, null,
                    "ORGANIZATION", mandant, vk);
            jdbc.update("INSERT INTO VK_EVENT_PARTY_ROLE (EVENT_ID, PARTY_ID, ROLE_TYPE, SORT_ORDER) "
                    + "VALUES (:eid, :pid, 'ORGANIZER', 0)",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("pid", partyId));
        }
        writePartyRole(eventId, in.performers, "PERFORMER", "PERSON", mandant, vk);
        writePartyRole(eventId, in.sponsors, "SPONSOR", "ORGANIZATION", mandant, vk);
        writeOffers(eventId, in.offers);
        writeAssets(eventId, in.images, "IMAGE");
        writeAssets(eventId, in.documents, "DOCUMENT");
    }

    private void writePartyRole(long eventId, List<PartyRef> parties, String role, String partyType,
                                long mandant, long vk) {
        if (parties == null) {
            return;
        }
        int sort = 0;
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        for (PartyRef ref : parties) {
            if (ref == null || ref.displayName == null || ref.displayName.trim().isEmpty()) {
                continue;
            }
            long partyId = resolveParty(ref.displayName.trim(), ref.email, ref.url, partyType, mandant, vk);
            if (!seen.add(partyId)) {
                continue; // selbe Partei nicht doppelt in derselben Rolle (PK-Schutz)
            }
            jdbc.update("INSERT INTO VK_EVENT_PARTY_ROLE (EVENT_ID, PARTY_ID, ROLE_TYPE, SORT_ORDER) "
                    + "VALUES (:eid, :pid, :role, :sort)",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("pid", partyId)
                            .addValue("role", role).addValue("sort", sort++));
        }
    }

    private void writeOffers(long eventId, List<Offer> offers) {
        if (offers == null) {
            return;
        }
        for (Offer o : offers) {
            if (o == null || (o.price == null && (o.name == null || o.name.trim().isEmpty())
                    && (o.url == null || o.url.trim().isEmpty()))) {
                continue; // völlig leere Zeile überspringen
            }
            jdbc.update("INSERT INTO VK_OFFER (EVENT_ID, NAME, PRICE, PRICE_CURRENCY, URL, AVAILABILITY) "
                    + "VALUES (:eid, :name, :price, :cur, :url, :avail)",
                    new MapSqlParameterSource().addValue("eid", eventId)
                            .addValue("name", emptyToNull(o.name)).addValue("price", o.price)
                            .addValue("cur", o.priceCurrency == null || o.priceCurrency.trim().isEmpty()
                                    ? "EUR" : o.priceCurrency.trim().toUpperCase())
                            .addValue("url", emptyToNull(o.url)).addValue("avail", emptyToNull(o.availability)));
        }
    }

    private void writeAssets(long eventId, List<AssetRef> assets, String assetType) {
        if (assets == null) {
            return;
        }
        int sort = 0;
        for (AssetRef a : assets) {
            if (a == null || a.url == null || a.url.trim().isEmpty()) {
                continue;
            }
            jdbc.update("INSERT INTO VK_ASSET (EVENT_ID, ASSET_TYPE, FILE_NAME, MIME_TYPE, STORAGE_PATH, "
                    + "ALT_TEXT, COPYRIGHT_TEXT, SORT_ORDER) "
                    + "VALUES (:eid, :type, :file, :mime, :path, :alt, :copy, :sort)",
                    new MapSqlParameterSource().addValue("eid", eventId).addValue("type", assetType)
                            .addValue("file", emptyToNull(a.fileName)).addValue("mime", emptyToNull(a.mimeType))
                            .addValue("path", a.url.trim()).addValue("alt", emptyToNull(a.altText))
                            .addValue("copy", emptyToNull(a.copyrightText)).addValue("sort", sort++));
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private Long resolvePlaceId(String placePublicId) {
        if (placePublicId == null || placePublicId.trim().isEmpty()) {
            return null;
        }
        try {
            return jdbc.queryForObject("SELECT ID FROM VK_PLACE WHERE PUBLIC_ID=:pid AND MANDANT_ID=:m AND VK_ID=:vk",
                    new MapSqlParameterSource().addValue("pid", placePublicId)
                            .addValue("m", ConfigVk.requireMandant()).addValue("vk", ConfigVk.requireVkId()),
                    Long.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private Long createVirtualLocation(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO VK_VIRTUAL_LOCATION (URL) VALUES (:url)",
                new MapSqlParameterSource("url", url.trim()), kh, new String[]{"ID"});
        return kh.getKey().longValue();
    }

    private long resolveKeyword(String name) {
        String slug = SearchTextUtil.normalizeTerm(name).replace(' ', '-');
        try {
            return jdbc.queryForObject("SELECT ID FROM VK_KEYWORD WHERE SLUG=:slug",
                    new MapSqlParameterSource("slug", slug), Long.class);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO VK_KEYWORD (NAME, SLUG) VALUES (:name, :slug)",
                    new MapSqlParameterSource().addValue("name", name).addValue("slug", slug),
                    kh, new String[]{"ID"});
            return kh.getKey().longValue();
        }
    }

    /** Findet eine Partei (Name + Typ, tenant-gescoped) oder legt sie an. Freitext-Name. */
    private long resolveParty(String name, String email, String url, String partyType, long mandant, long vk) {
        try {
            return jdbc.queryForObject(
                    "SELECT ID FROM VK_PARTY WHERE MANDANT_ID=:m AND VK_ID=:vk AND DISPLAY_NAME=:name "
                  + "AND PARTY_TYPE=:type",
                    new MapSqlParameterSource().addValue("m", mandant).addValue("vk", vk)
                            .addValue("name", name).addValue("type", partyType),
                    Long.class);
        } catch (EmptyResultDataAccessException ex) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO VK_PARTY (PUBLIC_ID, MANDANT_ID, VK_ID, PARTY_TYPE, DISPLAY_NAME, EMAIL, URL) "
                    + "VALUES (:pid, :m, :vk, :type, :name, :email, :url)",
                    new MapSqlParameterSource().addValue("pid", UUID.randomUUID().toString())
                            .addValue("m", mandant).addValue("vk", vk).addValue("type", partyType)
                            .addValue("name", name).addValue("email", emptyToNull(email))
                            .addValue("url", emptyToNull(url)),
                    kh, new String[]{"ID"});
            return kh.getKey().longValue();
        }
    }

    private String buildSearchText(EventInput in) {
        String kw = in.keywords == null ? "" : String.join(" ", in.keywords);
        return SearchTextUtil.build(in.title, in.shortDescription, in.categorySlug,
                in.organizerName, kw);
    }
}
