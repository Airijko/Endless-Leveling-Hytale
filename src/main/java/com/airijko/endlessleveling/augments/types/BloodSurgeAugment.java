package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class BloodSurgeAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "blood_surge";

    private final double maxLifeStealPercent;
    private final double maxMissingPercent;
    private final double healToDamagePercent;

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
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> lifeStealScaling = AugmentValueReader.getMap(buffs, "life_steal_scaling");
        this.maxLifeStealPercent = normalizePercentValue(
                AugmentValueReader.getDouble(lifeStealScaling, "max_value", 0.0D));
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
        Map<String, Object> healToDamage = AugmentValueReader.getMap(passives, "heal_to_damage");
        this.healToDamagePercent = AugmentValueReader.getDouble(healToDamage, "value", 0.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }

        double bonusFromCarry = runtime.getState(ID).getStoredValue();
        float damage = context.getDamage() + (float) bonusFromCarry;
        runtime.getState(ID).setStoredValue(0.0D);

        var attackerStats = context.getAttackerStats();
        EntityStatValue hp = attackerStats == null ? null
                : attackerStats.get(DefaultEntityStatTypes.getHealth());
        double missingRatio = 0.0D;
        if (hp != null && hp.getMax() > 0f) {
            missingRatio = Math.min(maxMissingPercent, Math.max(0.0D, (hp.getMax() - hp.get()) / hp.getMax()));
        }
        double lifeStealPercent = maxLifeStealPercent
                * (maxMissingPercent <= 0.0D ? 0.0D : missingRatio / maxMissingPercent);
        lifeStealPercent = Math.max(0.0D, Math.min(maxLifeStealPercent, lifeStealPercent));
        if (lifeStealPercent > 0.0D) {
            double potentialHeal = damage * (lifeStealPercent / 100.0D);
            double actualHeal = potentialHeal;
            if (hp != null) {
                actualHeal = Math.min(potentialHeal, hp.getMax() - hp.get());
            }
            AugmentUtils.applyLifeSteal(attackerStats, damage, lifeStealPercent);
            if (healToDamagePercent > 0.0D && actualHeal > 0.0D) {
                runtime.getState(ID).setStoredValue(actualHeal * healToDamagePercent);
            }
        }

        return damage;
    }
}
