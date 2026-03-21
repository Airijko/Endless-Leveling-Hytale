package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;

import java.util.Map;

public final class BloodEchoAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "blood_echo";

    private final double lifeStealPercent;
    private final double healingToDamageRatio;

    public BloodEchoAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.lifeStealPercent = normalizePercentPoints(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "lifesteal", "value"));
        this.healingToDamageRatio = normalizeRatio(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "healing_to_damage", "value"));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        float damage = Math.max(0f, context.getDamage());
        if (damage <= 0f || lifeStealPercent <= 0.0D) {
            return context.getDamage();
        }

        double healAmount = damage * (lifeStealPercent / 100.0D);
        if (healAmount <= 0.0D) {
            return context.getDamage();
        }

        // Heal normally, but bonus damage conversion uses potential healing even at full HP.
        AugmentUtils.heal(context.getAttackerStats(), healAmount);

        double bonusDamage = healAmount * healingToDamageRatio;
        if (bonusDamage <= 0.0D) {
            return context.getDamage();
        }

        return context.getDamage() + (float) bonusDamage;
    }

    private static double normalizePercentPoints(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        if (value <= 1.0D) {
            return Math.max(0.0D, value * 100.0D);
        }
        return Math.max(0.0D, value);
    }

    private static double normalizeRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        double normalized = value > 1.0D ? value / 100.0D : value;
        return Math.max(0.0D, Math.min(1.0D, normalized));
    }
}
