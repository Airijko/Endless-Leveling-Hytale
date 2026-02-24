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

    public OverdriveAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.critDamagePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "crit_damage", "value");
        this.hastePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }
        var state = runtime.getState(ID);
        int stacks = AugmentUtils.setStacksWithNotify(runtime,
                ID,
                state.getStacks() + 1,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                getName());
        double bonus = stacks * critDamagePerStack;
        applyAttributeBonuses(runtime, stacks);
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
        applyAttributeBonuses(runtime, stacks);
        return context.getIncomingDamage();
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stacks) {
        if (runtime == null) {
            return;
        }
        double hasteBonus = stacks * hastePerStack * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.HASTE, ID + "_haste", hasteBonus, 0L);
    }
}
