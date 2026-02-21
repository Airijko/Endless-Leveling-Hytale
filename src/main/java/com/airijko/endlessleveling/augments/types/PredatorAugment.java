package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class PredatorAugment extends YamlAugment
        implements AugmentHooks.OnKillAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "predator";

    private final int maxStacks;
    private final long durationMillis;
    private final double strengthPerStack;

    public PredatorAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
        this.durationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "duration"));
        this.strengthPerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        var state = runtime.getState(ID);
        int stacks = Math.min(maxStacks, state.getStacks() + 1);
        state.setStacks(stacks);
        state.setExpiresAt(System.currentTimeMillis() + durationMillis);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        var state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            state.clear();
            return context.getDamage();
        }
        if (state.getStacks() <= 0) {
            return context.getDamage();
        }
        double bonus = state.getStacks() * strengthPerStack;
        return (float) (context.getDamage() * (1.0D + bonus));
    }
}
