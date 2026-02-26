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

    private final double bleedHealPercent;
    private final double durationSeconds;
    private final int maxStacks;
    private final boolean resetOnKill;

    public BloodEchoAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_over_time");
        this.bleedHealPercent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.durationSeconds = AugmentValueReader.getDouble(heal, "duration", 3.0D);
        this.maxStacks = AugmentValueReader.getInt(heal, "max_stacks", 5);
        this.resetOnKill = AugmentValueReader.getBoolean(heal, "reset_on_kill", true);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null || bleedHealPercent <= 0.0D || context.getDamage() <= 0f) {
            return context.getDamage();
        }
        var state = runtime.getState(ID);
        AugmentUtils.setStacksWithNotify(runtime,
                ID,
                state.getStacks() + 1,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                getName());
        state.setStoredValue(state.getStoredValue() + context.getDamage());
        state.setExpiresAt(System.currentTimeMillis() + AugmentUtils.secondsToMillis(durationSeconds));
        return context.getDamage();
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (!resetOnKill || runtime == null) {
            return;
        }
        var state = runtime.getState(ID);
        if (state.getStoredValue() <= 0.0D && state.getStacks() <= 0) {
            return;
        }
        AugmentUtils.setStacksWithNotify(runtime,
                ID,
                0,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getKillerRef()),
                getName());
        state.clear();
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
            AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    0,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef()),
                    getName());
            state.clear();
            return;
        }
        double consumeAmount = state.getStoredValue()
                * Math.max(0.0D, bleedHealPercent)
                * Math.max(0.0D, context.getDeltaSeconds());
        consumeAmount = Math.min(state.getStoredValue(), consumeAmount);
        if (consumeAmount <= 0.0D) {
            return;
        }
        AugmentUtils.heal(stats, consumeAmount);
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - consumeAmount));
        if (state.getStoredValue() <= 0.0001D) {
            AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    0,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef()),
                    getName());
            state.clear();
        }
    }
}
