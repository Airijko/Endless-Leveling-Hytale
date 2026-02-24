package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class PredatorAugment extends YamlAugment
        implements AugmentHooks.OnKillAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "predator";

    private final int maxStacks;
    private final long durationMillis;
    private final double strengthPerStack;
    private final double hastePerStack;
    private final double defensePenalty;

    public PredatorAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
        this.durationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "duration"));
        this.strengthPerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
        this.hastePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
        Map<String, Object> debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.defensePenalty = AugmentValueReader.getNestedDouble(debuffs, 0.0D, "defense", "value");
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        var state = runtime.getState(ID);
        int stacks = AugmentUtils.setStacksWithNotify(runtime,
                ID,
                state.getStacks() + 1,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getKillerRef()),
                getName());
        state.setExpiresAt(System.currentTimeMillis() + durationMillis);
        applyAttributeBonuses(runtime, stacks, state.getExpiresAt());
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    0,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    getName());
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
            return context.getDamage();
        }
        if (state.getStacks() <= 0) {
            return context.getDamage();
        }
        applyAttributeBonuses(runtime, state.getStacks(), state.getExpiresAt());
        double bonus = state.getStacks() * strengthPerStack;
        return (float) (context.getDamage() * (1.0D + bonus));
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stacks, long expiresAt) {
        if (runtime == null) {
            return;
        }
        long duration = expiresAt > 0L ? Math.max(0L, expiresAt - System.currentTimeMillis()) : 0L;
        double hasteBonus = stacks * hastePerStack * 100.0D;
        double strengthBonus = stacks * strengthPerStack * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.HASTE, ID + "_haste", hasteBonus, duration);
        runtime.setAttributeBonus(SkillAttributeType.STRENGTH, ID + "_str", strengthBonus, duration);

        double defenseValue = stacks >= maxStacks ? 0.0D : defensePenalty * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.DEFENSE, ID + "_def", defenseValue, duration);
    }
}
