package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class ManaInfusionAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "mana_infusion";

    public ManaInfusionAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Sorcery-from-mana requires stat mapping; placeholder.
    }
}
