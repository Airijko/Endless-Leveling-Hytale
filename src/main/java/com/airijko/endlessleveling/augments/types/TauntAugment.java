package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class TauntAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "taunt";

    private final double defensePercent;

    public TauntAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.defensePercent = AugmentValueReader.getNestedDouble(buffs, 0.0D, "resistance", "value");
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        // Map the resistance percent to defense attribute value so it shows in stat
        // readouts.
        double defenseValue = defensePercent * 100.0D;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_def",
                SkillAttributeType.DEFENSE,
                defenseValue,
                0L);
    }
}
