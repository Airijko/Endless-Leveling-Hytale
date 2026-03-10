package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;

public final class ConquerorAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "conqueror";
    public static final long INTERNAL_COOLDOWN_MILLIS = 400L;

    private final double bonusDamagePerStack;
    private final int maxStacks;
    private final double maxStackFlatTrueDamage;
    private final double maxStackTrueDamagePercent;
    private final long stackDurationMillis;

    public ConquerorAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> bonusDamage = AugmentValueReader.getMap(buffs, "bonus_damage");
        Map<String, Object> maxStackBonus = AugmentValueReader.getMap(passives, "max_stack_bonus");
        Map<String, Object> trueDamage = AugmentValueReader.getMap(maxStackBonus, "bonus_true_damage");
        Map<String, Object> duration = AugmentValueReader.getMap(passives, "duration");

        this.bonusDamagePerStack = Math.max(0.0D, AugmentValueReader.getDouble(bonusDamage, "value", 0.0D));
        this.maxStacks = Math.max(1, AugmentValueReader.getInt(buffs, "max_stacks", 1));
        this.maxStackFlatTrueDamage = Math.max(0.0D, AugmentValueReader.getDouble(trueDamage, "value", 0.0D));
        this.maxStackTrueDamagePercent = normalizePercent(AugmentValueReader.getDouble(trueDamage,
                "true_damage_percent",
                0.0D));
        this.stackDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(duration, "seconds", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        AugmentRuntimeState runtime = context != null ? context.getRuntimeState() : null;
        if (context == null || runtime == null) {
            return context != null ? context.getDamage() : 0f;
        }

        PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (stackDurationMillis > 0L && state.getStacks() > 0 && state.getExpiresAt() > 0L
                && now >= state.getExpiresAt()) {
            AugmentUtils.setStacksWithNotify(runtime, ID, 0, maxStacks, playerRef, getName());
            state.setStoredValue(0.0D);
            state.setExpiresAt(0L);
        }

        int stacks = AugmentUtils.setStacksWithNotify(runtime,
                ID,
                state.getStacks() + 1,
                maxStacks,
                playerRef,
                getName());
        if (stackDurationMillis > 0L) {
            state.setExpiresAt(now + stackDurationMillis);
        }

        float preMitigatedDamage = context.getDamage();
        float updatedDamage = AugmentUtils.applyMultiplier(preMitigatedDamage, stacks * bonusDamagePerStack);
        if (stacks >= maxStacks && (maxStackFlatTrueDamage > 0.0D || maxStackTrueDamagePercent > 0.0D)) {
            boolean cooldownReady = state.getLastProc() <= 0L || now - state.getLastProc() >= INTERNAL_COOLDOWN_MILLIS;
            if (cooldownReady) {
                double bonusTrueDamage = maxStackFlatTrueDamage
                        + (Math.max(0.0D, updatedDamage) * maxStackTrueDamagePercent);
                context.addTrueDamageBonus(bonusTrueDamage);
                state.setLastProc(now);
            }
        }
        return updatedDamage;
    }

    private static double normalizePercent(double raw) {
        if (!Double.isFinite(raw) || raw <= 0.0D) {
            return 0.0D;
        }
        return raw > 1.0D ? raw / 100.0D : raw;
    }
}
