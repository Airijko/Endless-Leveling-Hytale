package com.airijko.endlessleveling.passives.settings;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import java.util.List;
import java.util.Map;

/**
 * Parsed True Bolts passive settings.
 */
public record TrueBoltsSettings(boolean enabled,
        double flatTrueDamage,
        double trueDamagePercent,
        double maxHealthTrueDamagePercent,
        long internalCooldownMillis,
        double monsterTrueDamageCap) {

    private static final long DEFAULT_INTERNAL_COOLDOWN_MILLIS = 400L;
    private static final TrueBoltsSettings DISABLED = new TrueBoltsSettings(false, 0.0D, 0.0D, 0.0D, 0L, 0.0D);

    public static TrueBoltsSettings fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return DISABLED;
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.TRUE_BOLTS);
        if (definitions.isEmpty()) {
            return DISABLED;
        }

        double flatTrueDamage = 0.0D;
        double trueDamagePercent = 0.0D;
        double maxHealthTrueDamagePercent = 0.0D;
        long internalCooldownMillis = DEFAULT_INTERNAL_COOLDOWN_MILLIS;
        double monsterTrueDamageCap = 0.0D;

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }

            Map<String, Object> props = definition.properties();

            double flat = Math.max(0.0D, definition.value());
            double explicitFlat = parsePositiveDouble(props == null ? null : props.get("flat_true_damage"));
            if (explicitFlat > 0.0D) {
                flat = explicitFlat;
            }
            flatTrueDamage += flat;

            trueDamagePercent += parsePercent(props == null ? null : props.get("true_damage_percent"));

            maxHealthTrueDamagePercent += parsePercent(props == null
                    ? null
                    : firstNonNull(
                            props.get("max_health_true_damage_percent"),
                            props.get("max_health_true_damage")));

            long cooldownCandidate = parseInternalCooldownMillis(props);
            if (cooldownCandidate >= 0L) {
                internalCooldownMillis = cooldownCandidate;
            }

            double capCandidate = parsePositiveDouble(props == null
                    ? null
                    : firstNonNull(
                            props.get("monster_true_damage_cap"),
                            props.get("monster_cap"),
                            props.get("max_true_damage_vs_monsters")));
            if (capCandidate > 0.0D) {
                monsterTrueDamageCap = capCandidate;
            }
        }

        if (flatTrueDamage <= 0.0D && trueDamagePercent <= 0.0D && maxHealthTrueDamagePercent <= 0.0D) {
            return DISABLED;
        }

        return new TrueBoltsSettings(true,
                flatTrueDamage,
                trueDamagePercent,
                maxHealthTrueDamagePercent,
                Math.max(0L, internalCooldownMillis),
                monsterTrueDamageCap);
    }

    private static double parsePositiveDouble(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value > 0.0D ? value : 0.0D;
        }
        if (raw instanceof String stringValue) {
            try {
                double value = Double.parseDouble(stringValue.trim());
                return value > 0.0D ? value : 0.0D;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0D;
    }

    private static double parsePercent(Object raw) {
        double value = parsePositiveDouble(raw);
        if (value <= 0.0D) {
            return 0.0D;
        }
        return value > 1.0D ? value / 100.0D : value;
    }

    private static long parseInternalCooldownMillis(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return -1L;
        }

        double explicitMillis = parsePositiveDouble(props.get("internal_cooldown_ms"));
        if (explicitMillis > 0.0D) {
            return Math.round(explicitMillis);
        }

        double seconds = parsePositiveDouble(firstNonNull(
                props.get("internal_cooldown_seconds"),
                props.get("internal_cooldown")));
        if (seconds > 0.0D) {
            return Math.round(seconds * 1000.0D);
        }

        return -1L;
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
