package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class AmbushAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "ambush";

    public AmbushAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Visual stealth/buff effects require engine hooks; no stat-safe changes
        // applied here.
    }
}
