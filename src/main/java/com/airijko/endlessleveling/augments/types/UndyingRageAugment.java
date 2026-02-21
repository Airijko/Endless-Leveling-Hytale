package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;

import java.util.Map;

public final class UndyingRageAugment extends YamlAugment
        implements AugmentHooks.OnLowHpAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "undying_rage";

    private final long durationMillis;
    private final double minHealthPercent;
    private final double maxBonusDamage;

    public UndyingRageAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> rage = AugmentValueReader.getMap(passives, "rage_damage");
        this.durationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(rage, "duration", 0.0D));
        this.minHealthPercent = AugmentValueReader.getDouble(rage, "min_health_percent", 0.0D);
        this.maxBonusDamage = AugmentValueReader.getDouble(rage, "max_bonus_damage", 0.0D);
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        var hp = context.getStatMap() == null ? null
                : context.getStatMap().get(
                        com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getIncomingDamage();
        }
        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        if (state.getExpiresAt() > now) {
            float minHp = (float) (hp.getMax() * minHealthPercent);
            float projected = hp.get() - context.getIncomingDamage();
            if (projected < minHp) {
                return Math.max(0.0f, hp.get() - minHp);
            }
            return context.getIncomingDamage();
        }
        double projectedHp = hp.get() - context.getIncomingDamage();
        if (projectedHp > hp.getMax() * 0.1D) {
            return context.getIncomingDamage();
        }
        state.setExpiresAt(now + durationMillis);
        return Math.max(0.0f, hp.get() - (float) (hp.getMax() * minHealthPercent));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        var runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        if (state.getExpiresAt() <= System.currentTimeMillis()) {
            return context.getDamage();
        }
        var hp = context.getAttackerStats() == null ? null
                : context.getAttackerStats().get(
                        com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }
        double missing = (hp.getMax() - hp.get()) / hp.getMax();
        double bonus = maxBonusDamage * Math.max(0.0D, missing);
        return (float) (context.getDamage() * (1.0D + bonus));
    }
}
