package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class ManaInfusionAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "mana_infusion";

    private final double percentOfManaToSorcery;

    public ManaInfusionAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.percentOfManaToSorcery = AugmentValueReader
                .getNestedDouble(buffs, 0.0D, "sorcery_from_mana", "value");
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        var statMap = context.getStatMap();
        if (runtime == null || statMap == null) {
            return;
        }
        double currentMana = AugmentUtils.getCurrentMana(statMap);
        double sorceryBonus = currentMana * percentOfManaToSorcery;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBonus,
                0L);
    }
}
