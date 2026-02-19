package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * Enumerates archetype-specific passives that can be provided by races or
 * classes.
 */
public enum ArchetypePassiveType {
    XP_BONUS("XP_BONUS"),
    HEALTH_REGEN("HEALTH_REGEN"),
    MANA_REGEN("MANA_REGEN"),
    MANA_REGEN_FLAT("MANA_REGEN_FLAT"),
    REGENERATION("REGENERATION"),
    HEALING_BONUS("HEALING_BONUS"),
    LIFE_STEAL("LIFE_STEAL"),
    SPECIAL_CHARGE_BONUS("SPECIAL_CHARGE_BONUS"),
    STAMINA_GAIN_BONUS("STAMINA_GAIN_BONUS"),
    LUCK("LUCK"),
    SECOND_WIND("SECOND_WIND"),
    FIRST_STRIKE("FIRST_STRIKE"),
    INNATE_ATTRIBUTE_GAIN("INNATE_ATTRIBUTE_GAIN"),
    ADRENALINE("ADRENALINE"),
    BERZERKER("BERZERKER"),
    RETALIATION("RETALIATION"),
    EXECUTIONER("EXECUTIONER"),
    SWIFTNESS("SWIFTNESS"),
    WITHER("WITHER"),
    CRIT_DEFENSE("CRIT_DEFENSE");

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
