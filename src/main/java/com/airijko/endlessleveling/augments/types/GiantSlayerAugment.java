package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class GiantSlayerAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "giant_slayer";

    private final double maxBonus;
    private final double maxRatio;

    public GiantSlayerAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_vs_hp_ratio");
        this.maxBonus = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonus, "max_value", 0.0D));
        this.maxRatio = AugmentValueReader.getDouble(bonus, "max_ratio", 1.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        EntityStatValue targetHp = context.getTargetStats() == null ? null
                : context.getTargetStats().get(DefaultEntityStatTypes.getHealth());
        EntityStatValue attackerHp = context.getAttackerStats() == null ? null
                : context.getAttackerStats().get(DefaultEntityStatTypes.getHealth());
        if (targetHp == null || attackerHp == null || attackerHp.get() <= 0f || maxRatio <= 1.0D) {
            return context.getDamage();
        }
        double ratio = targetHp.get() / attackerHp.get();
        if (ratio <= 1.0D) {
            return context.getDamage();
        }
        double t = Math.min(1.0D, (ratio - 1.0D) / (maxRatio - 1.0D));
        double bonus = maxBonus * t;
        float baseDamage = context.getBaseDamage();
        if (baseDamage <= 0f || bonus <= 0.0D) {
            return context.getDamage();
        }
        float bonusDamage = (float) (baseDamage * bonus);
        return context.getDamage() + bonusDamage;
    }
}
