package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class OverdriveAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "overdrive";

    private final double critDamagePerStack;
    private final int maxStacks;

    public OverdriveAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.critDamagePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "crit_damage", "value");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }
        var state = runtime.getState(ID);
        int stacks = Math.min(maxStacks, state.getStacks() + 1);
        state.setStacks(stacks);
        double bonus = stacks * critDamagePerStack;
        return (float) (context.getDamage() * (1.0D + bonus));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getIncomingDamage();
        }
        var state = runtime.getState(ID);
        int stacks = Math.max(0, state.getStacks() - 1);
        state.setStacks(stacks);
        return context.getIncomingDamage();
    }
}
