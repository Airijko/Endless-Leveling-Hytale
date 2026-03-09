package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class OverhealAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "overheal";
    private static final Map<EntityStatMap, Double> PENDING_OVERHEAL = Collections
            .synchronizedMap(new WeakHashMap<>());

    private final double maxShieldPercent;
    private final long durationMillis;
    private final double lifeStealPercent;

    public OverhealAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> shield = AugmentValueReader.getMap(passives, "overheal_shield");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.maxShieldPercent = Math.max(0.0D,
                AugmentValueReader.getDouble(
                        shield,
                        "max_shield_percent",
                        AugmentValueReader.getDouble(shield, "max_bonus_health_percent", 0.0D)));
        this.durationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(
                shield,
                "decay_duration",
                AugmentValueReader.getDouble(shield, "duration", 0.0D)));
        this.lifeStealPercent = normalizePercentPoints(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "lifesteal", "value"));
    }

    public static void recordOverhealOverflow(EntityStatMap statMap, double overflowAmount) {
        if (statMap == null || overflowAmount <= 0.0D || !Double.isFinite(overflowAmount)) {
            return;
        }
        synchronized (PENDING_OVERHEAL) {
            double existing = PENDING_OVERHEAL.getOrDefault(statMap, 0.0D);
            double merged = existing + overflowAmount;
            // Soft safety cap for malformed heal loops.
            PENDING_OVERHEAL.put(statMap, Math.min(1_000_000.0D, merged));
        }
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getAttackerStats() == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        double healAttempt = context.getDamage() * (lifeStealPercent / 100.0D);
        if (healAttempt <= 0.0D) {
            return context.getDamage();
        }

        AugmentUtils.heal(context.getAttackerStats(), healAttempt);
        absorbPendingOverflowIntoShield(
                context.getRuntimeState(),
                context.getAttackerStats(),
                context.getCommandBuffer(),
                context.getAttackerRef());
        return context.getDamage();
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getIncomingDamage() : 0f;
        }
        AugmentState state = context.getRuntimeState().getState(ID);
        if (state.getStoredValue() <= 0.0D) {
            return context.getIncomingDamage();
        }

        float incoming = Math.max(0f, context.getIncomingDamage());
        double absorbed = Math.min(incoming, state.getStoredValue());
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - absorbed));
        if (state.getStoredValue() <= 0.0001D) {
            clearShieldState(state, context.getCommandBuffer(), context.getDefenderRef());
        } else {
            notifyShieldChanged(state, context.getCommandBuffer(), context.getDefenderRef());
        }
        return (float) Math.max(0.0D, incoming - absorbed);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        absorbPendingOverflowIntoShield(
                context.getRuntimeState(),
                context.getStatMap(),
                context.getCommandBuffer(),
                context.getPlayerRef());

        AugmentState state = context.getRuntimeState().getState(ID);
        if (state.getStoredValue() <= 0.0D) {
            return;
        }

        long now = System.currentTimeMillis();
        if (state.getExpiresAt() <= 0L || now >= state.getExpiresAt()) {
            clearShieldState(state, context.getCommandBuffer(), context.getPlayerRef());
            return;
        }

        double deltaSeconds = Math.max(0.0D, context.getDeltaSeconds());
        if (deltaSeconds <= 0.0D || durationMillis <= 0L) {
            return;
        }

        double decay = state.getStoredValue() * Math.min(1.0D, (deltaSeconds * 1000.0D) / durationMillis);
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - decay));
        if (state.getStoredValue() <= 0.0001D) {
            clearShieldState(state, context.getCommandBuffer(), context.getPlayerRef());
        } else {
            notifyShieldChanged(state, context.getCommandBuffer(), context.getPlayerRef());
        }
    }

    private void absorbPendingOverflowIntoShield(AugmentRuntimeState runtimeState,
            EntityStatMap statMap,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        if (runtimeState == null || statMap == null || maxShieldPercent <= 0.0D || durationMillis <= 0L) {
            return;
        }

        double overflow = consumeOverhealOverflow(statMap);
        if (overflow <= 0.0D) {
            return;
        }

        float maxHealth = AugmentUtils.getMaxHealth(statMap);
        if (maxHealth <= 0f) {
            return;
        }

        AugmentState state = runtimeState.getState(ID);
        double maxShield = maxHealth * maxShieldPercent;
        state.setStoredValue(Math.min(maxShield, state.getStoredValue() + overflow));
        state.setExpiresAt(System.currentTimeMillis() + durationMillis);
        notifyShieldChanged(state, commandBuffer, ownerRef);
    }

    private double consumeOverhealOverflow(EntityStatMap statMap) {
        if (statMap == null) {
            return 0.0D;
        }
        synchronized (PENDING_OVERHEAL) {
            Double pending = PENDING_OVERHEAL.remove(statMap);
            if (pending == null || pending <= 0.0D || !Double.isFinite(pending)) {
                return 0.0D;
            }
            return pending;
        }
    }

    private void clearShieldState(AugmentState state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        if (state == null) {
            return;
        }
        state.setStoredValue(0.0D);
        state.setExpiresAt(0L);
        notifyShieldChanged(state, commandBuffer, ownerRef);
    }

    private void notifyShieldChanged(AugmentState state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        if (state == null) {
            return;
        }
        int displayedValue = (int) Math.round(Math.max(0.0D, state.getStoredValue()));
        if (displayedValue == state.getStacks()) {
            return;
        }
        state.setStacks(displayedValue);

        if (commandBuffer == null || ownerRef == null) {
            return;
        }
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ownerRef);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        AugmentUtils.sendAugmentMessage(playerRef,
                String.format("%s shield: %d", getName(), displayedValue));
    }

    private double normalizePercentPoints(double configuredValue) {
        double abs = Math.abs(configuredValue);
        if (abs > 0.0D && abs <= 5.0D) {
            return configuredValue * 100.0D;
        }
        return configuredValue;
    }
}
