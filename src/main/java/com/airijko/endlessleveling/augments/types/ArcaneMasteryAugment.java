package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class ArcaneMasteryAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "arcane_mastery";

    public ArcaneMasteryAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Mana/sorcery conversion would require stat mapping; placeholder only.
    }
}
