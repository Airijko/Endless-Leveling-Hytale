package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;

import java.util.Map;

public final class BloodEchoAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnKillAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "blood_echo";

    private final double percent;
    private final double durationSeconds;
    private final int maxStacks;

    public BloodEchoAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_over_time");
        this.percent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.durationSeconds = AugmentValueReader.getDouble(heal, "duration", 3.0D);
        this.maxStacks = AugmentValueReader.getInt(heal, "max_stacks", 5);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        EntityStatMap attackerStats = context.getAttackerStats();
        if (runtime == null || attackerStats == null || percent <= 0.0D) {
            return context.getDamage();
        }
        double healAmount = context.getDamage() * percent;
        var state = runtime.getState(ID);
        int stacks = Math.min(maxStacks, state.getStacks() + 1);
        state.setStacks(stacks);
        state.setStoredValue(state.getStoredValue() + healAmount);
        state.setExpiresAt(System.currentTimeMillis() + AugmentUtils.secondsToMillis(durationSeconds));
        return context.getDamage();
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime != null) {
            runtime.getState(ID).clear();
        }
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        EntityStatMap stats = context.getStatMap();
        if (runtime == null || stats == null) {
            return;
        }
        var state = runtime.getState(ID);
        if (state.getStoredValue() <= 0.0D) {
            return;
        }
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            state.clear();
            return;
        }
        double perSecond = state.getStoredValue() / Math.max(0.001D, durationSeconds);
        double healAmount = perSecond * context.getDeltaSeconds();
        float applied = AugmentUtils.heal(stats, healAmount);
        if (applied > 0f) {
            state.setStoredValue(Math.max(0.0D, state.getStoredValue() - applied));
        }
    }
}
