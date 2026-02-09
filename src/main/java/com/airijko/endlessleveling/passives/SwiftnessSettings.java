package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Configuration container for the Swiftness passive.
 */
public record SwiftnessSettings(boolean enabled,
        double stackBonusPercent,
        double durationSeconds,
        int maxStacks) {

    private static final double DEFAULT_DURATION_SECONDS = 5.0D;
    private static final int DEFAULT_MAX_STACKS = 1;

    public static SwiftnessSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.SWIFTNESS);
        double bonusPercent = resolveBonusPercent(snapshot, definitions);
        if (bonusPercent <= 0.0D) {
            return disabled();
        }

        double durationSum = 0.0D;
        int durationSources = 0;
        int maxStacks = DEFAULT_MAX_STACKS;

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();
            double durationValue = parsePositiveDouble(props, "duration", 0.0D);
            if (durationValue > 0.0D) {
                durationSum += durationValue;
                durationSources++;
            }
            maxStacks = Math.max(maxStacks, parsePositiveInt(props, "max_stacks", maxStacks));
        }

        if (maxStacks <= 0) {
            maxStacks = DEFAULT_MAX_STACKS;
        }

        double duration = durationSources > 0 ? durationSum / durationSources : DEFAULT_DURATION_SECONDS;
        return new SwiftnessSettings(true, bonusPercent, duration, maxStacks);
    }

    private static double resolveBonusPercent(ArchetypePassiveSnapshot snapshot,
            List<RacePassiveDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.SWIFTNESS));
        }
        double total = 0.0D;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            total += Math.max(0.0D, definition.value());
        }
        return total;
    }

    public double multiplierForStacks(int stacks) {
        if (!enabled || stacks <= 0) {
            return 1.0D;
        }
        int cappedStacks = Math.min(stacks, Math.max(1, maxStacks));
        return 1.0D + stackBonusPercent * cappedStacks;
    }

    public double totalBonusPercent(int stacks) {
        return multiplierForStacks(stacks) - 1.0D;
    }

    public long durationMillis() {
        return (long) Math.max(0L, Math.round(durationSeconds * 1000.0D));
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

    private static int parsePositiveInt(Map<String, Object> props, String key, int fallback) {
        if (props == null || key == null) {
            return fallback;
        }
        Object raw = props.get(key);
        if (raw instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : fallback;
        }
        if (raw instanceof String string) {
            try {
                int value = Integer.parseInt(string.trim());
                return value > 0 ? value : fallback;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public static SwiftnessSettings disabled() {
        return new SwiftnessSettings(false, 0.0D, 0.0D, 0);
    }
}
