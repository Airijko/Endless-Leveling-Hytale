package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Resolved configuration for Adrenaline passive which restores a percentage of
 * stamina over a short duration when the player dips below a threshold.
 */
public record AdrenalineSettings(boolean enabled,
        double restorePercent,
        double thresholdPercent,
        double durationSeconds,
        double cooldownSeconds) {

    private static final double DEFAULT_THRESHOLD = 0.35D;
    private static final double DEFAULT_DURATION = 5.0D;
    private static final double DEFAULT_COOLDOWN = 30.0D;

    public static AdrenalineSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        double restorePercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.ADRENALINE));
        if (restorePercent <= 0.0D) {
            return disabled();
        }

        double thresholdSum = 0.0D;
        int thresholdSources = 0;
        double durationSum = 0.0D;
        int durationSources = 0;
        double cooldownSum = 0.0D;
        int cooldownSources = 0;

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.ADRENALINE);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double thresholdValue = parsePositiveDouble(props, "threshold", 0.0D);
            if (thresholdValue > 0.0D) {
                thresholdSum += thresholdValue;
                thresholdSources++;
            }
            double durationValue = parsePositiveDouble(props, "duration", 0.0D);
            if (durationValue > 0.0D) {
                durationSum += durationValue;
                durationSources++;
            }
            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldownSum += cooldownCandidate;
                cooldownSources++;
            }
        }

        double threshold = thresholdSources > 0 ? thresholdSum / thresholdSources : DEFAULT_THRESHOLD;
        double duration = durationSources > 0 ? durationSum / durationSources : DEFAULT_DURATION;
        double cooldown = cooldownSources > 0 ? cooldownSum / cooldownSources : DEFAULT_COOLDOWN;

        return new AdrenalineSettings(true, restorePercent, threshold, duration, cooldown);
    }

    public long durationMillis() {
        return (long) Math.max(0L, Math.round(durationSeconds * 1000.0D));
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
    }

    private static double parsePositiveDouble(Map<String, Object> props, String key, double fallback) {
        if (props == null || key == null) {
            return fallback;
        }
        Object raw = props.get(key);
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value > 0.0D ? value : fallback;
        }
        if (raw instanceof String string) {
            try {
                double value = Double.parseDouble(string.trim());
                return value > 0.0D ? value : fallback;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public static AdrenalineSettings disabled() {
        return new AdrenalineSettings(false, 0.0D, DEFAULT_THRESHOLD, DEFAULT_DURATION, DEFAULT_COOLDOWN);
    }
}
