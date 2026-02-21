package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class SupersonicAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "supersonic";

    public SupersonicAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Haste/strength trade needs stat mapping; no-op placeholder.
    }
}
