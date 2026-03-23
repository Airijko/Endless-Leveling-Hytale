package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class OverhealAugment extends Augment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "overheal";
    private static final double SHIELD_EPSILON = 0.0001D;
    private static volatile double OVERFLOW_BUFFER_CAP = 0.0D;
    private static final Map<EntityStatMap, Double> PENDING_OVERHEAL = Collections
            .synchronizedMap(new WeakHashMap<>());
    private static final Map<AugmentState, Boolean> FULL_SHIELD_NOTIFIED = Collections
            .synchronizedMap(new WeakHashMap<>());

        private final double maxShieldPercentFallback;
        private final double precisionShieldScaling;
        private final double ferocityShieldScaling;
    private final long durationMillis;
    private final double lifeStealPercent;

    public OverhealAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> shield = AugmentValueReader.getMap(passives, "overheal_shield");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.maxShieldPercentFallback = Math.max(0.0D,
                AugmentValueReader.getDouble(
                        shield,
                        "max_shield_percent",
                        AugmentValueReader.getDouble(shield, "max_bonus_health_percent", 0.0D)));
        this.precisionShieldScaling = Math.max(0.0D,
            AugmentValueReader.getDouble(shield, "precision_shield_scaling", 2.0D));
        this.ferocityShieldScaling = Math.max(0.0D,
            AugmentValueReader.getDouble(shield, "ferocity_shield_scaling", 1.0D));
        this.durationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(
                shield,
                "decay_duration",
                AugmentValueReader.getDouble(shield, "duration", 0.0D)));
        OVERFLOW_BUFFER_CAP = Math.max(0.0D, AugmentValueReader.getDouble(shield, "overflow_buffer_cap", 0.0D));
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
            if (OVERFLOW_BUFFER_CAP > 0.0D) {
                merged = Math.min(OVERFLOW_BUFFER_CAP, merged);
            }
            PENDING_OVERHEAL.put(statMap, merged);
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
        if (state.getStoredValue() <= SHIELD_EPSILON) {
            clearShieldState(state, context.getCommandBuffer(), context.getDefenderRef());
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
        if (state.getStoredValue() <= SHIELD_EPSILON) {
            clearShieldState(state, context.getCommandBuffer(), context.getPlayerRef());
        }
    }

    private void absorbPendingOverflowIntoShield(AugmentRuntimeState runtimeState,
            EntityStatMap statMap,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        if (runtimeState == null || statMap == null || durationMillis <= 0L) {
            return;
        }

        double overflow = consumeOverhealOverflow(statMap);
        if (overflow <= 0.0D) {
            return;
        }

        double maxShield = resolveMaxShieldValue(statMap, commandBuffer, ownerRef);
        if (maxShield <= 0.0D) {
            return;
        }

        AugmentState state = runtimeState.getState(ID);
        double previousShield = Math.max(0.0D, state.getStoredValue());
        double nextShield = Math.min(maxShield, previousShield + overflow);
        state.setStoredValue(nextShield);
        state.setExpiresAt(System.currentTimeMillis() + durationMillis);

        boolean becameFull = previousShield + SHIELD_EPSILON < maxShield
                && nextShield + SHIELD_EPSILON >= maxShield;
        if (becameFull && shouldNotifyFull(state)) {
            notifyShieldFull(commandBuffer, ownerRef, nextShield, maxShield);
        }
    }

    private double resolveMaxShieldValue(EntityStatMap statMap,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        double dynamicShieldCap = 0.0D;

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin != null && commandBuffer != null && ownerRef != null) {
            PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ownerRef);
            PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
            SkillManager skillManager = plugin.getSkillManager();

            if (playerRef != null && playerRef.isValid() && playerDataManager != null && skillManager != null) {
                PlayerData playerData = playerDataManager.get(playerRef.getUuid());
                if (playerData != null) {
                    double precisionPercent = Math.max(0.0D,
                        AugmentUtils.resolvePrecision(statMap, skillManager, playerData, commandBuffer, ownerRef))
                        * 100.0D;
                    double ferocityValue = Math.max(0.0D,
                        AugmentUtils.resolveFerocity(statMap, skillManager, playerData, commandBuffer, ownerRef));
                    dynamicShieldCap = (precisionPercent * precisionShieldScaling)
                            + (ferocityValue * ferocityShieldScaling);
                }
            }
        }

        if (dynamicShieldCap > 0.0D) {
            return dynamicShieldCap;
        }

        float maxHealth = AugmentUtils.getMaxHealth(statMap);
        if (maxHealth <= 0f || maxShieldPercentFallback <= 0.0D) {
            return 0.0D;
        }
        return maxHealth * maxShieldPercentFallback;
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
        boolean hadShield = state.getStoredValue() > SHIELD_EPSILON;
        state.setStoredValue(0.0D);
        state.setExpiresAt(0L);
        clearFullNotifyFlag(state);
        if (hadShield) {
            notifyShieldRemoved(commandBuffer, ownerRef);
        }
    }

    private boolean shouldNotifyFull(AugmentState state) {
        if (state == null) {
            return false;
        }
        synchronized (FULL_SHIELD_NOTIFIED) {
            if (Boolean.TRUE.equals(FULL_SHIELD_NOTIFIED.get(state))) {
                return false;
            }
            FULL_SHIELD_NOTIFIED.put(state, true);
            return true;
        }
    }

    private void clearFullNotifyFlag(AugmentState state) {
        if (state == null) {
            return;
        }
        synchronized (FULL_SHIELD_NOTIFIED) {
            FULL_SHIELD_NOTIFIED.remove(state);
        }
    }

    private void notifyShieldFull(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef,
            double currentShield,
            double maxShield) {
        if (commandBuffer == null || ownerRef == null) {
            return;
        }
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ownerRef);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        AugmentUtils.sendAugmentMessage(playerRef,
                String.format("%s shield is full: %.1f/%.1f.", getName(), currentShield, maxShield));
    }

    private void notifyShieldRemoved(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ownerRef) {
        if (commandBuffer == null || ownerRef == null) {
            return;
        }
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ownerRef);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        AugmentUtils.sendAugmentMessage(playerRef,
                String.format("%s shield broken", getName()));
    }

    private double normalizePercentPoints(double configuredValue) {
        double abs = Math.abs(configuredValue);
        if (abs > 0.0D && abs <= 5.0D) {
            return configuredValue * 100.0D;
        }
        return configuredValue;
    }
}
