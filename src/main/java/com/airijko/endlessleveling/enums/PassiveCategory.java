package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * High-level trigger categories for passives (helps group behaviors in UI and
 * logic).
 */
public enum PassiveCategory {
    PASSIVE_STAT,
    ON_HIT,
    ON_DAMAGE_TAKEN,
    ON_LOW_HP,
    ON_SKILL_USE,
    ON_CRIT,
    ON_CRIT_TAKEN,
    ON_DEATH,
    ON_BLOCK,
    ON_KILL,
    ON_HIT_TARGET_CONDITION;

    public static PassiveCategory fromConfig(Object raw, PassiveCategory fallback) {
        if (raw instanceof PassiveCategory category) {
            return category;
        }
        if (raw instanceof String str && !str.isBlank()) {
            String normalized = str.trim().toUpperCase(Locale.ROOT);
            for (PassiveCategory category : values()) {
                if (category.name().equals(normalized)) {
                    return category;
                }
            }
        }
        return fallback == null ? PASSIVE_STAT : fallback;
    }
}