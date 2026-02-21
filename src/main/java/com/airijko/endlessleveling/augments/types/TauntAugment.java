package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class TauntAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "taunt";

    public TauntAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Taunt/resistance require combat system hooks; placeholder.
    }
}
