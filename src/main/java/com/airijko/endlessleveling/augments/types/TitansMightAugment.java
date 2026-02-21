package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class TitansMightAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "titans_might";

    public TitansMightAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Strength-from-health needs stat mapping; placeholder.
    }
}
