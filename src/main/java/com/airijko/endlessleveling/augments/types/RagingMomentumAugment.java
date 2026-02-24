package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.logger.HytaleLogger;

public final class RagingMomentumAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "raging_momentum";

    private final double perStackStrength; // percent per stack
    private final double perStackSorcery; // percent per stack
    private final int maxStacks;
    private final double durationPerStackSeconds;
    private final double decayPerSecond; // stacks lost per second after duration
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public RagingMomentumAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.perStackStrength = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
        this.perStackSorcery = AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery", "value");
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
        decayIfNeeded(state,
                runtime,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                now);

        int stacks = Math.max(0, state.getStacks());
        if (stacks < maxStacks) {
            stacks = AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    stacks + 1,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    getName());
        }

        // Refresh stack duration window
        long extendMillis = AugmentUtils.secondsToMillis(durationPerStackSeconds);
        if (extendMillis > 0L) {
            state.setExpiresAt(now + extendMillis);
        }
        state.setLastProc(now);

        double strengthBonus = stacks * perStackStrength;
        double sorceryBonus = stacks * perStackSorcery;
        if (stacks >= maxStacks && maxStacks > 0) {
            strengthBonus *= 2.0D; // “doubles at cap” behaviour
            sorceryBonus *= 2.0D;
        }

        long durationMillis = state.getExpiresAt() > 0L ? state.getExpiresAt() - now : 0L;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthBonus * 100.0D,
                durationMillis);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBonus * 100.0D,
                durationMillis);

        double dbgStr = runtime.getAttributeBonus(SkillAttributeType.STRENGTH, now);
        double dbgSorc = runtime.getAttributeBonus(SkillAttributeType.SORCERY, now);
        LOGGER.atFine().log(
                "RagingMomentum set bonus: stacks=%d str=%.2f sorc=%.2f durMs=%d player=%s totalsNow str=%.2f sorc=%.2f expiresAt=%d now=%d",
                stacks,
                strengthBonus * 100.0D,
                sorceryBonus * 100.0D,
                durationMillis,
                context.getPlayerData().getPlayerName(),
                dbgStr,
                dbgSorc,
                state.getExpiresAt(),
                now);

        return context.getDamage();
    }

    private void decayIfNeeded(AugmentState state, AugmentRuntimeState runtime, PlayerRef playerRef, long now) {
        if (state == null || runtime == null) {
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
            newStacks = AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    newStacks,
                    maxStacks,
                    playerRef,
                    getName());
        }
        if (newStacks == 0) {
            state.setExpiresAt(0L);
        } else {
            // keep sliding window moving to allow further decay
            state.setExpiresAt(now);
        }
    }
}
