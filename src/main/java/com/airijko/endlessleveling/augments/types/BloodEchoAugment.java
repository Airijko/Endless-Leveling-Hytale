package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;

import java.util.Map;

public final class BloodEchoAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "blood_echo";

    private final double lifeStealPercent;
    private final double healToDamageRatio;

    public BloodEchoAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.lifeStealPercent = normalizePercent(AugmentValueReader.getNestedDouble(buffs, 5.0D, "lifesteal", "value"));
        this.healToDamageRatio = normalizeRatio(
                AugmentValueReader.getNestedDouble(buffs, 0.20D, "healing_to_damage", "value"));
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

        EntityStatMap attackerStats = context.getAttackerStats();
        double healAmount = damage * (lifeStealPercent / 100.0D);
        float appliedHealAmount = 0f;
        if (healAmount > 0.0D) {
            appliedHealAmount = AugmentUtils.heal(attackerStats, healAmount);
        }

        double bonusDamage = appliedHealAmount * healToDamageRatio;
        if (bonusDamage > 0.0D) {
            return damage + (float) bonusDamage;
        }

        return damage;
    }

    private static double normalizePercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        if (Math.abs(value) <= 1.0D) {
            return Math.max(0.0D, value * 100.0D);
        }
        return Math.max(0.0D, value);
    }

    private static double normalizeRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        double normalized = Math.abs(value) > 1.0D ? value / 100.0D : value;
        return Math.max(0.0D, Math.min(1.0D, normalized));
    }
}
