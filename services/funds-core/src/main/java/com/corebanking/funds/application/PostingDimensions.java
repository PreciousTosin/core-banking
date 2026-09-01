package com.corebanking.funds.application;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** PostgreSQL-jsonb-compatible deterministic dimension encoding and sizing. */
public final class PostingDimensions {
    private PostingDimensions() {}

    public static int jsonbTextBytes(Map<String, String> dimensions) {
        return jsonbText(dimensions).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String compactJson(Map<String, String> dimensions) {
        return encode(dimensions, false);
    }

    static String jsonbText(Map<String, String> dimensions) {
        return encode(dimensions, true);
    }

    private static String encode(Map<String, String> dimensions, boolean jsonbSpacing) {
        Objects.requireNonNull(dimensions, "dimensions");
        var json = new StringBuilder("{");
        boolean first = true;
        for (var entry : new TreeMap<>(dimensions).entrySet()) {
            if (!first) {
                json.append(',');
                if (jsonbSpacing) {
                    json.append(' ');
                }
            }
            first = false;
            jsonString(json, Objects.requireNonNull(entry.getKey(), "dimension key"));
            json.append(':');
            if (jsonbSpacing) {
                json.append(' ');
            }
            jsonString(json, Objects.requireNonNull(entry.getValue(), "dimension value"));
        }
        return json.append('}').toString();
    }

    private static void jsonString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u");
                        String hex = Integer.toHexString(character);
                        json.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
