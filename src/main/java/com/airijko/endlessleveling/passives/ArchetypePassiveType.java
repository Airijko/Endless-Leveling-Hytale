package com.airijko.endlessleveling.passives;

import java.util.Locale;

/**
 * Enumerates archetype-specific passives that can be provided by races or
 * classes.
 */
public enum ArchetypePassiveType {
    XP_BONUS("XP_BONUS"),
    INCREASED_HEALTH_REGEN("INCREASED_HEALTH_REGEN"),
    INCREASED_MANA_REGEN("INCREASED_MANA_REGEN"),
    HEALING_BONUS("HEALING_BONUS"),
    SPECIAL_CHARGE_BONUS("SPECIAL_CHARGE_BONUS");

    private final String configKey;

    ArchetypePassiveType(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }

    public static ArchetypePassiveType fromConfigKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String normalized = rawKey.trim().toUpperCase(Locale.ROOT);
        for (ArchetypePassiveType type : values()) {
            if (type.configKey.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
