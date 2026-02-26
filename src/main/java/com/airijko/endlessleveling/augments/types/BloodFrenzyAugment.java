package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class BloodFrenzyAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "blood_frenzy";

    private final double lifeStealPercent;
    private final double hasteBonus;

    public BloodFrenzyAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.lifeStealPercent = AugmentValueReader.getNestedDouble(buffs, 0.0D, "life_steal", "value") * 100.0D;
        this.hasteBonus = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentUtils.applyLifeSteal(context.getAttackerStats(), context.getDamage(), lifeStealPercent);
        return context.getDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteBonus * 100.0D,
                0L);
    }
}
