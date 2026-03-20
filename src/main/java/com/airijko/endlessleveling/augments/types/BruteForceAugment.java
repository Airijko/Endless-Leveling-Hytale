package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class BruteForceAugment extends Augment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "brute_force";

    private final double strengthMultiplier;
    private final double sorceryMultiplier;

    public BruteForceAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bruteForce = AugmentValueReader.getMap(passives, "brute_force");

        this.strengthMultiplier = Math.max(0.0D,
                AugmentValueReader.getDouble(bruteForce, "strength_multiplier", 1.75D));
        this.sorceryMultiplier = Math.max(0.0D,
                AugmentValueReader.getDouble(bruteForce, "sorcery_multiplier", 1.75D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }

                double critLockMagnitude = 0.0D;
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_precision_lock",
                SkillAttributeType.PRECISION,
                critLockMagnitude,
                0L);
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_ferocity_lock",
                SkillAttributeType.FEROCITY,
                critLockMagnitude,
                0L);

        // Clear first so strength/sorcery snapshots are computed without recursive
        // self-bonus.
        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_str_bonus", SkillAttributeType.STRENGTH,
                0.0D, 0L);
        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_sorc_bonus", SkillAttributeType.SORCERY,
                0.0D, 0L);

        if (context.getSkillManager() == null || context.getPlayerData() == null) {
            return;
        }

        double strengthBase = Math.max(0.0D,
                context.getSkillManager().calculatePlayerStrength(context.getPlayerData()));
        double sorceryBase = Math.max(0.0D, context.getSkillManager().calculatePlayerSorcery(context.getPlayerData()));

        double strengthExtra = strengthBase * Math.max(0.0D, strengthMultiplier - 1.0D);
        double sorceryExtra = sorceryBase * Math.max(0.0D, sorceryMultiplier - 1.0D);

        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_str_bonus", SkillAttributeType.STRENGTH,
                strengthExtra, 0L);
        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_sorc_bonus", SkillAttributeType.SORCERY,
                sorceryExtra, 0L);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        float damage = context.getDamage();
        if (!context.isCritical() || damage <= 0f) {
            return damage;
        }

        double ferocity = context.getSkillManager() != null && context.getPlayerData() != null
                ? Math.max(0.0D, context.getSkillManager().calculatePlayerFerocity(context.getPlayerData()))
                : 0.0D;
        double critMultiplier = 1.0D + (ferocity / 100.0D);
        if (critMultiplier <= 0.0D) {
            return damage;
        }

        return (float) (damage / critMultiplier);
    }
}
