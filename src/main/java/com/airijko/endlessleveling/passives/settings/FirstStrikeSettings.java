package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Resolved configuration for Focused Strike.
 *
 * Focused Strike is haste-only: on hit, grant haste for a short duration.
 */
public record FirstStrikeSettings(boolean enabled,
        double bonusPercent,
        long cooldownMillis,
        double flatBonusDamage,
        double trueDamageFlatBonus,
        double trueDamageConversionPercent,
        double hasteBonusPercent,
        long hasteDurationMillis,
        boolean normalBonusDamage,
        boolean resetOnKill) {

    private static final double DEFAULT_HASTE_BONUS_PERCENT = 50.0D;
    private static final double DEFAULT_HASTE_DURATION_SECONDS = 3.0D;

    public static FirstStrikeSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.FOCUSED_STRIKE);
        if (definitions.isEmpty()) {
            return disabled();
        }

        double resolvedHasteBonusPercent = DEFAULT_HASTE_BONUS_PERCENT;
        double resolvedHasteDurationSeconds = DEFAULT_HASTE_DURATION_SECONDS;
        double resolvedCooldownSeconds = 0.0D;
        double resolvedTrueDamageFlatBonus = 0.0D;
        boolean resolvedResetOnKill = false;

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();

            double hasteCandidate = parsePercent(props == null
                    ? null
                    : firstNonNull(props.get("haste_bonus"), props.get("haste_bonus_percent")));
            if (hasteCandidate > 0.0D) {
                resolvedHasteBonusPercent = hasteCandidate * 100.0D;
            }

            double durationCandidate = parsePositiveDouble(props == null
                    ? null
                    : firstNonNull(props.get("haste_duration_seconds"), props.get("haste_duration")));
            if (durationCandidate > 0.0D) {
                resolvedHasteDurationSeconds = durationCandidate;
            }

            double cooldownCandidate = parsePositiveDouble(props == null
                    ? null
                    : firstNonNull(props.get("cooldown"), props.get("cooldown_seconds")));
            if (cooldownCandidate > 0.0D) {
                resolvedCooldownSeconds = Math.max(resolvedCooldownSeconds, cooldownCandidate);
            }

            double trueDamageFlatCandidate = parsePositiveDouble(props == null
                    ? null
                    : firstNonNull(props.get("true_damage_flat_bonus"), props.get("flat_true_damage")));
            if (trueDamageFlatCandidate > 0.0D) {
                resolvedTrueDamageFlatBonus += trueDamageFlatCandidate;
            }

            if (props != null && parseBoolean(props.get("reset_on_kill"))) {
                resolvedResetOnKill = true;
            }
        }

        long resolvedHasteDurationMillis = (long) Math.max(0L, Math.round(resolvedHasteDurationSeconds * 1000.0D));
        long resolvedCooldownMillis = (long) Math.max(0L, Math.round(resolvedCooldownSeconds * 1000.0D));
        boolean enabled = resolvedHasteBonusPercent > 0.0D && resolvedHasteDurationMillis > 0L;
        if (!enabled) {
            return disabled();
        }

        return new FirstStrikeSettings(
                true,
                0.0D,
            resolvedCooldownMillis,
                0.0D,
                resolvedTrueDamageFlatBonus,
                0.0D,
                resolvedHasteBonusPercent,
                resolvedHasteDurationMillis,
                false,
            resolvedResetOnKill);
    }

    public static FirstStrikeSettings disabled() {
        return new FirstStrikeSettings(false,
                0.0D,
                0L,
                0.0D,
                0.0D,
                0.0D,
                0.0D,
                0L,
                true,
                false);
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

    private static double parsePercent(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            return 0.0D;
        }
        double value = raw instanceof Number number ? number.doubleValue() : parsePositiveDouble(raw);
        if (value <= 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            value /= 100.0D;
        }
        return Math.min(1.0D, value);
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        if (raw instanceof String string) {
            String normalized = string.trim().toLowerCase();
            return "true".equals(normalized)
                    || "yes".equals(normalized)
                    || "on".equals(normalized)
                    || "1".equals(normalized);
        }
        return false;
    }
}
