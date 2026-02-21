package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class WitherAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "wither";

    private final double percentPerSecond;
    private final double durationSeconds;

    public WitherAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> wither = AugmentValueReader.getMap(passives, "wither");
        this.percentPerSecond = AugmentValueReader.getDouble(wither, "value", 0.0D);
        this.durationSeconds = AugmentValueReader.getDouble(wither, "duration", 0.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        EntityStatMap targetStats = context.getTargetStats();
        if (targetStats == null) {
            return context.getDamage();
        }
        var hp = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }
        double totalPercent = percentPerSecond * durationSeconds;
        double extra = hp.getMax() * totalPercent;
        return (float) (context.getDamage() + extra);
    }
}
