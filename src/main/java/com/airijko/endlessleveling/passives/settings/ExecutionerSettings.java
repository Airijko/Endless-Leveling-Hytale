package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents Final Incantation style bonus damage entries applied against
 * low-health targets.
 */
public record ExecutionerSettings(List<Entry> entries,
        double cooldownSeconds,
        double cooldownReductionOnKillSeconds) {

    private static final double DEFAULT_COOLDOWN = 12.0D;
    private static final double DEFAULT_COOLDOWN_REDUCTION_ON_KILL = 0.0D;
    private static final double DEFAULT_FLAT_BONUS_DAMAGE = 25.0D;

    public record Entry(double thresholdPercent, double flatBonusDamage, double bonusDamagePercent) {
    }

    public static ExecutionerSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        List<Entry> entries = new ArrayList<>();
        double cooldownSum = 0.0D;
        int cooldownSources = 0;
        double cooldownReductionSum = 0.0D;
        int cooldownReductionSources = 0;
        if (snapshot == null) {
            return new ExecutionerSettings(List.of(), DEFAULT_COOLDOWN, DEFAULT_COOLDOWN_REDUCTION_ON_KILL);
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.FINAL_INCANTATION);
        if (definitions.isEmpty()) {
            return new ExecutionerSettings(List.of(), DEFAULT_COOLDOWN, DEFAULT_COOLDOWN_REDUCTION_ON_KILL);
        }

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            double scalingValue = Math.max(0.0D, definition.value());
            if (scalingValue <= 0.0D) {
                continue;
            }

            Map<String, Object> props = definition.properties();
            double threshold = parseThresholdPercent(props, "threshold", 0.35D);
            if (threshold <= 0.0D) {
                continue;
            }

            double flatBonusDamage = parsePositiveDouble(props, "flat_bonus_damage", DEFAULT_FLAT_BONUS_DAMAGE);
            if (flatBonusDamage <= 0.0D) {
                continue;
            }
            double bonusDamagePercent = parsePercent(props, "bonus_damage_percent", scalingValue);
            entries.add(new Entry(threshold, flatBonusDamage, bonusDamagePercent));

            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldownSum += cooldownCandidate;
                cooldownSources++;
            }

            double cooldownReductionCandidate = parsePositiveDouble(props,
                    "cooldown_reduction_on_kill",
                    parsePositiveDouble(props, "cooldown_reduction_on_kill_seconds", 0.0D));
            if (cooldownReductionCandidate > 0.0D) {
                cooldownReductionSum += cooldownReductionCandidate;
                cooldownReductionSources++;
            }
        }
        double cooldown = cooldownSources > 0 ? cooldownSum / cooldownSources : DEFAULT_COOLDOWN;
        double cooldownReductionOnKill = cooldownReductionSources > 0
                ? cooldownReductionSum / cooldownReductionSources
                : DEFAULT_COOLDOWN_REDUCTION_ON_KILL;
        return new ExecutionerSettings(List.copyOf(entries), cooldown, cooldownReductionOnKill);
    }

    public boolean enabled() {
        return !entries.isEmpty();
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(Math.max(0.0D, cooldownSeconds) * 1000.0D));
    }

    public long cooldownReductionOnKillMillis() {
        return (long) Math.max(0L, Math.round(Math.max(0.0D, cooldownReductionOnKillSeconds) * 1000.0D));
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

    private static double parseThresholdPercent(Map<String, Object> props, String key, double fallback) {
        double rawThreshold = parsePositiveDouble(props, key, fallback);
        double normalized = rawThreshold;
        if (normalized > 1.0D) {
            normalized /= 100.0D;
        }
        if (normalized <= 0.0D) {
            return fallback;
        }
        return Math.min(1.0D, normalized);
    }

    private static double parsePercent(Map<String, Object> props, String key, double fallback) {
        double raw = parsePositiveDouble(props, key, fallback);
        if (raw > 10.0D) {
            return raw / 100.0D;
        }
        return raw;
    }
}
