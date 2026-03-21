package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class BloodSurgeAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "blood_surge";

    private static final double DEFAULT_MIN_LIFESTEAL_PERCENT = 5.0D;

    private final double minLifeStealPercent;
    private final double maxLifeStealPercent;
    private final double maxMissingPercent;
    private final double healingToDamageRatio;

    private static double normalizePercentValue(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        if (value <= 1.0D) {
            return Math.max(0.0D, value * 100.0D);
        }
        return Math.max(0.0D, value);
    }

    private static double normalizeRatioValue(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        double normalized = value > 1.0D ? value / 100.0D : value;
        return Math.max(0.0D, Math.min(1.0D, normalized));
    }

    public BloodSurgeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> lifeStealScaling = AugmentValueReader.getMap(passives, "life_steal_scaling");
        this.minLifeStealPercent = normalizePercentValue(
            AugmentValueReader.getDouble(lifeStealScaling,
                "min_value",
                DEFAULT_MIN_LIFESTEAL_PERCENT / 100.0D));
        this.maxLifeStealPercent = normalizePercentValue(
                AugmentValueReader.getDouble(lifeStealScaling, "max_value", 0.0D));
        this.healingToDamageRatio = normalizeRatioValue(
                AugmentValueReader.getDouble(lifeStealScaling, "healing_to_damage", 0.25D));
        double fullAtHealthPercent = AugmentValueReader.getDouble(lifeStealScaling,
                "full_value_at_health_percent",
                -1.0D);
        if (fullAtHealthPercent >= 0.0D) {
            double healthThreshold = normalizeRatioValue(fullAtHealthPercent);
            this.maxMissingPercent = Math.max(0.0D, Math.min(1.0D, 1.0D - healthThreshold));
        } else {
            this.maxMissingPercent = normalizeRatioValue(
                    AugmentValueReader.getDouble(lifeStealScaling, "max_missing_health_percent", 0.0D));
        }
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        float damage = Math.max(0f, context.getDamage());
        if (damage <= 0f) {
            return context.getDamage();
        }

        var attackerStats = context.getAttackerStats();
        EntityStatValue hp = attackerStats == null ? null
                : attackerStats.get(DefaultEntityStatTypes.getHealth());
        double missingRatio = 0.0D;
        if (hp != null && hp.getMax() > 0f) {
            missingRatio = Math.min(maxMissingPercent, Math.max(0.0D, (hp.getMax() - hp.get()) / hp.getMax()));
        }
        double safeMinLifeSteal = Math.max(0.0D, Math.min(minLifeStealPercent, maxLifeStealPercent));
        double safeMaxLifeSteal = Math.max(safeMinLifeSteal, maxLifeStealPercent);
        double normalizedMissing = maxMissingPercent <= 0.0D
                ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, missingRatio / maxMissingPercent));
        double lifeStealPercent = safeMinLifeSteal + ((safeMaxLifeSteal - safeMinLifeSteal) * normalizedMissing);
        lifeStealPercent = Math.max(safeMinLifeSteal, Math.min(safeMaxLifeSteal, lifeStealPercent));
        if (lifeStealPercent <= 0.0D) {
            return damage;
        }

        double healAmount = damage * (lifeStealPercent / 100.0D);
        if (healAmount > 0.0D) {
            // Heal normally, but bonus damage conversion uses potential healing even at full HP.
            AugmentUtils.heal(attackerStats, healAmount);
        }

        double bonusDamage = healAmount * healingToDamageRatio;
        if (bonusDamage > 0.0D) {
            return damage + (float) bonusDamage;
        }

        return damage;
    }
}
