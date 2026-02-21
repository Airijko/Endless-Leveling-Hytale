package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.logger.HytaleLogger;

public final class RagingMomentumAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "raging_momentum";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final double perStackBonus; // percent per stack
    private final int maxStacks;
    private final double durationPerStackSeconds;
    private final double decayPerSecond; // stacks lost per second after duration

    public RagingMomentumAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.perStackBonus = AugmentValueReader.getDouble(buffs, "strength", 0.0D);
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 20);
        this.durationPerStackSeconds = AugmentValueReader.getDouble(buffs, "duration_per_stack", 8.0D);
        this.decayPerSecond = AugmentValueReader.getDouble(buffs, "decay_per_second", 0.0D);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        decayIfNeeded(state, now);

        int stacks = Math.max(0, state.getStacks());
        if (stacks < maxStacks) {
            stacks += 1;
            state.setStacks(stacks);
        }

        // Refresh stack duration window
        long extendMillis = AugmentUtils.secondsToMillis(durationPerStackSeconds);
        if (extendMillis > 0L) {
            state.setExpiresAt(now + extendMillis);
        }
        state.setLastProc(now);

        double bonusPercent = stacks * perStackBonus;
        if (stacks >= maxStacks && maxStacks > 0) {
            bonusPercent *= 2.0D; // “doubles at cap” behaviour
        }

        float base = context.getDamage();
        float updated = base + (float) (base * bonusPercent);
        return updated;
    }

    private void decayIfNeeded(com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState state,
            long now) {
        if (state == null) {
            return;
        }
        long expiresAt = state.getExpiresAt();
        if (expiresAt <= 0L || decayPerSecond <= 0.0D) {
            return;
        }
        if (now <= expiresAt) {
            return;
        }
        double elapsedSeconds = (now - expiresAt) / 1000.0D;
        int decay = (int) Math.floor(elapsedSeconds * decayPerSecond);
        if (decay <= 0) {
            return;
        }
        int newStacks = Math.max(0, state.getStacks() - decay);
        if (newStacks != state.getStacks()) {
            state.setStacks(newStacks);
            if (newStacks == 0) {
                state.setExpiresAt(0L);
            } else {
                // keep sliding window moving to allow further decay
                state.setExpiresAt(now);
            }
        }
    }
}
