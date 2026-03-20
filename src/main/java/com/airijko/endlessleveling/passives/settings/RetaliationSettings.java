package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates Retaliation passive tuning (window/cooldown/percent).
 */
public record RetaliationSettings(boolean enabled,
        double reflectPercent,
        double windowSeconds,
    double cooldownSeconds,
    double targetHasteSlowOnHitPercent,
    double targetHasteSlowDurationSeconds) {

    private static final double DEFAULT_WINDOW = 4.0D;
    private static final double DEFAULT_COOLDOWN = 25.0D;

    public static RetaliationSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        double reflectPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.RETALIATION));
        if (reflectPercent <= 0.0D) {
            return disabled();
        }

        double windowSum = 0.0D;
        int windowSources = 0;
        double cooldownSum = 0.0D;
        int cooldownSources = 0;
        double targetSlowPercent = 0.0D;
        double targetSlowDuration = 0.0D;
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.RETALIATION);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double windowCandidate = parsePositiveDouble(props, "window", 0.0D);
            if (windowCandidate > 0.0D) {
                windowSum += windowCandidate;
                windowSources++;
            }
            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldownSum += cooldownCandidate;
                cooldownSources++;
            }

            double slowCandidate = parsePercent(props,
                    "target_haste_slow_on_hit",
                    "target_haste_slow",
                    "on_hit_target_haste_slow");
            if (slowCandidate > 0.0D) {
                targetSlowPercent = Math.max(targetSlowPercent, slowCandidate);
            }

            double slowDurationCandidate = parsePositiveDouble(props, "target_haste_slow_duration", 0.0D);
            if (slowDurationCandidate > 0.0D) {
                targetSlowDuration = Math.max(targetSlowDuration, slowDurationCandidate);
            }
        }
        double window = windowSources > 0 ? windowSum / windowSources : DEFAULT_WINDOW;
        double cooldown = cooldownSources > 0 ? cooldownSum / cooldownSources : DEFAULT_COOLDOWN;
        return new RetaliationSettings(true,
                reflectPercent,
                window,
                cooldown,
                targetSlowPercent,
                targetSlowDuration);
    }

    public long windowMillis() {
        return (long) Math.max(0L, Math.round(windowSeconds * 1000.0D));
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
    }

    public long targetHasteSlowDurationMillis() {
        return (long) Math.max(0L, Math.round(targetHasteSlowDurationSeconds * 1000.0D));
    }

    public static RetaliationSettings disabled() {
        return new RetaliationSettings(false, 0.0D, DEFAULT_WINDOW, DEFAULT_COOLDOWN, 0.0D, 0.0D);
    }

    private static double parsePercent(Map<String, Object> props, String... keys) {
        if (props == null || keys == null) {
            return 0.0D;
        }
        for (String key : keys) {
            double value = parsePositiveDouble(props, key, 0.0D);
            if (value > 0.0D) {
                return value > 1.0D ? value / 100.0D : value;
            }
        }
        return 0.0D;
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
