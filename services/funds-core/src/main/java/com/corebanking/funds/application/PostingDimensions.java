package com.corebanking.funds.application;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * PostgreSQL-jsonb-compatible deterministic dimension encoding and sizing. Keys are emitted in
 * sorted order with escaping only where JSON requires it, so equal maps always yield equal
 * bytes. Two renderings exist: compactJson is what the repository sends on insert, and
 * jsonbText reproduces the ", " and ": " spacing of jsonb::text so the byte count agrees with
 * the posting_dimensions_bytes_check limit of 8192.
 */
public final class PostingDimensions {
    private PostingDimensions() {}

    public static int jsonbTextBytes(Map<String, String> dimensions) {
        return jsonbText(dimensions).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Insert form; PostgreSQL re-normalises it into jsonb, so spacing is irrelevant here. */
    public static String compactJson(Map<String, String> dimensions) {
        return encode(dimensions, false);
    }

    /**
     * Sizing form. jsonb stores keys in its own order (shortest first), which changes nothing
     * about the length; only the separators and escaping have to match.
     */
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

    // Minimal escaping matching jsonb's text output: the two mandatory escapes, the short forms
    // for common control characters, the four-hex-digit escape for the remaining control
    // characters, everything else verbatim UTF-8.
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
