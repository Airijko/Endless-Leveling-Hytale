package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class ArcaneMasteryAugment extends Augment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "arcane_mastery";

    private final double sorceryToFlowRatio;

    public ArcaneMasteryAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.sorceryToFlowRatio = AugmentValueReader
                .getNestedDouble(buffs, 0.0D, "mana_from_sorcery", "value");
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }

        double sorcery = AugmentUtils.resolveSorcery(context);
        double flowBonus = sorcery * sorceryToFlowRatio;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_flow",
                SkillAttributeType.FLOW,
                flowBonus,
                0L);
    }
}
