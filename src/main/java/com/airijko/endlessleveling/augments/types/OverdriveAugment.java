package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class OverdriveAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "overdrive";

    private final double critDamagePerStack;
    private final double hastePerStack;
    private final int maxStacks;
    private final long stackDurationMillis;

    public OverdriveAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> duration = AugmentValueReader.getMap(passives, "duration");
        this.critDamagePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "crit_damage", "value");
        this.hastePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
        this.stackDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(duration, "seconds", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();

        if (stackDurationMillis > 0L && state.getStacks() > 0 && state.isExpired(now)) {
            AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    0,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    getName());
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
        }

        int stacks = AugmentUtils.setStacksWithNotify(runtime,
                ID,
                state.getStacks() + 1,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                getName());

        if (stackDurationMillis > 0L) {
            state.setExpiresAt(now + stackDurationMillis);
        }

        double bonus = stacks * critDamagePerStack;
        applyAttributeBonuses(runtime, stacks, state.getExpiresAt());
        return (float) (context.getDamage() * (1.0D + bonus));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getIncomingDamage();
        }
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();

        if (stackDurationMillis > 0L && state.getStacks() > 0 && state.isExpired(now)) {
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
            return context.getIncomingDamage();
        }

        int stacks = Math.max(0, state.getStacks() - 1);
        state.setStacks(stacks);
        if (stacks <= 0) {
            state.setExpiresAt(0L);
        }
        applyAttributeBonuses(runtime, stacks, state.getExpiresAt());
        return context.getIncomingDamage();
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stacks, long expiresAt) {
        if (runtime == null) {
            return;
        }
        double hasteBonus = stacks * hastePerStack * 100.0D;
        long expiresAtMillis = stackDurationMillis > 0L && stacks > 0 ? Math.max(0L, expiresAt) : 0L;
        runtime.setAttributeBonus(SkillAttributeType.HASTE, ID + "_haste", hasteBonus, expiresAtMillis);
    }
}
