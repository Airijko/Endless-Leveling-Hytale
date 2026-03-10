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
        implements AugmentHooks.OnKillAugment, AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "predator";

    private final int maxStacks;
    private final long durationMillis;
    private final double strengthPerStack;
    private final double hastePerStack;
    private final double defensePenalty;
    private final boolean defensePenaltyActiveUntilMaxStacks;

    public PredatorAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> duration = AugmentValueReader.getMap(passives, "duration");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
        double durationSeconds = AugmentValueReader.getDouble(duration, "seconds", 0.0D);
        if (durationSeconds <= 0.0D) {
            // Backward compatibility for older predator.yml layout.
            durationSeconds = Math.max(
                    AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "duration"),
                    AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "duration"));
        }
        this.durationMillis = AugmentUtils.secondsToMillis(durationSeconds);
        this.strengthPerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
        this.hastePerStack = AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value");
        Map<String, Object> debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.defensePenalty = AugmentValueReader.getNestedDouble(debuffs, 0.0D, "defense", "value");
        Map<String, Object> defenseDebuff = AugmentValueReader.getMap(debuffs, "defense");
        this.defensePenaltyActiveUntilMaxStacks = AugmentValueReader.getBoolean(defenseDebuff,
                "active_until_max_stacks",
                true);
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
        long now = System.currentTimeMillis();
        state.setExpiresAt(durationMillis > 0L ? now + durationMillis : 0L);
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

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        AugmentRuntimeState runtime = context != null ? context.getRuntimeState() : null;
        if (runtime == null) {
            return;
        }

        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
            return;
        }

        if (state.getStacks() <= 0) {
            applyAttributeBonuses(runtime, 0, 0L);
            return;
        }

        applyAttributeBonuses(runtime, state.getStacks(), state.getExpiresAt());
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stacks, long expiresAt) {
        if (runtime == null) {
            return;
        }
        long expiresAtMillis = durationMillis > 0L && stacks > 0 ? Math.max(0L, expiresAt) : 0L;
        double hasteBonus = stacks * hastePerStack * 100.0D;
        double strengthBonus = stacks * strengthPerStack * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.HASTE, ID + "_haste", hasteBonus, expiresAtMillis);
        runtime.setAttributeBonus(SkillAttributeType.STRENGTH, ID + "_str", strengthBonus, expiresAtMillis);

        boolean removeAtMaxStacks = defensePenaltyActiveUntilMaxStacks && maxStacks > 0 && stacks >= maxStacks;
        double defenseValue = stacks <= 0 || removeAtMaxStacks ? 0.0D : defensePenalty * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.DEFENSE, ID + "_def", defenseValue, expiresAtMillis);
    }
}
