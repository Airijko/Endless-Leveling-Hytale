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
    ARCANE_WISDOM("ARCANE_WISDOM"),
    HEALING_TOUCH("HEALING_TOUCH"),
    HEALING_AURA("HEALING_AURA"),
    SHIELDING_AURA("SHIELDING_AURA"),
    BUFFING_AURA("BUFFING_AURA"),
    ARMY_OF_THE_DEAD("ARMY_OF_THE_DEAD"),
    REGENERATION("REGENERATION"),
    HEALING_BONUS("HEALING_BONUS"),
    LIFE_STEAL("LIFE_STEAL"),
    TRUE_EDGE("TRUE_EDGE"),
    RAVENOUS_STRIKE("RAVENOUS_STRIKE"),
    SPECIAL_CHARGE_BONUS("SPECIAL_CHARGE_BONUS"),
    STAMINA_GAIN_BONUS("STAMINA_GAIN_BONUS"),
    LUCK("LUCK"),
    SECOND_WIND("SECOND_WIND"),
    FIRST_STRIKE("FIRST_STRIKE"),
    INNATE_ATTRIBUTE_GAIN("INNATE_ATTRIBUTE_GAIN"),
    ADRENALINE("ADRENALINE"),
    BERZERKER("BERZERKER"),
    RETALIATION("RETALIATION"),
    ABSORB("ABSORB"),
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
        // Legacy alias retained for backward compatibility with older configs.
        if ("VAMPIRIC_BLADE".equals(normalized)) {
            return RAVENOUS_STRIKE;
        }
        if ("PARTY_MENDING_AURA".equals(normalized)) {
            return HEALING_AURA;
        }
        if ("HEAL_TOUCH".equals(normalized)) {
            return HEALING_TOUCH;
        }
        for (ArchetypePassiveType type : values()) {
            if (type.configKey.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
