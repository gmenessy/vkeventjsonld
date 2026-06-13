package de.example.vk.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.example.vk.config.GsonFactory;
import de.example.vk.util.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bildet das interne Event-JSON auf schema.org/Event JSON-LD ab
 * (Spezifikation Kapitel 17 und 29). Arbeitet direkt auf {@link JsonObject},
 * ohne Zwischen-DTO.
 */
@Service
public class JsonLdService {

    private final Gson gson = GsonFactory.create();
    private final String baseUrl;

    public JsonLdService(@Value("${vk.baseUrl:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String toJsonLd(JsonObject e) {
        return gson.toJson(buildJsonLd(e));
    }

    public JsonObject buildJsonLd(JsonObject e) {
        JsonObject ld = new JsonObject();
        ld.addProperty("@context", "https://schema.org");
        ld.addProperty("@type", str(e, "schemaType", "Event"));
        ld.addProperty("@id", baseUrl + "/api/events/" + str(e, "id", ""));
        Json.strIfPresent(ld, "name", str(e, "title", null));

        String desc = str(e, "shortDescription", null);
        if (desc == null) {
            desc = str(e, "description", null);
        }
        Json.strIfPresent(ld, "description", desc);

        Json.strIfPresent(ld, "startDate", str(e, "startAt", null));
        Json.strIfPresent(ld, "endDate", str(e, "endAt", null));
        Json.strIfPresent(ld, "doorTime", str(e, "doorTime", null));
        Json.strIfPresent(ld, "duration", str(e, "durationIso", null));

        String status = str(e, "eventStatus", null);
        if (status != null) {
            ld.addProperty("eventStatus", "https://schema.org/" + status);
        }
        ld.addProperty("eventAttendanceMode", attendanceModeIri(str(e, "attendanceMode", "OFFLINE")));
        ld.addProperty("isAccessibleForFree", e.has("isAccessibleForFree")
                && !e.get("isAccessibleForFree").isJsonNull()
                && e.get("isAccessibleForFree").getAsBoolean());
        Json.strIfPresent(ld, "inLanguage", str(e, "languageCode", null));

        JsonElement location = buildLocation(e);
        if (location != null) {
            ld.add("location", location);
        }

        addParties(ld, "organizer", e, "organizers");
        addParties(ld, "performer", e, "performers");
        addParties(ld, "sponsor", e, "sponsors");

        JsonArray offersIn = arr(e, "offers");
        if (offersIn.size() > 0) {
            JsonArray out = new JsonArray();
            for (JsonElement el : offersIn) {
                JsonObject o = el.getAsJsonObject();
                JsonObject offer = new JsonObject();
                offer.addProperty("@type", "Offer");
                Json.strIfPresent(offer, "name", str(o, "name", null));
                if (o.has("price") && !o.get("price").isJsonNull()) {
                    offer.addProperty("price", o.get("price").getAsBigDecimal().toPlainString());
                }
                Json.strIfPresent(offer, "priceCurrency", str(o, "priceCurrency", null));
                Json.strIfPresent(offer, "url", str(o, "url", null));
                String avail = str(o, "availability", null);
                if (avail != null) {
                    offer.addProperty("availability", "https://schema.org/" + avail);
                }
                out.add(offer);
            }
            ld.add("offers", out.size() == 1 ? out.get(0) : out);
        }

        JsonArray imagesIn = arr(e, "images");
        if (imagesIn.size() > 0) {
            JsonArray images = new JsonArray();
            for (JsonElement el : imagesIn) {
                String url = str(el.getAsJsonObject(), "url", null);
                if (url != null) {
                    images.add(url);
                }
            }
            if (images.size() > 0) {
                ld.add("image", images.size() == 1 ? images.get(0) : images);
            }
        }

        JsonArray keywordsIn = arr(e, "keywords");
        if (keywordsIn.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement k : keywordsIn) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(k.getAsString());
            }
            ld.addProperty("keywords", sb.toString());
        }

        Json.strIfPresent(ld, "url", str(e, "canonicalUrl", null));
        Json.strIfPresent(ld, "sameAs", str(e, "sameAsUrl", null));
        return ld;
    }

    private JsonElement buildLocation(JsonObject e) {
        JsonObject placeJson = null;
        if (e.has("place") && e.get("place").isJsonObject()) {
            JsonObject place = e.getAsJsonObject("place");
            placeJson = new JsonObject();
            placeJson.addProperty("@type", "Place");
            Json.strIfPresent(placeJson, "name", str(place, "name", null));
            if (place.has("address") && place.get("address").isJsonObject()) {
                JsonObject a = place.getAsJsonObject("address");
                JsonObject addr = new JsonObject();
                addr.addProperty("@type", "PostalAddress");
                Json.strIfPresent(addr, "streetAddress", str(a, "streetAddress", null));
                Json.strIfPresent(addr, "postalCode", str(a, "postalCode", null));
                Json.strIfPresent(addr, "addressLocality", str(a, "locality", null));
                Json.strIfPresent(addr, "addressRegion", str(a, "region", null));
                Json.strIfPresent(addr, "addressCountry", str(a, "countryCode", null));
                placeJson.add("address", addr);
            }
            if (place.has("latitude") && !place.get("latitude").isJsonNull()
                    && place.has("longitude") && !place.get("longitude").isJsonNull()) {
                JsonObject geo = new JsonObject();
                geo.addProperty("@type", "GeoCoordinates");
                geo.addProperty("latitude", place.get("latitude").getAsDouble());
                geo.addProperty("longitude", place.get("longitude").getAsDouble());
                placeJson.add("geo", geo);
            }
            Json.strIfPresent(placeJson, "url", str(place, "url", null));
        }

        JsonObject virtualJson = null;
        if (e.has("virtualLocation") && e.get("virtualLocation").isJsonObject()) {
            JsonObject v = e.getAsJsonObject("virtualLocation");
            virtualJson = new JsonObject();
            virtualJson.addProperty("@type", "VirtualLocation");
            Json.strIfPresent(virtualJson, "name", str(v, "name", null));
            Json.strIfPresent(virtualJson, "url", str(v, "url", null));
        }

        if (placeJson != null && virtualJson != null) {
            JsonArray both = new JsonArray();
            both.add(placeJson);
            both.add(virtualJson);
            return both;
        }
        return placeJson != null ? placeJson : virtualJson;
    }

    private void addParties(JsonObject ld, String property, JsonObject e, String sourceKey) {
        JsonArray parties = arr(e, sourceKey);
        if (parties.size() == 0) {
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonElement el : parties) {
            JsonObject p = el.getAsJsonObject();
            JsonObject party = new JsonObject();
            party.addProperty("@type", "PERSON".equals(str(p, "type", "")) ? "Person" : "Organization");
            Json.strIfPresent(party, "name", str(p, "displayName", null));
            Json.strIfPresent(party, "email", str(p, "email", null));
            Json.strIfPresent(party, "telephone", str(p, "telephone", null));
            Json.strIfPresent(party, "url", str(p, "url", null));
            out.add(party);
        }
        ld.add(property, out.size() == 1 ? out.get(0) : out);
    }

    private static String attendanceModeIri(String mode) {
        if ("ONLINE".equals(mode)) {
            return "https://schema.org/OnlineEventAttendanceMode";
        }
        if ("MIXED".equals(mode)) {
            return "https://schema.org/MixedEventAttendanceMode";
        }
        return "https://schema.org/OfflineEventAttendanceMode";
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    private static JsonArray arr(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            return obj.getAsJsonArray(key);
        }
        return new JsonArray();
    }
}
