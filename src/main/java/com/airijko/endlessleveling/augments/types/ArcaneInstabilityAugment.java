package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.YamlAugment;

public final class ArcaneInstabilityAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "arcane_instability";

    public ArcaneInstabilityAugment(AugmentDefinition definition) {
        super(definition);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        // Sorcery stat adjustments require stat mapping; no-op placeholder.
    }
}
