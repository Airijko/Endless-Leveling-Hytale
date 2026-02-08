package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents executioner-style bonus damage entries applied against low-health
 * targets.
 */
public record ExecutionerSettings(List<Entry> entries, double cooldownSeconds) {

    private static final double DEFAULT_COOLDOWN = 12.0D;

    public record Entry(double thresholdPercent, double bonusPercent) {
        public boolean isExecute() {
            return bonusPercent >= 1.0D;
        }
    }

    public static ExecutionerSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        List<Entry> entries = new ArrayList<>();
        double cooldown = DEFAULT_COOLDOWN;
        if (snapshot == null) {
            return new ExecutionerSettings(List.of(), cooldown);
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.EXECUTIONER);
        if (definitions.isEmpty()) {
            return new ExecutionerSettings(List.of(), cooldown);
        }

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            double bonusPercent = Math.max(0.0D, definition.value());
            if (bonusPercent <= 0.0D) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double threshold = parsePositiveDouble(props, "threshold", 0.35D);
            if (threshold <= 0.0D) {
                continue;
            }
            entries.add(new Entry(threshold, bonusPercent));

            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldown = cooldown <= 0.0D ? cooldownCandidate : Math.min(cooldown, cooldownCandidate);
            }
        }
        return new ExecutionerSettings(List.copyOf(entries), cooldown);
    }

    public boolean enabled() {
        return !entries.isEmpty();
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(Math.max(0.0D, cooldownSeconds) * 1000.0D));
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
}
