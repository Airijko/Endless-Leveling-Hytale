package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FrozenDomainAugment extends YamlAugment
        implements AugmentHooks.OnDamageTakenAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "frozen_domain";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long SLOW_DURATION_MILLIS = 2500L;
    private static final float MIN_MOVEMENT_MULTIPLIER = 0.0001F;
    private static final String[] SLOW_EFFECT_IDS = new String[] { "slowness", "slow" };
    private static final String[] TRIGGER_PULSE_VFX_IDS = new String[] { "Impact_Blade_01" };
        private static final String[] TRIGGER_PULSE_SFX_IDS = new String[] {
            "SFX_Arrow_Frost_Miss",
            "SFX_Arrow_Frost_Hit",
            "SFX_Ice_Ball_Death"
        };
    private static final long TRIGGER_PULSE_DURATION_MILLIS = 250L;
    private static final long TRIGGER_PULSE_STEP_MILLIS = 50L;
    private static final int TRIGGER_PULSE_MIN_POINT_COUNT = 8;
    private static final int TRIGGER_PULSE_MAX_POINT_COUNT = 18;
    private static final int TRIGGER_PULSE_MIN_LAYER_COUNT = 1;
    private static final double TRIGGER_PULSE_START_RADIUS = 0.1D;
    private static final double TRIGGER_PULSE_Y_OFFSET = 0.3D;
    private static final Map<String, ActiveFrozen> ACTIVE_FROST = new ConcurrentHashMap<>();
    private static final Map<String, ActivePulse> ACTIVE_PULSES = new ConcurrentHashMap<>();

    private final double slowPercent;
    private final double stolenSlowRatio;
    private final double baseRadius;
    private final double healthPerRadiusBlock;
    private final long activeDurationMillis;
    private final long cooldownMillis;
    private final long slowTickIntervalMillis;
    private final double lifeForceFlatBonus;

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

    private static final class ActiveFrozen {
        Ref<EntityStore> targetRef;
        long expiresAt;
        double slowPercent;
        MovementSnapshot movementSnapshot;
        MovementSnapshot defaultMovementSnapshot;
        boolean fallbackSlowEffectApplied;
        String fallbackSlowEffectId;
        boolean loggedMissingMovementManager;
        boolean loggedMissingMovementSettings;
        boolean loggedMissingEffectController;
        boolean loggedMissingSlowEffectAsset;
        boolean loggedEffectApplyFailure;
    }

    private static final class ActivePulse {
        Ref<EntityStore> sourceRef;
        long startedAt;
        long expiresAt;
        long lastVisualAt;
        double endRadius;
        boolean soundPlayed;
    }

    public FrozenDomainAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> aura = AugmentValueReader.getMap(passives, "aura_frozen_domain");
        Map<String, Object> radiusScaling = AugmentValueReader.getMap(aura, "radius_health_scaling");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> lifeForce = AugmentValueReader.getMap(buffs, "life_force");

        this.slowPercent = normalizeSlowPercent(AugmentValueReader.getDouble(aura, "slow_percent", 0.0D));
        this.stolenSlowRatio = clampRatio(AugmentValueReader.getDouble(aura, "stolen_slow_ratio", 0.0D));
        this.baseRadius = Math.max(0.0D, AugmentValueReader.getDouble(aura, "radius", 0.0D));
        this.healthPerRadiusBlock = Math.max(0.0D,
                AugmentValueReader.getDouble(radiusScaling, "health_per_block", 0.0D));
        this.activeDurationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(aura, "duration", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(aura, "cooldown", 0.0D));
        this.slowTickIntervalMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(aura, "slow_interval", 2.5D));
        this.lifeForceFlatBonus = Math.max(0.0D, AugmentValueReader.getDouble(lifeForce, "value", 0.0D));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null || activeDurationMillis <= 0L) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        AugmentRuntimeState runtimeState = context.getRuntimeState();
        var state = runtimeState.getState(ID);
        long now = System.currentTimeMillis();
        if (state.getExpiresAt() > now) {
            return context.getIncomingDamage();
        }

        long cooldownEndsAt = (long) state.getStoredValue();
        if (cooldownEndsAt > now) {
            return context.getIncomingDamage();
        }

        long activeUntil = now + activeDurationMillis;
        long combinedCooldownEnd = cooldownMillis > 0L ? activeUntil + cooldownMillis : activeUntil;
        state.setExpiresAt(activeUntil);
        state.setStoredValue(combinedCooldownEnd);
        state.setStacks(1);
        state.setLastProc(0L);
        if (combinedCooldownEnd > now) {
            runtimeState.setCooldown(ID, getName(), combinedCooldownEnd);
        }

        PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    String.format("%s activated for %.1fs.", getName(), activeDurationMillis / 1000.0D));
        }

        return context.getIncomingDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null || context.getStatMap() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        var augmentState = context.getRuntimeState().getState(ID);
        updateTriggerPulse(context.getPlayerRef(), context.getCommandBuffer(), now);
        boolean active = augmentState.getExpiresAt() > now;
        if (!active) {
            if (augmentState.getStacks() > 0) {
                PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
                if (playerRef != null && playerRef.isValid()) {
                    AugmentUtils.sendAugmentMessage(playerRef,
                            String.format("%s expired.", getName()));
                }
                augmentState.setStacks(0);
            }
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_life_force", SkillAttributeType.LIFE_FORCE,
                    0.0D, 0L);
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_stolen_haste", SkillAttributeType.HASTE,
                    0.0D, 0L);
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_life_force", SkillAttributeType.LIFE_FORCE,
                lifeForceFlatBonus, 0L);

        if (augmentState.getLastProc() > 0L && now - augmentState.getLastProc() < slowTickIntervalMillis) {
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        EntityStatMap sourceStats = context.getStatMap();
        EntityStatValue sourceHp = sourceStats.get(DefaultEntityStatTypes.getHealth());
        if (sourceHp == null || sourceHp.getMax() <= 0f || sourceHp.get() <= 0f) {
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        double maxHealth = Math.max(0.0D, sourceHp.getMax());
        double radius = resolveRadius(maxHealth);

        if (radius <= 0.0D || slowPercent <= 0.0D) {
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> sourceRef = context.getPlayerRef();
        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            cleanupExpired(commandBuffer, now);
            return;
        }

        augmentState.setLastProc(now);
        startTriggerPulse(sourceRef, commandBuffer, now);

        UUID sourceUuid = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
        PartyManager partyManager = resolvePartyManager();
        UUID sourcePartyLeader = resolvePartyLeader(partyManager, sourceUuid);

        int affectedTargets = 0;
        HashSet<Integer> visitedEntityIds = new HashSet<>();
        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(
                sourceTransform.getPosition(),
                radius,
                commandBuffer)) {
            if (targetRef == null || !targetRef.isValid()) {
                continue;
            }
            if (!visitedEntityIds.add(targetRef.getIndex())) {
                continue;
            }
            if (targetRef.equals(sourceRef)) {
                continue;
            }
            if (isPetEntity(targetRef, commandBuffer)) {
                continue;
            }
            if (isSamePartyTarget(sourceUuid, sourcePartyLeader, targetRef, commandBuffer, partyManager)) {
                continue;
            }

            EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer,
                    targetRef,
                    EntityStatMap.getComponentType());
            EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
                continue;
            }

            String key = keyFor(targetRef, commandBuffer);
            ActiveFrozen state = ACTIVE_FROST.computeIfAbsent(key, unused -> new ActiveFrozen());
            state.targetRef = targetRef;
            state.expiresAt = now + SLOW_DURATION_MILLIS;
            state.slowPercent = slowPercent;
            applySlowIfPossible(state, commandBuffer, targetRef);
            affectedTargets++;
        }

        cleanupExpired(commandBuffer, now);

        double stolenHastePercent = affectedTargets * slowPercent * stolenSlowRatio * 100.0D;
        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_stolen_haste", SkillAttributeType.HASTE,
                stolenHastePercent, 0L);
    }

    private void cleanupExpired(CommandBuffer<EntityStore> commandBuffer, long now) {
        if (commandBuffer == null) {
            return;
        }
        for (var entry : ACTIVE_FROST.entrySet()) {
            ActiveFrozen state = entry.getValue();
            if (state == null || state.expiresAt <= 0L || now < state.expiresAt) {
                continue;
            }

            Ref<EntityStore> targetRef = state.targetRef;
            if (targetRef != null && targetRef.isValid()) {
                clearSlowIfPossible(state, commandBuffer, targetRef);
            }
            ACTIVE_FROST.remove(entry.getKey());
        }
    }

    private static String keyFor(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer != null) {
            PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
            if (playerRef != null && playerRef.isValid() && playerRef.getUuid() != null) {
                return playerRef.getUuid().toString();
            }
        }
        return ref.toString();
    }

    private static void applySlowIfPossible(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null || state.slowPercent <= 0.0D) {
            return;
        }

        String key = keyFor(ref, commandBuffer);
        MovementManager movementManager = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                MovementManager.getComponentType());
        if (movementManager == null) {
            if (!state.loggedMissingMovementManager) {
                LOGGER.atWarning().log(
                        "Frozen Domain slow MovementManager missing; trying effect fallback key=%s target=%s",
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
                LOGGER.atWarning().log(
                        "Frozen Domain slow movement settings missing; trying effect fallback key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingMovementSettings = true;
            }
            applySlowEffectFallback(state, commandBuffer, ref, key);
            return;
        }

        if (state.movementSnapshot == null && settings != null) {
            state.movementSnapshot = new MovementSnapshot(settings);
        }
        if (state.defaultMovementSnapshot == null && defaultSettings != null) {
            state.defaultMovementSnapshot = new MovementSnapshot(defaultSettings);
        }

        float multiplier = (float) Math.max(MIN_MOVEMENT_MULTIPLIER, 1.0D - state.slowPercent);
        if (settings != null && state.movementSnapshot != null) {
            state.movementSnapshot.apply(settings, multiplier);
        }
        if (defaultSettings != null && state.defaultMovementSnapshot != null) {
            state.defaultMovementSnapshot.apply(defaultSettings, multiplier);
        }

        if (playerRef != null && playerRef.isValid()) {
            movementManager.update(playerRef.getPacketHandler());
        }

        // Always apply the visual slow effect in addition to the movement manager slow.
        applySlowEffectFallback(state, commandBuffer, ref, key);
    }

    private static void clearSlowIfPossible(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null) {
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
        if (settings != null && state.movementSnapshot != null) {
            state.movementSnapshot.restore(settings);
        }
        if (defaultSettings != null && state.defaultMovementSnapshot != null) {
            state.defaultMovementSnapshot.restore(defaultSettings);
        }

        if (playerRef != null && playerRef.isValid()) {
            movementManager.update(playerRef.getPacketHandler());
        }
    }

    private static void applySlowEffectFallback(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            String key) {
        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            if (!state.loggedMissingEffectController) {
                LOGGER.atWarning().log("Frozen Domain fallback unavailable: EffectController missing key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingEffectController = true;
            }
            return;
        }

        EntityEffect slowEffect = resolveSlowEffect();
        if (slowEffect == null) {
            if (!state.loggedMissingSlowEffectAsset) {
                LOGGER.atWarning().log(
                        "Frozen Domain fallback unavailable: no slow effect asset found key=%s target=%s",
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
        } else if (!state.loggedEffectApplyFailure) {
            LOGGER.atWarning().log("Frozen Domain fallback slow failed to apply key=%s target=%s effect=%s",
                    key,
                    ref,
                    slowEffect.getId());
            state.loggedEffectApplyFailure = true;
        }
    }

    private static void clearSlowEffectFallback(ActiveFrozen state,
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

    private void startTriggerPulse(Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            long now) {
        if (sourceRef == null || commandBuffer == null) {
            return;
        }

        ActivePulse pulse = ACTIVE_PULSES.computeIfAbsent(keyFor(sourceRef, commandBuffer), unused -> new ActivePulse());
        pulse.sourceRef = sourceRef;
        pulse.startedAt = now;
        pulse.expiresAt = now + TRIGGER_PULSE_DURATION_MILLIS;
        pulse.lastVisualAt = 0L;
        pulse.endRadius = resolveTriggerPulseRadius(sourceRef, commandBuffer);
        pulse.soundPlayed = false;
        updateTriggerPulse(sourceRef, commandBuffer, now);
    }

    private void updateTriggerPulse(Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            long now) {
        if (sourceRef == null || commandBuffer == null) {
            return;
        }

        String pulseKey = keyFor(sourceRef, commandBuffer);
        ActivePulse pulse = ACTIVE_PULSES.get(pulseKey);
        if (pulse == null) {
            return;
        }
        if (pulse.expiresAt <= now || pulse.sourceRef == null || !pulse.sourceRef.isValid()) {
            ACTIVE_PULSES.remove(pulseKey);
            return;
        }

        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                pulse.sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            return;
        }

        Vector3d centerPosition = sourceTransform.getPosition();
        double centerY = centerPosition.getY() + TRIGGER_PULSE_Y_OFFSET;
        if (!pulse.soundPlayed) {
            playTriggerPulseSound(pulse.sourceRef, new Vector3d(centerPosition.getX(), centerY, centerPosition.getZ()));
            pulse.soundPlayed = true;
        }
        if (pulse.lastVisualAt > 0L && now - pulse.lastVisualAt < TRIGGER_PULSE_STEP_MILLIS) {
            return;
        }

        double progress = Math.max(0.0D,
                Math.min(1.0D, (double) (now - pulse.startedAt) / (double) TRIGGER_PULSE_DURATION_MILLIS));
        double ringRadius = TRIGGER_PULSE_START_RADIUS + ((pulse.endRadius - TRIGGER_PULSE_START_RADIUS) * progress);
        double baseAngleOffset = progress * Math.PI * 2.0D;
        int pointCount = resolveTriggerPulsePointCount(pulse.endRadius);
        int layerCount = resolveTriggerPulseLayerCount(pulse.endRadius);
        double layerSpacing = resolveTriggerPulseLayerSpacing(pulse.endRadius, layerCount);

        for (int layer = 0; layer < layerCount; layer++) {
            double layerRadius = Math.max(TRIGGER_PULSE_START_RADIUS, ringRadius - (layer * layerSpacing));
            double angleOffset = baseAngleOffset + ((Math.PI / pointCount) * layer);
            for (int i = 0; i < pointCount; i++) {
            double angle = angleOffset + ((Math.PI * 2.0D * i) / pointCount);
                Vector3d particlePosition = new Vector3d(
                        centerPosition.getX() + (Math.cos(angle) * layerRadius),
                        centerY,
                        centerPosition.getZ() + (Math.sin(angle) * layerRadius));
                spawnTriggerPulseParticle(pulse.sourceRef, particlePosition);
            }
        }

        pulse.lastVisualAt = now;
        if (progress >= 1.0D) {
            ACTIVE_PULSES.remove(pulseKey);
        }
    }

    private double resolveTriggerPulseRadius(Ref<EntityStore> sourceRef, CommandBuffer<EntityStore> commandBuffer) {
        EntityStatMap sourceStats = EntityRefUtil.tryGetComponent(commandBuffer,
                sourceRef,
                EntityStatMap.getComponentType());
        EntityStatValue sourceHp = sourceStats == null ? null : sourceStats.get(DefaultEntityStatTypes.getHealth());
        double auraRadius = sourceHp == null || sourceHp.getMax() <= 0f
                ? baseRadius
                : resolveRadius(Math.max(0.0D, sourceHp.getMax()));
        return Math.max(TRIGGER_PULSE_START_RADIUS, auraRadius);
    }

    private int resolveTriggerPulsePointCount(double targetRadius) {
        return Math.max(TRIGGER_PULSE_MIN_POINT_COUNT,
                Math.min(TRIGGER_PULSE_MAX_POINT_COUNT, (int) Math.ceil(targetRadius * 3.0D)));
    }

    private int resolveTriggerPulseLayerCount(double targetRadius) {
        return TRIGGER_PULSE_MIN_LAYER_COUNT;
    }

    private double resolveTriggerPulseLayerSpacing(double targetRadius, int layerCount) {
        return 0.0D;
    }

    private static void spawnTriggerPulseParticle(Ref<EntityStore> sourceRef, Vector3d position) {
        for (String particleId : TRIGGER_PULSE_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(particleId, position, sourceRef.getStore());
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void playTriggerPulseSound(Ref<EntityStore> sourceRef, Vector3d position) {
        for (String soundId : TRIGGER_PULSE_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            SoundUtil.playSoundEvent3d(null, soundIndex, position, sourceRef.getStore());
            return;
        }
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private boolean isPetEntity(Ref<EntityStore> targetRef, CommandBuffer<EntityStore> commandBuffer) {
        return EntityRefUtil.tryGetComponent(commandBuffer, targetRef, NPCMountComponent.getComponentType()) != null;
    }

    private boolean isSamePartyTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            PartyManager partyManager) {
        if (sourceUuid == null || partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        PlayerRef targetPlayer = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return false;
        }

        UUID targetUuid = targetPlayer.getUuid();
        if (targetUuid == null) {
            return false;
        }

        UUID effectiveSourceLeader = sourcePartyLeader != null
                ? sourcePartyLeader
                : resolvePartyLeader(partyManager, sourceUuid);
        if (effectiveSourceLeader == null) {
            return false;
        }

        UUID targetLeader = resolvePartyLeader(partyManager, targetUuid);
        return targetLeader != null && targetLeader.equals(effectiveSourceLeader);
    }

    private UUID resolvePartyLeader(PartyManager partyManager, UUID playerUuid) {
        if (partyManager == null || !partyManager.isAvailable() || playerUuid == null) {
            return null;
        }
        if (!partyManager.isInParty(playerUuid)) {
            return null;
        }
        return partyManager.getPartyLeader(playerUuid);
    }

    private PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    private double resolveRadius(double attackerMaxHealth) {
        if (healthPerRadiusBlock <= 0.0D) {
            return baseRadius;
        }
        return baseRadius + Math.floor(attackerMaxHealth / healthPerRadiusBlock);
    }

    private static double normalizeSlowPercent(double rawValue) {
        double magnitude = Math.abs(rawValue);
        if (magnitude > 1.0D) {
            magnitude /= 100.0D;
        }
        return Math.min(0.95D, Math.max(0.0D, magnitude));
    }

    private static double clampRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
