package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Resolved settings for Arcane Wisdom passive.
 *
 * Value semantics: ARCANE_WISDOM value is interpreted as bonus mana percent,
 * so 0.20 means a 1.20x mana multiplier.
 */
public record ArcaneWisdomSettings(boolean enabled,
        double manaMultiplier,
        double restorePercent,
        double thresholdPercent) {

    private static final double DEFAULT_RESTORE_PERCENT = 0.25D;
    private static final double DEFAULT_THRESHOLD_PERCENT = 0.10D;

    public static ArcaneWisdomSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }

        double bonusPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.ARCANE_WISDOM));
        if (bonusPercent <= 0.0D) {
            return disabled();
        }

        double restorePercent = DEFAULT_RESTORE_PERCENT;
        double thresholdPercent = DEFAULT_THRESHOLD_PERCENT;

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.ARCANE_WISDOM);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            restorePercent = parsePercent(props, "restore_percent", restorePercent);
            thresholdPercent = parsePercent(props, "threshold", thresholdPercent);
        }

        return new ArcaneWisdomSettings(true,
                1.0D + bonusPercent,
                clamp01(restorePercent),
                clamp01(thresholdPercent));
    }

    private static double parsePercent(Map<String, Object> props, String key, double fallback) {
        if (props == null || key == null) {
            return fallback;
        }
        Object raw = props.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static double clamp01(double value) {
        if (value <= 0.0D) {
            return 0.0D;
        }
        if (value >= 1.0D) {
            return 1.0D;
        }
        return value;
    }

    public static ArcaneWisdomSettings disabled() {
        return new ArcaneWisdomSettings(false, 1.0D, DEFAULT_RESTORE_PERCENT, DEFAULT_THRESHOLD_PERCENT);
    }
}
