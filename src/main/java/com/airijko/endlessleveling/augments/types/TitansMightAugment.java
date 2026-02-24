package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class TitansMightAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "titans_might";

    private final double percentOfHealthToStrength;

    public TitansMightAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.percentOfHealthToStrength = AugmentValueReader
                .getNestedDouble(buffs, 0.0D, "strength_from_max_health", "value");
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        var statMap = context.getStatMap();
        if (runtime == null || statMap == null) {
            return;
        }
        double maxHealth = AugmentUtils.getMaxHealth(statMap);
        double strengthBonus = maxHealth * percentOfHealthToStrength;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthBonus,
                0L);
    }
}
