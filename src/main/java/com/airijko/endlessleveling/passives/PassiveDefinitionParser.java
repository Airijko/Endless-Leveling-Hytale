package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;

import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for parsing passive metadata (tags, stacking, damage layers)
 * out of YAML definitions.
 */
public final class PassiveDefinitionParser {

    private PassiveDefinitionParser() {
    }

    public static DamageLayer resolveDamageLayer(ArchetypePassiveType type, Map<String, Object> passive) {
        Object rawLayer = passive != null ? firstNonNull(passive.get("layer"), passive.get("damage_layer")) : null;
        DamageLayer fallback = defaultDamageLayer(type);
        return DamageLayer.fromConfig(rawLayer, fallback);
    }

    public static String resolveTag(ArchetypePassiveType type, Map<String, Object> passive) {
        Object tagCandidate = passive != null ? firstNonNull(passive.get("tag"), passive.get("damage_tag")) : null;
        if (tagCandidate instanceof String stringTag && !stringTag.isBlank()) {
            return stringTag.trim().toLowerCase(Locale.ROOT);
        }
        if (passive != null) {
            Object tagsNode = passive.get("tags");
            if (tagsNode instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    if (entry instanceof String stringEntry && !stringEntry.isBlank()) {
                        return stringEntry.trim().toLowerCase(Locale.ROOT);
                    }
                }
            }
        }
        if (type != null) {
            return type.getConfigKey().toLowerCase(Locale.ROOT);
        }
        return "default";
    }

    public static PassiveStackingStyle resolveStacking(ArchetypePassiveType type, Map<String, Object> passive) {
        // Stacking style is authoritative in code; YAML overrides are ignored.
        return PassiveStackingStyle.defaultFor(type);
    }

    private static DamageLayer defaultDamageLayer(ArchetypePassiveType type) {
        if (type == null) {
            return DamageLayer.BONUS;
        }
        return switch (type) {
            case FIRST_STRIKE, BERZERKER, EXECUTIONER, RETALIATION -> DamageLayer.BONUS;
            default -> DamageLayer.BONUS;
        };
    }

    private static Object firstNonNull(Object primary, Object secondary) {
        return primary != null ? primary : secondary;
    }
}
