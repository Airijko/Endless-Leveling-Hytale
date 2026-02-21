package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

public final class DrainAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "drain";

    private final double percent;

    public DrainAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        this.percent = AugmentValueReader.getNestedDouble(passives, 0.0D, "bonus_damage_on_hit", "value") * 100.0D;
    }

    public double getPercent() {
        return percent;
    }

    public static double bonusDamage(EntityStatMap targetStats, double percent) {
        if (targetStats == null || percent <= 0.0D) {
            return 0.0D;
        }
        var health = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return 0.0D;
        }
        return Math.max(0.0D, health.get() * (percent / 100.0D));
    }

    public float onHit(AugmentHooks.HitContext context) {
        double extra = bonusDamage(context.getTargetStats(), getPercent());
        if (extra <= 0.0D) {
            return context.getDamage();
        }
        float updated = context.getDamage() + (float) extra;
        context.getRuntimeState().getState(ID).setLastProc(System.currentTimeMillis());
        return updated;
    }
}
