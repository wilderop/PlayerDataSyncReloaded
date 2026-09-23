package de.craftingstudiopro.playerDataSyncReloaded.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Shared inventory/ender-chest payload. Paper and Fabric both persist
 * {@code {"v":2,"slots":[{"slot":n,"b":"<base64 of vanilla item bytes>"}]}}.
 */
public final class SlotDataFormat {
    public static final int VERSION = 2;

    public enum Kind {
        V2, FABRIC_JSON, LEGACY_BUKKIT, EMPTY, UNKNOWN
    }

    private SlotDataFormat() {}

    public static Kind kind(String raw) {
        if (raw == null || raw.isBlank()) {
            return Kind.EMPTY;
        }
        String s = raw.trim();
        if (s.startsWith("{")) {
            try {
                JsonObject obj = JsonParser.parseString(s).getAsJsonObject();
                if (obj.has("v") && obj.get("v").getAsInt() == VERSION) {
                    return Kind.V2;
                }
            } catch (Exception ignored) {
            }
            return Kind.UNKNOWN;
        }
        if (s.startsWith("[")) {
            return Kind.FABRIC_JSON;
        }
        return Kind.LEGACY_BUKKIT;
    }

    public static String encode(Map<Integer, byte[]> slots) {
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonArray arr = new JsonArray();
        slots.forEach((slot, bytes) -> {
            if (bytes == null || bytes.length == 0) {
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("slot", slot);
            o.addProperty("b", Base64.getEncoder().encodeToString(bytes));
            arr.add(o);
        });
        root.add("slots", arr);
        return root.toString();
    }

    public static void decodeV2(String json, BiConsumer<Integer, byte[]> consumer) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("slots");
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("b") || o.get("b").isJsonNull()) {
                continue;
            }
            int slot = o.get("slot").getAsInt();
            byte[] bytes = Base64.getDecoder().decode(o.get("b").getAsString());
            consumer.accept(slot, bytes);
        }
    }

    public static Map<Integer, byte[]> decodeV2(String json) {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        decodeV2(json, out::put);
        return out;
    }
}
