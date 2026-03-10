package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates Absorb passive tuning (reduction/cooldown).
 */
public record AbsorbSettings(boolean enabled,
        double reductionPercent,
        double cooldownSeconds) {

    private static final double DEFAULT_COOLDOWN = 25.0D;

    public static AbsorbSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        double reduction = normalizeRatio(snapshot.getValue(ArchetypePassiveType.ABSORB));
        if (reduction <= 0.0D) {
            return disabled();
        }

        double cooldownSum = 0.0D;
        int cooldownSources = 0;
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.ABSORB);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldownSum += cooldownCandidate;
                cooldownSources++;
            }
        }
        double cooldown = cooldownSources > 0 ? cooldownSum / cooldownSources : DEFAULT_COOLDOWN;
        return new AbsorbSettings(true, reduction, cooldown);
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
    }

    public static AbsorbSettings disabled() {
        return new AbsorbSettings(false, 0.0D, DEFAULT_COOLDOWN);
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

    private static double normalizeRatio(double raw) {
        if (!Double.isFinite(raw)) {
            return 0.0D;
        }
        double ratio = Math.abs(raw) > 1.0D ? raw / 100.0D : raw;
        return Math.max(0.0D, Math.min(1.0D, ratio));
    }
}
