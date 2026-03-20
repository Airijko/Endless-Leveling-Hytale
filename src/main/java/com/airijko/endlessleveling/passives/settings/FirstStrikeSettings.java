package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolved configuration for the First Strike passive, including bonus values
 * and cooldown.
 */
public record FirstStrikeSettings(boolean enabled,
    double bonusPercent,
    long cooldownMillis,
    double flatBonusDamage,
    double trueDamageFlatBonus,
    double trueDamageConversionPercent,
    double hasteBonusPercent,
    boolean normalBonusDamage,
    boolean suppressOnHit,
    boolean allowAugmentStacking,
    boolean resetOnKill,
    boolean requireOutOfCombatCooldown) {

    private static final double DEFAULT_COOLDOWN_SECONDS = 30.0D;
    private static final double DEFAULT_FLAT_BONUS_DAMAGE = 25.0D;

    public static FirstStrikeSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.FIRST_STRIKE);
        if (definitions.isEmpty()) {
            return disabled();
        }

        double bonusPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.FIRST_STRIKE));
        double cooldownSum = 0.0D;
        int cooldownSources = 0;
        double resolvedFlatBonusDamage = 0.0D;
        double resolvedTrueDamageFlatBonus = 0.0D;
        double resolvedTrueDamageConversionPercent = 0.0D;
        double resolvedHasteBonusPercent = 0.0D;
        boolean suppressOnHit = true;
        boolean allowAugmentStacking = false;
        boolean resetOnKill = false;
        boolean requireOutOfCombatCooldown = false;
        boolean normalBonusDamage = true;

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            Map<String, Object> props = definition.properties();

            double flatCandidate = parsePositiveDouble(props != null ? props.get("flat_bonus_damage") : null);
            if (flatCandidate > 0) {
                // FIRST_STRIKE stacks as UNIQUE, so keep the highest configured flat bonus.
                resolvedFlatBonusDamage = Math.max(resolvedFlatBonusDamage, flatCandidate);
            }

            double trueFlatCandidate = parsePositiveDouble(props != null ? props.get("true_damage_flat_bonus") : null);
            if (trueFlatCandidate > 0) {
                resolvedTrueDamageFlatBonus = Math.max(resolvedTrueDamageFlatBonus, trueFlatCandidate);
            }

            double trueConversionCandidate = parsePercent(props != null
                    ? firstNonNull(props.get("true_damage_conversion"), props.get("true_damage_conversion_percent"))
                    : null);
            if (trueConversionCandidate > 0) {
                resolvedTrueDamageConversionPercent = Math.max(resolvedTrueDamageConversionPercent,
                        trueConversionCandidate);
            }

            double hasteCandidate = parsePercent(props != null
                    ? firstNonNull(props.get("haste_bonus"), props.get("haste_bonus_percent"))
                    : null);
            if (hasteCandidate > 0) {
                resolvedHasteBonusPercent = Math.max(resolvedHasteBonusPercent, hasteCandidate * 100.0D);
            }

            suppressOnHit &= parseBoolean(props != null ? props.get("suppress_on_hit") : null, true);
            normalBonusDamage &= parseBoolean(props != null
                    ? firstNonNull(props.get("normal_bonus_damage"), props.get("enable_normal_bonus_damage"))
                    : null, true);
            allowAugmentStacking |= parseBoolean(props != null
                    ? firstNonNull(props.get("allow_augment_stacking"), props.get("stack_with_first_strike_augment"))
                    : null, false);
            resetOnKill |= parseBoolean(props != null ? props.get("reset_on_kill") : null, false);
            requireOutOfCombatCooldown |= parseBoolean(props != null
                    ? firstNonNull(props.get("out_of_combat_cooldown"), props.get("require_out_of_combat"))
                    : null,
                    false);

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
        if (normalBonusDamage && resolvedFlatBonusDamage <= 0.0D) {
            resolvedFlatBonusDamage = DEFAULT_FLAT_BONUS_DAMAGE;
        }

        boolean enabled = (normalBonusDamage && (bonusPercent > 0.0D || resolvedFlatBonusDamage > 0.0D))
                || resolvedTrueDamageFlatBonus > 0.0D
                || resolvedTrueDamageConversionPercent > 0.0D
                || resolvedHasteBonusPercent > 0.0D;
        if (!enabled) {
            return disabled();
        }

        return new FirstStrikeSettings(
                true,
                bonusPercent,
                cooldownMillis,
                resolvedFlatBonusDamage,
                resolvedTrueDamageFlatBonus,
                resolvedTrueDamageConversionPercent,
                resolvedHasteBonusPercent,
                normalBonusDamage,
                suppressOnHit,
                allowAugmentStacking,
                resetOnKill,
                requireOutOfCombatCooldown);
    }

    public static FirstStrikeSettings disabled() {
        return new FirstStrikeSettings(false,
            0.0D,
            0L,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            true,
            true,
            false,
            false,
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

    private static boolean parseBoolean(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String string) {
            String normalized = string.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)
                    || "on".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)
                    || "off".equals(normalized)) {
                return false;
            }
        }
        return fallback;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}
