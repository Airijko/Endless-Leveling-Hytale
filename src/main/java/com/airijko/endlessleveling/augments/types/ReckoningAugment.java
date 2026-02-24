package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class ReckoningAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "reckoning";

    private final double maxBonus;

    public ReckoningAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage");
        this.maxBonus = AugmentValueReader.getDouble(bonus, "max_bonus_damage", 0.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        EntityStatValue hp = context.getAttackerStats() == null ? null
                : context.getAttackerStats().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }
        double missing = (hp.getMax() - hp.get()) / hp.getMax();
        double bonus = maxBonus * Math.max(0.0D, missing);
        return (float) (context.getDamage() * (1.0D + bonus));
    }
}
