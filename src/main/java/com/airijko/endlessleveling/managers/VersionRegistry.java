package com.airijko.endlessleveling.managers;

import java.util.Locale;

/**
 * Centralized source of truth for all file/schema versions used by the plugin.
 */
public final class VersionRegistry {

    private VersionRegistry() {
    }

    public static final String CONFIG_VERSION_KEY = "config_version";
    public static final String PLAYERDATA_VERSION_KEY = "version";

    public static final int CONFIG_YML_VERSION = 27;
    public static final int LEVELING_YML_VERSION = 12;
    public static final int WORLDS_YML_VERSION = 6;
    public static final int WEAPONS_YML_VERSION = 2;

    public static final int PLAYERDATA_SCHEMA_VERSION = 9;

    public static final int BUILTIN_AUGMENTS_VERSION = 8;
    public static final int BUILTIN_CLASSES_VERSION = 8;
    public static final int BUILTIN_RACES_VERSION = 8;
    public static final int BUILTIN_LANG_VERSION = 2;

    public static final String AUGMENTS_VERSION_FILE = "augments.version";
    public static final String CLASSES_VERSION_FILE = "classes.version";
    public static final String RACES_VERSION_FILE = "races.version";
    public static final String LANG_VERSION_FILE = "lang.version";

    public static Integer getResourceConfigVersion(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        return switch (resourceName.trim().toLowerCase(Locale.ROOT)) {
            case "config.yml" -> CONFIG_YML_VERSION;
            case "leveling.yml" -> LEVELING_YML_VERSION;
            case "worlds.yml" -> WORLDS_YML_VERSION;
            case "weapons.yml" -> WEAPONS_YML_VERSION;
            default -> null;
        };
    }
}