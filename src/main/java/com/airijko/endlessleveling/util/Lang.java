package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LanguageManager;

import java.util.UUID;

public final class Lang {

    private Lang() {
    }

    public static String tr(String key, String fallback, Object... args) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return fallbackOrKey(fallback, key, args);
        }

        LanguageManager languageManager = plugin.getLanguageManager();
        if (languageManager == null) {
            return fallbackOrKey(fallback, key, args);
        }

        return languageManager.tr(key, fallback, args);
    }

    public static String tr(UUID playerUuid, String key, String fallback, Object... args) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return fallbackOrKey(fallback, key, args);
        }

        LanguageManager languageManager = plugin.getLanguageManager();
        if (languageManager == null) {
            return fallbackOrKey(fallback, key, args);
        }

        return languageManager.tr(playerUuid, key, fallback, args);
    }

    private static String fallbackOrKey(String fallback, String key, Object... args) {
        String template = fallback != null ? fallback : (key != null ? key : "");
        if (args == null || args.length == 0) {
            return template;
        }
        String formatted = template;
        for (int i = 0; i < args.length; i++) {
            formatted = formatted.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return formatted;
    }
}
