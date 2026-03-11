package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PartyManager;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FrozenDomainAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "frozen_domain";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long REAPPLY_GRACE_MILLIS = 1500L;
    private static final float MIN_MOVEMENT_MULTIPLIER = 0.0001F;
    private static final float FREEZE_EFFECT_DURATION_SECONDS = 1.25F;
    private static final String[] FREEZE_EFFECT_IDS = new String[] { "freeze", "Freeze" };
    private static final String[] SLOW_EFFECT_IDS = new String[] { "slowness", "slow" };
    private static final Map<String, ActiveFrozen> ACTIVE_FROST = new ConcurrentHashMap<>();

    private final double slowPercent;
    private final double stolenSlowRatio;
    private final double baseRadius;
    private final double healthPerRadiusBlock;
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
        boolean loggedMissingFreezeEffectAsset;
        boolean loggedEffectApplyFailure;
        boolean freezeEffectApplied;
        String freezeEffectId;
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
        this.lifeForceFlatBonus = Math.max(0.0D, AugmentValueReader.getDouble(lifeForce, "value", 0.0D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null || context.getStatMap() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        EntityStatMap sourceStats = context.getStatMap();
        EntityStatValue sourceHp = sourceStats.get(DefaultEntityStatTypes.getHealth());
        if (sourceHp == null || sourceHp.getMax() <= 0f || sourceHp.get() <= 0f) {
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_life_force", SkillAttributeType.LIFE_FORCE,
                    lifeForceFlatBonus, 0L);
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_stolen_haste", SkillAttributeType.HASTE,
                    0.0D, 0L);
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        double maxHealth = Math.max(0.0D, sourceHp.getMax());
        double radius = resolveRadius(maxHealth);

        AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_life_force", SkillAttributeType.LIFE_FORCE,
                lifeForceFlatBonus, 0L);

        if (radius <= 0.0D || slowPercent <= 0.0D) {
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_stolen_haste", SkillAttributeType.HASTE,
                    0.0D, 0L);
            cleanupExpired(context.getCommandBuffer(), now);
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> sourceRef = context.getPlayerRef();
        TransformComponent sourceTransform = commandBuffer.getComponent(sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_stolen_haste", SkillAttributeType.HASTE,
                    0.0D, 0L);
            cleanupExpired(commandBuffer, now);
            return;
        }

        UUID sourceUuid = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
        PartyManager partyManager = resolvePartyManager();
        UUID sourcePartyLeader = resolvePartyLeader(partyManager, sourceUuid);
        EntityEffect freezeEffect = resolveFreezeEffect();

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

            EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
            EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
                continue;
            }

            String key = keyFor(targetRef, commandBuffer);
            ActiveFrozen state = ACTIVE_FROST.computeIfAbsent(key, unused -> new ActiveFrozen());
            state.targetRef = targetRef;
            state.expiresAt = now + REAPPLY_GRACE_MILLIS;
            state.slowPercent = slowPercent;
            applySlowIfPossible(state, commandBuffer, targetRef, freezeEffect);
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
            Ref<EntityStore> ref,
            EntityEffect freezeEffect) {
        if (state == null || commandBuffer == null || ref == null || state.slowPercent <= 0.0D) {
            return;
        }

        String key = keyFor(ref, commandBuffer);
        applyFreezeEffect(state, commandBuffer, ref, key, freezeEffect);

        MovementManager movementManager = commandBuffer.getComponent(ref, MovementManager.getComponentType());
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
    }

    private static void clearSlowIfPossible(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null) {
            return;
        }

        String key = keyFor(ref, commandBuffer);
        clearFreezeEffect(state, commandBuffer, ref);
        clearSlowEffectFallback(state, commandBuffer, ref, key);

        MovementManager movementManager = commandBuffer.getComponent(ref, MovementManager.getComponentType());
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

    private static void applyFreezeEffect(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            String key,
            EntityEffect freezeEffect) {
        if (state == null || commandBuffer == null || ref == null) {
            return;
        }

        if (freezeEffect == null) {
            if (!state.loggedMissingFreezeEffectAsset) {
                LOGGER.atWarning().log(
                        "Frozen Domain freeze effect unavailable: no freeze effect asset found key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingFreezeEffectAsset = true;
            }
            return;
        }

        EffectControllerComponent controller = commandBuffer.getComponent(ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            if (!state.loggedMissingEffectController) {
                LOGGER.atWarning().log(
                        "Frozen Domain freeze effect unavailable: EffectController missing key=%s target=%s",
                        key,
                        ref);
                state.loggedMissingEffectController = true;
            }
            return;
        }

        boolean applied = controller.addEffect(ref,
                freezeEffect,
                FREEZE_EFFECT_DURATION_SECONDS,
                OverlapBehavior.OVERWRITE,
                commandBuffer);
        if (applied) {
            state.freezeEffectApplied = true;
            state.freezeEffectId = freezeEffect.getId();
        }
    }

    private static void clearFreezeEffect(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref) {
        if (state == null || commandBuffer == null || ref == null || !state.freezeEffectApplied
                || state.freezeEffectId == null) {
            return;
        }

        EffectControllerComponent controller = commandBuffer.getComponent(ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }

        int idx = EntityEffect.getAssetMap().getIndex(state.freezeEffectId);
        if (idx != Integer.MIN_VALUE) {
            controller.removeEffect(ref, idx, commandBuffer);
        }

        state.freezeEffectApplied = false;
        state.freezeEffectId = null;
    }

    private static void applySlowEffectFallback(ActiveFrozen state,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref,
            String key) {
        EffectControllerComponent controller = commandBuffer.getComponent(ref,
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

        EffectControllerComponent controller = commandBuffer.getComponent(ref,
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

    private static EntityEffect resolveFreezeEffect() {
        for (String candidate : FREEZE_EFFECT_IDS) {
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

    private boolean isPetEntity(Ref<EntityStore> targetRef, CommandBuffer<EntityStore> commandBuffer) {
        return commandBuffer.getComponent(targetRef, NPCMountComponent.getComponentType()) != null;
    }

    private boolean isSamePartyTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            PartyManager partyManager) {
        if (sourceUuid == null || partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
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
