package com.airijko.endlessleveling.augments;

import java.util.Map;

public final class AugmentValueReader {

    private AugmentValueReader() {
    }

    public static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string.trim());
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return Map.of();
        }
        Object value = map.get(key);
        if (value instanceof Map<?, ?> inner) {
            return (Map<String, Object>) inner;
        }
        return Map.of();
    }

    public static double getNestedDouble(Map<String, Object> map, double defaultValue, String... path) {
        if (map == null || path == null || path.length == 0) {
            return defaultValue;
        }
        Map<String, Object> current = map;
        for (int i = 0; i < path.length - 1; i++) {
            current = getMap(current, path[i]);
            if (current.isEmpty()) {
                return defaultValue;
            }
        }
        return getDouble(current, path[path.length - 1], defaultValue);
    }
}
