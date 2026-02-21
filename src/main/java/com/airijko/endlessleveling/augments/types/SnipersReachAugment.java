package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class SnipersReachAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "snipers_reach";

    private final double maxValue;

    public SnipersReachAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_by_distance");
        this.maxValue = AugmentValueReader.getDouble(bonus, "max_value", 0.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (!context.isRangedAttack()) {
            return context.getDamage();
        }
        // Distance not available; apply a conservative half bonus for ranged hits.
        double bonus = maxValue * 0.5D;
        return (float) (context.getDamage() * (1.0D + bonus));
    }
}
