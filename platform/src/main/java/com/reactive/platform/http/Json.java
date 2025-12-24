package com.reactive.platform.http;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON parser/serializer.
 *
 * No dependencies. Just enough for simple request/response handling.
 * For complex JSON, use a proper library.
 */
public final class Json {

    private Json() {}

    // ========================================================================
    // Parsing (minimal, for simple flat objects)
    // ========================================================================

    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern INT_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern BOOL_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

    /**
     * Parse flat JSON object to map.
     */
    public static Map<String, Object> parse(String json) {
        Map<String, Object> result = new HashMap<>();

        // String fields
        Matcher m = STRING_FIELD.matcher(json);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }

        // Int fields (only if not already matched as string)
        m = INT_FIELD.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            if (!result.containsKey(key)) {
                result.put(key, Long.parseLong(m.group(2)));
            }
        }

        // Bool fields
        m = BOOL_FIELD.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            if (!result.containsKey(key)) {
                result.put(key, Boolean.parseBoolean(m.group(2)));
            }
        }

        return result;
    }

    /**
     * Parse and map to type.
     */
    public static <T> T parse(String json, Function<Map<String, Object>, T> mapper) {
        return mapper.apply(parse(json));
    }

    /**
     * Parse from bytes.
     */
    public static Map<String, Object> parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    // ========================================================================
    // Serialization
    // ========================================================================

    /**
     * Serialize object to JSON string.
     * Supports: String, Number, Boolean, null.
     */
    public static String stringify(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Must provide key-value pairs");
        }

        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(",");
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            sb.append("\"").append(key).append("\":");
            appendValue(sb, value);
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append("\"").append(escape(s)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append("\"").append(escape(value.toString())).append("\"");
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Stringify to bytes.
     */
    public static byte[] bytes(Object... keyValues) {
        return stringify(keyValues).getBytes(StandardCharsets.UTF_8);
    }
}
