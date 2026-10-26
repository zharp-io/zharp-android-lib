package io.zharp.logs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Turning what callers log into the JSON Zharp takes. */
final class Json {
    private Json() {
    }

    /** Nested values deeper than this become text, so a map that contains itself can't hang. */
    private static final int MAX_DEPTH = 12;

    /**
     * A log message as text. Callers pass whatever they have — a Throwable, a map, null from a
     * field that wasn't set; the backend refuses an entry whose message isn't non-empty text.
     */
    static String toMessage(Object value) {
        String text;
        if (value == null) {
            text = "";
        } else if (value instanceof String) {
            text = (String) value;
        } else if (value instanceof Throwable) {
            text = describe((Throwable) value);
        } else if (value instanceof Map || value instanceof Collection || value.getClass().isArray()) {
            text = String.valueOf(wrap(value, 0));
        } else {
            text = String.valueOf(value);
        }
        return text.trim().isEmpty() ? "(empty message)" : text;
    }

    /** "IllegalStateException: card declined" — the type people recognise, then what it says. */
    static String describe(Throwable error) {
        String message = error.getMessage();
        String name = error.getClass().getSimpleName();
        if (name.isEmpty()) name = error.getClass().getName();
        return message == null || message.trim().isEmpty() ? name : name + ": " + message;
    }

    /** The full stack as Java prints it, causes included. */
    static String stackOf(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString().trim();
    }

    /** An error as plain fields: its type, its text and where it was thrown. */
    static JSONObject errorFields(Throwable error) {
        JSONObject fields = new JSONObject();
        put(fields, "name", error.getClass().getName());
        put(fields, "message", describe(error));
        put(fields, "stack", stackOf(error));
        return fields;
    }

    /**
     * Metadata as a JSON object, copied now so a later change to the caller's map doesn't change
     * a log already waiting to be sent. Values JSON can't hold become text.
     */
    static JSONObject toJsonMap(Map<String, ?> metadata) {
        JSONObject out = new JSONObject();
        if (metadata == null) return out;
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            if (entry.getKey() == null) continue;
            put(out, entry.getKey(), wrap(entry.getValue(), 0));
        }
        return out;
    }

    /** A value as something JSONObject holds: a primitive, a JSONObject, a JSONArray or text. */
    static Object wrap(Object value, int depth) {
        if (value == null) return JSONObject.NULL;
        if (depth > MAX_DEPTH) return String.valueOf(value);
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // NaN and infinity aren't JSON.
            return Double.isNaN(d) || Double.isInfinite(d) ? String.valueOf(value) : value;
        }
        if (value instanceof Number) return value.toString();
        if (value instanceof Character) return value.toString();
        if (value instanceof JSONObject || value instanceof JSONArray) return value;
        if (value instanceof Throwable) return errorFields((Throwable) value);
        if (value instanceof Date) return iso(((Date) value).getTime());
        if (value instanceof Enum) return ((Enum<?>) value).name();
        if (value instanceof Map) {
            JSONObject out = new JSONObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() == null) continue;
                put(out, String.valueOf(entry.getKey()), wrap(entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof Collection) {
            JSONArray out = new JSONArray();
            for (Object item : (Collection<?>) value) out.put(wrap(item, depth + 1));
            return out;
        }
        if (value.getClass().isArray()) {
            JSONArray out = new JSONArray();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) out.put(wrap(Array.get(value, i), depth + 1));
            return out;
        }
        return String.valueOf(value);
    }

    /** The first Throwable among a map's values, or null. */
    static Throwable firstError(Map<String, ?> metadata) {
        if (metadata == null) return null;
        for (Object value : metadata.values()) {
            if (value instanceof Throwable) return (Throwable) value;
        }
        return null;
    }

    /** "2026-10-26T09:15:02.123Z" */
    static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** JSONObject.put without the checked exception: keys here are never null. */
    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // Only thrown for a null key or a non-finite number, both handled before this.
        }
    }
}
