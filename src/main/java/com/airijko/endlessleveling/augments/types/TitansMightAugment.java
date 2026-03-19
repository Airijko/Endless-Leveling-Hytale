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
    private final double hasteDebuff;

    public TitansMightAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.percentOfHealthToStrength = AugmentValueReader
                .getNestedDouble(buffs, 0.0D, "strength_from_max_health", "value");
        var debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.hasteDebuff = AugmentUtils.normalizeConfiguredDebuffMultiplier(
            AugmentValueReader.getNestedDouble(debuffs, 0.0D, "haste", "value"));
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
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteDebuff * 100.0D,
                0L);
    }
}
