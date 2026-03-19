package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class SupersonicAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "supersonic";

    private final double hasteBonus;
    private final double strengthPenalty;

    public SupersonicAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        var debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.hasteBonus = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
        this.strengthPenalty = AugmentUtils.normalizeConfiguredDebuffMultiplier(
            AugmentValueReader.getNestedDouble(debuffs, 0.0D, "strength", "value"));
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
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthPenalty * 100.0D,
                0L);
    }
}
