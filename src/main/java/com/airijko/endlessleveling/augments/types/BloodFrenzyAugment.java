package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class BloodFrenzyAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "blood_frenzy";

    private final double lifeStealPercent;

    public BloodFrenzyAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.lifeStealPercent = AugmentValueReader.getNestedDouble(buffs, 0.0D, "life_steal", "value") * 100.0D;
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentUtils.applyLifeSteal(context.getAttackerStats(), context.getDamage(), lifeStealPercent);
        return context.getDamage();
    }
}
