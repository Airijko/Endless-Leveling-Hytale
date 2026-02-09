package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * Defines the ordered layers applied during damage calculation. Layers are
 * multiplicative with respect to one another, while contributors inside the
 * same layer are aggregated by tag.
 */
public enum DamageLayer {
    BASE,
    ATTRIBUTE,
    BONUS,
    CRITICAL,
    WEAPON;

    public static DamageLayer fromConfig(Object raw, DamageLayer fallback) {
        if (raw instanceof String string) {
            String normalized = string.trim().toUpperCase(Locale.ROOT);
            for (DamageLayer layer : values()) {
                if (layer.name().equals(normalized)) {
                    return layer;
                }
            }
        }
        return fallback;
    }
}
