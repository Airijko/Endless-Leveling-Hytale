package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates Retaliation passive tuning (window/cooldown/percent).
 */
public record RetaliationSettings(boolean enabled,
        double reflectPercent,
        double windowSeconds,
        double cooldownSeconds) {

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

        double window = DEFAULT_WINDOW;
        double cooldown = DEFAULT_COOLDOWN;
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.RETALIATION);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            window = Math.max(window, parsePositiveDouble(props, "window", DEFAULT_WINDOW));
            double cooldownCandidate = parsePositiveDouble(props, "cooldown", 0.0D);
            if (cooldownCandidate > 0.0D) {
                cooldown = cooldown <= 0.0D ? cooldownCandidate : Math.min(cooldown, cooldownCandidate);
            }
        }
        return new RetaliationSettings(true, reflectPercent, window, cooldown);
    }

    public long windowMillis() {
        return (long) Math.max(0L, Math.round(windowSeconds * 1000.0D));
    }

    public long cooldownMillis() {
        return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
    }

    public static RetaliationSettings disabled() {
        return new RetaliationSettings(false, 0.0D, DEFAULT_WINDOW, DEFAULT_COOLDOWN);
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
