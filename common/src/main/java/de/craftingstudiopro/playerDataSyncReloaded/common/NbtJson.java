package de.craftingstudiopro.playerDataSyncReloaded.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tiny NBT binary reader/writer plus gzip, so Fabric can read Paper's
 * {@code ItemStack.serializeAsBytes()} without calling Minecraft NbtIo
 * (yarn 1.21.11 remaps NbtIo to a class that does not exist in 26.2).
 */
public final class NbtJson {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private NbtJson() {}

    public static JsonElement gzipNbtToJson(byte[] gzipNbt) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipNbt)))) {
            int type = in.readUnsignedByte();
            if (type != TAG_COMPOUND) {
                throw new IOException("Expected compound tag, got " + type);
            }
            readUtf(in); // empty root name
            return readCompound(in);
        }
    }

    public static byte[] jsonToGzipNbt(JsonElement json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             DataOutputStream out = new DataOutputStream(gzip)) {
            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "");
            writeCompoundContents(out, json.getAsJsonObject());
            gzip.finish();
        }
        return bytes.toByteArray();
    }

    private static JsonElement readTag(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> new JsonPrimitive((int) in.readByte());
            case TAG_SHORT -> new JsonPrimitive((int) in.readShort());
            case TAG_INT -> new JsonPrimitive(in.readInt());
            case TAG_LONG -> new JsonPrimitive(in.readLong());
            case TAG_FLOAT -> new JsonPrimitive(in.readFloat());
            case TAG_DOUBLE -> new JsonPrimitive(in.readDouble());
            case TAG_STRING -> new JsonPrimitive(readUtf(in));
            case TAG_COMPOUND -> readCompound(in);
            case TAG_LIST -> readList(in);
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                JsonArray arr = new JsonArray();
                for (int i = 0; i < len; i++) {
                    arr.add(in.readByte());
                }
                yield arr;
            }
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                JsonArray arr = new JsonArray();
                for (int i = 0; i < len; i++) {
                    arr.add(in.readInt());
                }
                yield arr;
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                JsonArray arr = new JsonArray();
                for (int i = 0; i < len; i++) {
                    arr.add(in.readLong());
                }
                yield arr;
            }
            default -> throw new IOException("Unknown NBT type " + type);
        };
    }

    private static JsonObject readCompound(DataInputStream in) throws IOException {
        JsonObject obj = new JsonObject();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                break;
            }
            String name = readUtf(in);
            obj.add(name, readTag(in, type));
        }
        return obj;
    }

    private static JsonArray readList(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < len; i++) {
            arr.add(type == TAG_END ? JsonNull.INSTANCE : readTag(in, type));
        }
        return arr;
    }

    private static void writeCompoundContents(DataOutputStream out, JsonObject obj) throws IOException {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonElement value = e.getValue();
            int type = jsonType(value);
            out.writeByte(type);
            writeUtf(out, e.getKey());
            writeTag(out, type, value);
        }
        out.writeByte(TAG_END);
    }

    private static int jsonType(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return TAG_END;
        }
        if (value.isJsonObject()) {
            return TAG_COMPOUND;
        }
        if (value.isJsonArray()) {
            return TAG_LIST;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isString()) {
            return TAG_STRING;
        }
        if (p.isBoolean()) {
            return TAG_BYTE;
        }
        if (p.isNumber()) {
            Number n = p.getAsNumber();
            if (n instanceof Double || n instanceof Float || p.getAsString().contains(".")) {
                return TAG_DOUBLE;
            }
            long l = n.longValue();
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return TAG_INT;
            }
            return TAG_LONG;
        }
        return TAG_STRING;
    }

    private static void writeTag(DataOutputStream out, int type, JsonElement value) throws IOException {
        switch (type) {
            case TAG_BYTE -> out.writeByte(value.getAsByte());
            case TAG_SHORT -> out.writeShort(value.getAsShort());
            case TAG_INT -> out.writeInt(value.getAsInt());
            case TAG_LONG -> out.writeLong(value.getAsLong());
            case TAG_FLOAT -> out.writeFloat(value.getAsFloat());
            case TAG_DOUBLE -> out.writeDouble(value.getAsDouble());
            case TAG_STRING -> writeUtf(out, value.getAsString());
            case TAG_COMPOUND -> writeCompoundContents(out, value.getAsJsonObject());
            case TAG_LIST -> {
                JsonArray arr = value.getAsJsonArray();
                int listType = arr.isEmpty() ? TAG_END : jsonType(arr.get(0));
                out.writeByte(listType);
                out.writeInt(arr.size());
                for (JsonElement el : arr) {
                    if (listType != TAG_END) {
                        writeTag(out, listType, el);
                    }
                }
            }
            default -> throw new IOException("Cannot write NBT type " + type);
        }
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] buf = in.readNBytes(len);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] buf = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(buf.length);
        out.write(buf);
    }
}
