package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * Represents the rarity/power tier of a passive.
 */
public enum PassiveTier {
    COMMON,
    ELITE,
    MYTHIC;

    public static PassiveTier fromConfig(Object raw, PassiveTier fallback) {
        if (raw instanceof PassiveTier tier) {
            return tier;
        }
        if (raw instanceof String str && !str.isBlank()) {
            String normalized = str.trim().toUpperCase(Locale.ROOT);
            if ("PRIME".equals(normalized)) {
                return ELITE; // backward compatibility with legacy naming
            }
            for (PassiveTier tier : values()) {
                if (tier.name().equals(normalized)) {
                    return tier;
                }
            }
        }
        return fallback == null ? COMMON : fallback;
    }

    public boolean isUniqueTier() {
        return this == ELITE || this == MYTHIC;
    }
}
