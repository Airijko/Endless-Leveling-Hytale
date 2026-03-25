package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentDamageSafety;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WitherAugment extends Augment implements AugmentHooks.OnHitAugment {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String ID = "wither";
    private static final long TICK_INTERVAL_MILLIS = 1000L;
    private static final float MIN_MOVEMENT_MULTIPLIER = 0.0001F;
    private static final String[] SLOW_EFFECT_IDS = new String[] { "slowness", "slow" };
    private static final Map<String, ActiveWither> ACTIVE_WITHER = new ConcurrentHashMap<>();

    private final double percentPerSecond;
    private final double durationSeconds;
    private final double movementSpeedSlowPercent;
    private final double movementSpeedSlowCap;
    private final double maxDamagePerTick;

    private static final class MovementSnapshot {
        final float forwardWalk;
        final float backwardWalk;
        final float strafeWalk;
        final float forwardRun;
        final float backwardRun;
        final float strafeRun;
        final float forwardCrouch;
        final float backwardCrouch;
        final float strafeCrouch;
        final float forwardSprint;

        MovementSnapshot(MovementSettings source) {
            this.forwardWalk = source.forwardWalkSpeedMultiplier;
            this.backwardWalk = source.backwardWalkSpeedMultiplier;
            this.strafeWalk = source.strafeWalkSpeedMultiplier;
            this.forwardRun = source.forwardRunSpeedMultiplier;
            this.backwardRun = source.backwardRunSpeedMultiplier;
            this.strafeRun = source.strafeRunSpeedMultiplier;
            this.forwardCrouch = source.forwardCrouchSpeedMultiplier;
            this.backwardCrouch = source.backwardCrouchSpeedMultiplier;
            this.strafeCrouch = source.strafeCrouchSpeedMultiplier;
            this.forwardSprint = source.forwardSprintSpeedMultiplier;
        }

        void apply(MovementSettings target, float multiplier) {
            target.forwardWalkSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, forwardWalk * multiplier);
            target.backwardWalkSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, backwardWalk * multiplier);
            target.strafeWalkSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, strafeWalk * multiplier);
            target.forwardRunSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, forwardRun * multiplier);
            target.backwardRunSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, backwardRun * multiplier);
            target.strafeRunSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, strafeRun * multiplier);
            target.forwardCrouchSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, forwardCrouch * multiplier);
            target.backwardCrouchSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, backwardCrouch * multiplier);
            target.strafeCrouchSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, strafeCrouch * multiplier);
            target.forwardSprintSpeedMultiplier = Math.max(MIN_MOVEMENT_MULTIPLIER, forwardSprint * multiplier);
        }

        void restore(MovementSettings target) {
            target.forwardWalkSpeedMultiplier = forwardWalk;
            target.backwardWalkSpeedMultiplier = backwardWalk;
            target.strafeWalkSpeedMultiplier = strafeWalk;
            target.forwardRunSpeedMultiplier = forwardRun;
            target.backwardRunSpeedMultiplier = backwardRun;
            target.strafeRunSpeedMultiplier = strafeRun;
            target.forwardCrouchSpeedMultiplier = forwardCrouch;
            target.backwardCrouchSpeedMultiplier = backwardCrouch;
            target.strafeCrouchSpeedMultiplier = strafeCrouch;
            target.forwardSprintSpeedMultiplier = forwardSprint;
        }
    }

    private static final class ActiveWither {
        long expiresAt;
        long nextTickAt;
        int targetStoreIdentity;
        Ref<EntityStore> targetRef;
        UUID targetUuid;
        double percentPerSecond;
        double movementSpeedSlowPercent;
        double durationSeconds;
        double maxDamagePerTick;
        Ref<EntityStore> sourceRef;
        UUID sourceUuid;
        MovementSnapshot movementSnapshot;
        MovementSnapshot defaultMovementSnapshot;
        boolean fallbackSlowEffectApplied;
        String fallbackSlowEffectId;
        boolean loggedMissingMovementManager;
        boolean loggedMissingMovementSettings;
        boolean loggedMissingEffectController;
        boolean loggedMissingSlowEffectAsset;
        boolean loggedEffectApplyFailure;
        boolean loggedSourceStoreMismatch;
    }

    public WitherAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> wither = AugmentValueReader.getMap(passives, "wither");
        this.percentPerSecond = AugmentValueReader.getDouble(wither, "value", 0.0D);
        this.durationSeconds = AugmentValueReader.getDouble(wither, "duration", 0.0D);
        this.maxDamagePerTick = Math.max(0.0D, AugmentValueReader.getDouble(wither, "max_damage_per_tick", 0.0D));
        Map<String, Object> targetDebuff = AugmentValueReader.getMap(wither, "target_debuff");
        Map<String, Object> movementSpeed = AugmentValueReader.getMap(targetDebuff, "movement_speed");
        this.movementSpeedSlowCap = Math.max(0.0D, AugmentValueReader.getDouble(movementSpeed, "max_slow_cap", 0.0D));
        double rawMovementSpeedValue = AugmentValueReader.getNestedDouble(targetDebuff,
                0.0D,
                "movement_speed",
                "value");
        this.movementSpeedSlowPercent = normalizeSlowPercent(rawMovementSpeedValue, movementSpeedSlowCap);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getTargetRef() == null || context.getCommandBuffer() == null) {
            return context.getDamage();
        }

        Ref<EntityStore> targetRef = context.getTargetRef();
        EntityStatMap targetStats = context.getTargetStats();
        if (targetStats == null) {
            targetStats = context.getCommandBuffer().getComponent(targetRef, EntityStatMap.getComponentType());
        }
        EntityStatValue hp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || durationSeconds <= 0.0D || percentPerSecond <= 0.0D) {
            LOGGER.atFine().log(
                    "Wither skipped: hp/stat gate failed target=%s hp=%s max=%.2f duration=%.2f dpsPct=%.4f",
                    context.getTargetRef(),
                    hp,
                    hp == null ? -1.0 : hp.getMax(),
                    durationSeconds,
                    percentPerSecond);
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        long durationMillis = AugmentUtils.secondsToMillis(durationSeconds);
        String key = keyFor(targetRef, context.getCommandBuffer());
        ActiveWither state = ACTIVE_WITHER.computeIfAbsent(key, unused -> new ActiveWither());
        boolean isNewOrExpired = state.nextTickAt <= 0L || now >= state.expiresAt;
        state.expiresAt = now + durationMillis;
        if (isNewOrExpired) {
            state.nextTickAt = now + TICK_INTERVAL_MILLIS;
        }
        state.percentPerSecond = percentPerSecond;
        state.movementSpeedSlowPercent = movementSpeedSlowPercent;
        state.durationSeconds = durationSeconds;
        state.maxDamagePerTick = maxDamagePerTick;
        state.targetStoreIdentity = storeIdentityFor(targetRef);
        state.targetRef = targetRef;
        state.sourceRef = context.getAttackerRef();
        state.targetUuid = resolveEntityUuid(targetRef, context.getCommandBuffer());
        state.sourceUuid = resolveEntityUuid(context.getAttackerRef(), context.getCommandBuffer());
        state.loggedSourceStoreMismatch = false;

        LOGGER.atFine().log("Wither applied: key=%s target=%s durationMs=%d slowPct=%.4f dpsPct=%.4f",
                key,
                targetRef,
                durationMillis,
                movementSpeedSlowPercent,
                percentPerSecond);

        applySlowIfPossible(state, context.getCommandBuffer(), targetRef);
        return context.getDamage();
    }

    public static boolean hasActiveWithers() {
        return !ACTIVE_WITHER.isEmpty();
    }

    public static List<Ref<EntityStore>> snapshotActiveTargetsForStore(Store<EntityStore> store, long now) {
        if (store == null || ACTIVE_WITHER.isEmpty()) {
            return List.of();
        }

        int storeIdentity = System.identityHashCode(store);
        List<Ref<EntityStore>> targets = new ArrayList<>();

        ACTIVE_WITHER.forEach((key, state) -> {
            if (state == null || now >= state.expiresAt) {
                return;
            }

            Ref<EntityStore> targetRef = state.targetRef;
            if (targetRef == null || !EntityRefUtil.isUsable(targetRef)) {
                return;
            }

            UUID currentTargetUuid = resolveEntityUuid(targetRef, store);
            if (!matchesExpectedUuid(state.targetUuid, currentTargetUuid)) {
                return;
            }

            int targetStoreIdentity = state.targetStoreIdentity;
            if (targetStoreIdentity == 0) {
                targetStoreIdentity = storeIdentityFor(targetRef);
                state.targetStoreIdentity = targetStoreIdentity;
            }

            if (targetStoreIdentity != storeIdentity) {
                return;
            }

            targets.add(targetRef);
        });

        if (targets.isEmpty()) {
            return List.of();
        }
        return List.copyOf(targets);
    }

    public static void tickTarget(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer, long now) {
        if (ref == null || commandBuffer == null || !EntityRefUtil.isUsable(ref)) {
            return;
        }
        String key = keyFor(ref, commandBuffer);
        ActiveWither state = ACTIVE_WITHER.get(key);
        if (state == null) {
            return;
        }

        UUID currentTargetUuid = resolveEntityUuid(ref, commandBuffer);
        if (!matchesExpectedUuid(state.targetUuid, currentTargetUuid)) {
            ACTIVE_WITHER.remove(key);
            LOGGER.atFine().log("Wither removed due to UUID mismatch: key=%s target=%s expected=%s actual=%s",
                    key,
                    ref,
                    state.targetUuid,
                    currentTargetUuid);
            return;
        }

        int targetStoreIdentity = storeIdentityFor(ref);
        if (state.targetStoreIdentity != 0 && targetStoreIdentity != 0
                && state.targetStoreIdentity != targetStoreIdentity) {
            ACTIVE_WITHER.remove(key);
            LOGGER.atFine().log(
                    "Wither removed due to store change: key=%s target=%s oldStore=%d newStore=%d",
                    key,
                    ref,
                    state.targetStoreIdentity,
                    targetStoreIdentity);
            return;
        }
        if (state.targetStoreIdentity == 0) {
            state.targetStoreIdentity = targetStoreIdentity;
        }

        if (now >= state.expiresAt) {
            clearSlowIfPossible(state, commandBuffer, ref);
            ACTIVE_WITHER.remove(key);
            LOGGER.atFine().log("Wither expired: key=%s target=%s", key, ref);
            return;
        }

        applySlowIfPossible(state, commandBuffer, ref);

        if (now < state.nextTickAt) {
            return;
        }

        EntityStatMap statMap = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                EntityStatMap.getComponentType());
        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
            return;
        }

        long elapsedMillis = Math.max(TICK_INTERVAL_MILLIS, now - (state.nextTickAt - TICK_INTERVAL_MILLIS));
        double elapsedSeconds = elapsedMillis / 1000.0D;
        double damage = hp.getMax() * state.percentPerSecond * elapsedSeconds;
        PlayerRef targetPlayer = AugmentUtils.getPlayerRef(commandBuffer, ref);
        boolean targetIsMonster = targetPlayer == null || !targetPlayer.isValid();
        if (targetIsMonster && state.maxDamagePerTick > 0.0D && damage > state.maxDamagePerTick) {
            damage = state.maxDamagePerTick;
        }
        if (damage <= 0.0D) {
            state.nextTickAt = now + TICK_INTERVAL_MILLIS;
            return;
        }

        Ref<EntityStore> sourceRef = sanitizeSourceRefForTarget(state, ref);
        Damage witherTickDamage = PlayerCombatSystem.createAugmentDotDamage(sourceRef, (float) damage);

        if (!EntityRefUtil.isUsable(ref)) {
            clearSlowIfPossible(state, commandBuffer, ref);
            ACTIVE_WITHER.remove(key);
            LOGGER.atFine().log("Wither removed: target became unusable key=%s target=%s", key, ref);
            return;
        }

        if (!AugmentDamageSafety.tryExecuteDamage(ref, commandBuffer, witherTickDamage, ID)) {
            state.nextTickAt = now + TICK_INTERVAL_MILLIS;
            if (!EntityRefUtil.isUsable(ref)) {
                clearSlowIfPossible(state, commandBuffer, ref);
                ACTIVE_WITHER.remove(key);
                LOGGER.atFine().log("Wither removed after failed damage: target unusable key=%s target=%s", key, ref);
            }
            return;
        }

        state.nextTickAt = now + TICK_INTERVAL_MILLIS;
    }

    public static void purgeExpiredStates(long now) {
        ACTIVE_WITHER.entrySet().removeIf(entry -> {
            ActiveWither state = entry.getValue();
            return state == null || now >= state.expiresAt;
        });
    }

    public static int clearAllRuntimeState() {
        int cleared = ACTIVE_WITHER.size();
        ACTIVE_WITHER.clear();
        return cleared;
    }

    private static String keyFor(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer != null) {
            PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
            if (playerRef != null && playerRef.isValid() && playerRef.getUuid() != null) {
                return playerRef.getUuid().toString();
            }

            UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer,
                    ref,
                    UUIDComponent.getComponentType());
            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                return uuidComponent.getUuid().toString();
            }
        }

        var store = EntityRefUtil.getStore(ref);
        if (store != null) {
            return System.identityHashCode(store) + ":" + ref.getIndex();
        }
        return String.valueOf(ref.getIndex());
    }

    private static int storeIdentityFor(Ref<EntityStore> ref) {
        var store = EntityRefUtil.getStore(ref);
        if (store == null) {
            return 0;
        }
        return System.identityHashCode(store);
    }

    private static Ref<EntityStore> sanitizeSourceRefForTarget(ActiveWither state, Ref<EntityStore> targetRef) {
        Ref<EntityStore> sourceRef = state == null ? null : state.sourceRef;
        if (sourceRef == null || targetRef == null) {
            return null;
        }
        if (!EntityRefUtil.isUsable(sourceRef) || !EntityRefUtil.isUsable(targetRef)) {
            return null;
        }

        UUID currentSourceUuid = resolveEntityUuid(sourceRef, EntityRefUtil.getStore(sourceRef));
        if (!matchesExpectedUuid(state == null ? null : state.sourceUuid, currentSourceUuid)) {
            return null;
        }

        if (EntityRefUtil.getStore(sourceRef) != EntityRefUtil.getStore(targetRef)) {
            if (state != null && !state.loggedSourceStoreMismatch) {
                LOGGER.atWarning().log(
                        "Wither source store mismatch; falling back to NULL_SOURCE target=%s source=%s",
                        targetRef,
                        sourceRef);
                state.loggedSourceStoreMismatch = true;
            }
            return null;
        }
        return sourceRef;
    }

    private static UUID resolveEntityUuid(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref) || commandBuffer == null) {
            return null;
        }
        UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                UUIDComponent.getComponentType());
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    private static UUID resolveEntityUuid(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!EntityRefUtil.isUsable(ref) || store == null) {
            return null;
        }
        UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(store,
                ref,
                UUIDComponent.getComponentType());
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    private static boolean matchesExpectedUuid(UUID expected, UUID actual) {
        if (expected == null || actual == null) {
            return true;
        }
        return expected.equals(actual);
    }

    private static void applySlowIfPossible(ActiveWither state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null || !EntityRefUtil.isUsable(ref)
                || state.movementSpeedSlowPercent <= 0.0D) {
            return;
        }
        String key = keyFor(ref, commandBuffer);
        MovementManager movementManager = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                MovementManager.getComponentType());
        if (movementManager == null) {
            if (!state.loggedMissingMovementManager) {
                LOGGER.atWarning().log("Wither slow MovementManager missing; trying effect fallback key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingMovementManager = true;
            }
            applySlowEffectFallback(state, commandBuffer, ref, key);
            return;
        }
        MovementSettings settings = movementManager.getSettings();
        MovementSettings defaultSettings = movementManager.getDefaultSettings();
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
        if (settings == null && defaultSettings == null) {
            if (!state.loggedMissingMovementSettings) {
                LOGGER.atWarning().log("Wither slow movement settings missing; trying effect fallback key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingMovementSettings = true;
            }
            applySlowEffectFallback(state, commandBuffer, ref, key);
            return;
        }
        if (state.movementSnapshot == null && settings != null) {
            state.movementSnapshot = new MovementSnapshot(settings);
            LOGGER.atFine().log("Wither slow snapshot captured (active settings) key=%s target=%s", key, ref);
        }
        if (state.defaultMovementSnapshot == null && defaultSettings != null) {
            state.defaultMovementSnapshot = new MovementSnapshot(defaultSettings);
            LOGGER.atFine().log("Wither slow snapshot captured (default settings) key=%s target=%s", key, ref);
        }
        float multiplier = (float) Math.max(MIN_MOVEMENT_MULTIPLIER, 1.0D - state.movementSpeedSlowPercent);
        if (settings != null && state.movementSnapshot != null) {
            state.movementSnapshot.apply(settings, multiplier);
        }
        if (defaultSettings != null && state.defaultMovementSnapshot != null) {
            state.defaultMovementSnapshot.apply(defaultSettings, multiplier);
        }
        if (playerRef != null && playerRef.isValid()) {
            movementManager.update(playerRef.getPacketHandler());
            LOGGER.atFine().log("Wither slow applied key=%s target=%s multiplier=%.4f packetUpdate=true",
                    key,
                    ref,
                    multiplier);
        } else {
            LOGGER.atFine().log("Wither slow applied key=%s target=%s multiplier=%.4f packetUpdate=false",
                    key,
                    ref,
                    multiplier);
        }
    }

    private static void clearSlowIfPossible(ActiveWither state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null || !EntityRefUtil.isUsable(ref)) {
            return;
        }
        String key = keyFor(ref, commandBuffer);

        clearSlowEffectFallback(state, commandBuffer, ref, key);

        MovementManager movementManager = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }
        MovementSettings settings = movementManager.getSettings();
        MovementSettings defaultSettings = movementManager.getDefaultSettings();
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
        if (settings == null && defaultSettings == null) {
            return;
        }
        if (settings != null && state.movementSnapshot != null) {
            state.movementSnapshot.restore(settings);
        }
        if (defaultSettings != null && state.defaultMovementSnapshot != null) {
            state.defaultMovementSnapshot.restore(defaultSettings);
        }
        if (playerRef != null && playerRef.isValid()) {
            movementManager.update(playerRef.getPacketHandler());
            LOGGER.atFine().log("Wither slow restored key=%s target=%s packetUpdate=true", key, ref);
        } else {
            LOGGER.atFine().log("Wither slow restored key=%s target=%s packetUpdate=false", key, ref);
        }
    }

    private static void applySlowEffectFallback(ActiveWither state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            String key) {
        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            if (!state.loggedMissingEffectController) {
                LOGGER.atWarning().log("Wither slow fallback unavailable: EffectController missing key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingEffectController = true;
            }
            return;
        }

        EntityEffect slowEffect = resolveSlowEffect();
        if (slowEffect == null) {
            if (!state.loggedMissingSlowEffectAsset) {
                LOGGER.atWarning().log("Wither slow fallback unavailable: no slow effect asset found key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingSlowEffectAsset = true;
            }
            return;
        }

        float remainingSeconds = Math.max(0.1F, (float) ((state.expiresAt - System.currentTimeMillis()) / 1000.0D));
        boolean applied = controller.addEffect(ref, slowEffect, remainingSeconds, OverlapBehavior.OVERWRITE,
                commandBuffer);
        if (applied) {
            state.fallbackSlowEffectApplied = true;
            state.fallbackSlowEffectId = slowEffect.getId();
            LOGGER.atFine().log("Wither fallback slow applied key=%s target=%s effect=%s duration=%.2fs",
                    key,
                    ref,
                    slowEffect.getId(),
                    remainingSeconds);
        } else if (!state.loggedEffectApplyFailure) {
            LOGGER.atWarning().log("Wither fallback slow failed to apply key=%s target=%s effect=%s",
                    key,
                    ref,
                    slowEffect.getId());
            state.loggedEffectApplyFailure = true;
        }
    }

    private static void clearSlowEffectFallback(ActiveWither state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            String key) {
        if (!state.fallbackSlowEffectApplied || state.fallbackSlowEffectId == null) {
            return;
        }
        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }
        int idx = EntityEffect.getAssetMap().getIndex(state.fallbackSlowEffectId);
        if (idx != Integer.MIN_VALUE) {
            controller.removeEffect(ref, idx, commandBuffer);
            LOGGER.atFine().log("Wither fallback slow cleared key=%s target=%s effect=%s",
                    key,
                    ref,
                    state.fallbackSlowEffectId);
        }
        state.fallbackSlowEffectApplied = false;
        state.fallbackSlowEffectId = null;
    }

    private static EntityEffect resolveSlowEffect() {
        for (String candidate : SLOW_EFFECT_IDS) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(candidate);
            if (effect != null) {
                return effect;
            }
            effect = EntityEffect.getAssetMap().getAsset(candidate.toLowerCase());
            if (effect != null) {
                return effect;
            }
            effect = EntityEffect.getAssetMap().getAsset(candidate.toUpperCase());
            if (effect != null) {
                return effect;
            }
        }
        return null;
    }

    private static double normalizeSlowPercent(double rawValue, double configuredCap) {
        double magnitude = Math.abs(rawValue);
        if (magnitude > 1.0D) {
            magnitude /= 100.0D;
        }
        magnitude = Math.max(0.0D, magnitude);
        return configuredCap > 0.0D ? Math.min(configuredCap, magnitude) : magnitude;
    }
}
