package de.craftingstudiopro.playerDataSyncReloaded.common.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON payloads for potion effects and UNTYPED statistics so Paper and Fabric
 * can round-trip without BukkitObjectOutputStream.
 */
public final class PortableJson {
    private PortableJson() {}

    public static boolean isJsonObject(String data) {
        return data != null && !data.isEmpty() && data.trim().startsWith("{");
    }

    public static boolean isJsonArray(String data) {
        return data != null && !data.isEmpty() && data.trim().startsWith("[");
    }

    public static String statsToJson(Map<String, Integer> stats) {
        JsonObject o = new JsonObject();
        if (stats != null) {
            stats.forEach((k, v) -> {
                if (k != null && v != null) o.addProperty(k, v.intValue());
            });
        }
        return o.toString();
    }

    public static Map<String, Integer> statsFromJson(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!isJsonObject(json)) return out;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            try {
                out.put(e.getKey(), e.getValue().getAsInt());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static JsonArray parseArray(String json) {
        if (!isJsonArray(json)) return new JsonArray();
        JsonElement el = JsonParser.parseString(json);
        return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    public static String advancementsToJson(List<String> keys) {
        JsonArray arr = new JsonArray();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.isBlank()) arr.add(k);
            }
        }
        return arr.toString();
    }

    public static List<String> advancementsFromJson(String json) {
        List<String> out = new ArrayList<>();
        if (!isJsonArray(json)) return out;
        for (JsonElement el : parseArray(json)) {
            try {
                String k = el.getAsString();
                if (k != null && !k.isBlank()) out.add(k);
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
