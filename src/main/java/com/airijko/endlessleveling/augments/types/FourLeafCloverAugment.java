package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class FourLeafCloverAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "four_leaf_clover";

    private final double luckBonus;

    public FourLeafCloverAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.luckBonus = AugmentValueReader.getNestedDouble(buffs, 0.0D, "luck", "value");
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }
        // Preserve the configured luck percent in runtime state for
        // debugging/inspection.
        context.getRuntimeState().getState(ID).setStoredValue(Math.max(0.0D, luckBonus * 100.0D));

        // Keep the legacy Discipline source pinned to zero so stale runtime data cannot
        // continue applying XP gain after migrating Four Leaf Clover to luck-based
        // scaling.
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_discipline",
                SkillAttributeType.DISCIPLINE,
                0.0D,
                0L);
    }
}
