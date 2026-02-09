package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Resolved configuration for the First Strike passive, including bonus values
 * and cooldown.
 */
public record FirstStrikeSettings(boolean enabled, double bonusPercent, long cooldownMillis) {

    private static final double DEFAULT_COOLDOWN_SECONDS = 30.0D;

    public static FirstStrikeSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        double bonusPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.FIRST_STRIKE));
        if (bonusPercent <= 0) {
            return disabled();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.FIRST_STRIKE);
        double cooldownSum = 0.0D;
        int cooldownSources = 0;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double candidate = parsePositiveDouble(props != null ? props.get("cooldown") : null);
            if (candidate > 0) {
                cooldownSum += candidate;
                cooldownSources++;
            }
        }

        double resolvedSeconds = cooldownSources > 0
                ? cooldownSum / cooldownSources
                : DEFAULT_COOLDOWN_SECONDS;
        long cooldownMillis = (long) Math.max(0L, Math.round(resolvedSeconds * 1000.0D));
        return new FirstStrikeSettings(true, bonusPercent, cooldownMillis);
    }

    public static FirstStrikeSettings disabled() {
        return new FirstStrikeSettings(false, 0.0D, 0L);
    }

    private static double parsePositiveDouble(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value > 0 ? value : 0.0D;
        }
        if (raw instanceof String string) {
            try {
                double parsed = Double.parseDouble(string.trim());
                return parsed > 0 ? parsed : 0.0D;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0D;
    }
}
