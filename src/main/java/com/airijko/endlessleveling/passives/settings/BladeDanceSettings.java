package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Configuration container for the Blade Dance passive.
 */
public record BladeDanceSettings(boolean enabled,
        double stackBonusPercent,
        double damageBonusPerStack,
        double durationSeconds,
        int maxStacks,
        boolean triggerOnHit) {

    private static final double DEFAULT_DURATION_SECONDS = 5.0D;
    private static final int DEFAULT_MAX_STACKS = 1;

    public static BladeDanceSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.BLADE_DANCE);
        double bonusPercent = resolveBonusPercent(snapshot, definitions);
        if (bonusPercent <= 0.0D) {
            return disabled();
        }

        double durationSum = 0.0D;
        int durationSources = 0;
        int maxStacks = DEFAULT_MAX_STACKS;
        double damageBonusPerStack = 0.0D;
        boolean triggerOnHit = false;

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
            damageBonusPerStack = Math.max(damageBonusPerStack,
                    parsePercent(props,
                            "damage_bonus_per_stack",
                            "bonus_damage_per_stack",
                            "stack_damage_bonus"));
            triggerOnHit |= parseBoolean(props,
                    "trigger_on_hit",
                    "on_hit",
                    "apply_on_hit");
        }

        if (maxStacks <= 0) {
            maxStacks = DEFAULT_MAX_STACKS;
        }

        double duration = durationSources > 0 ? durationSum / durationSources : DEFAULT_DURATION_SECONDS;
        boolean enabled = bonusPercent > 0.0D || damageBonusPerStack > 0.0D;
        if (!enabled) {
            return disabled();
        }
        return new BladeDanceSettings(true, bonusPercent, damageBonusPerStack, duration, maxStacks, triggerOnHit);
    }

    private static double resolveBonusPercent(ArchetypePassiveSnapshot snapshot,
            List<RacePassiveDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.BLADE_DANCE));
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

    public double damageMultiplierForStacks(int stacks) {
        if (!enabled || stacks <= 0 || damageBonusPerStack <= 0.0D) {
            return 1.0D;
        }
        int cappedStacks = Math.min(stacks, Math.max(1, maxStacks));
        return 1.0D + (damageBonusPerStack * cappedStacks);
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

    private static boolean parseBoolean(Map<String, Object> props, String... keys) {
        if (props == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            Object raw = props.get(key);
            if (raw instanceof Boolean bool) {
                return bool;
            }
            if (raw instanceof Number number) {
                return number.intValue() != 0;
            }
            if (raw instanceof String string) {
                String normalized = string.trim().toLowerCase(java.util.Locale.ROOT);
                if ("true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)
                        || "1".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)
                        || "0".equals(normalized)) {
                    return false;
                }
            }
        }
        return false;
    }

    public static BladeDanceSettings disabled() {
        return new BladeDanceSettings(false, 0.0D, 0.0D, 0.0D, 0, false);
    }
}
