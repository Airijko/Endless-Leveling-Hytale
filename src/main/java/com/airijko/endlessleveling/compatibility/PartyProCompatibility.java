package com.airijko.endlessleveling.compatibility;

/**
 * Minimal PartyPro presence check via reflection, matching the documented
 * usage.
 */
public final class PartyProCompatibility {

    private static final String API_CLASS = "me.tsumori.partypro.api.PartyProAPI";

    private PartyProCompatibility() {
    }

    public static boolean isAvailable() {
        try {
            Object result = Class.forName(API_CLASS).getMethod("isAvailable").invoke(null);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object getApiInstance() {
        try {
            return Class.forName(API_CLASS).getMethod("getInstance").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}