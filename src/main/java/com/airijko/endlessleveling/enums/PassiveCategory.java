package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * High-level trigger categories for passives (helps group behaviors in UI and
 * logic).
 */
public enum PassiveCategory {
    PASSIVE_STAT("Ingredient_Life_Essence"),
    ON_HIT("Weapon_Sword_Mithril"),
    ON_DAMAGE_TAKEN("Weapon_Shield_Orbis_Knight"),
    ON_LOW_HP("Potion_Health_Lesser"),
    ON_SKILL_USE("Ingredient_Water_Essence"),
    ON_CRIT("Weapon_Battleaxe_Mithril"),
    ON_CRIT_TAKEN("Weapon_Shield_Orbis_Knight"),
    ON_DEATH("Ingredient_Void_Essence"),
    ON_BLOCK("Weapon_Shield_Orbis_Knight"),
    ON_KILL("Ingredient_Fire_Essence"),
    ON_HIT_TARGET_CONDITION("Weapon_Sword_Mithril");

    private final String iconItemId;

    PassiveCategory(String iconItemId) {
        this.iconItemId = iconItemId;
    }

    public String getIconItemId() {
        return iconItemId;
    }

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