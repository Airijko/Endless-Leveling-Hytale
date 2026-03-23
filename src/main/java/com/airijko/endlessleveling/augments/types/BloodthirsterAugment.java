package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDamageSafety;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class BloodthirsterAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "bloodthirster";
    private static final long INTERNAL_STACKING_DELAY_MILLIS = 400L;
    private static final String STACK_DELAY_STATE_ID = ID + "_stack_delay";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private static final int MODE_NONE = 0;
    private static final int MODE_HEALTHY = 1;
    private static final int MODE_WOUNDED = 2;
    private static final double TRIGGER_VFX_Y_OFFSET = 1.0D;
    private static final String[] TRIGGER_VFX_IDS = new String[] {
            "Sword_Signature_AoE2",
            "Sword_Signature_AoE",
            "Impact_Sword_Basic",
            "Explosion_Small"
    };

    // Placeholder SFX for testing — differentiate healthy/wounded sounds below later
    private static final String TRIGGER_SFX_HEALTHY = "SFX_Sword_T2_Signature_Part_2";
    private static final String TRIGGER_SFX_WOUNDED = "SFX_Sword_T2_Signature_Part_2";
    private static final int TRIGGER_SFX_PLAY_COUNT = 3;

    private final double healthyThresholdAbove;
    private final int sharedHitCounter;
    private final long sharedHitCounterDurationMillis;
    private final double healthyFlatDamage;
    private final double healthyStrengthScaling;
    private final double healthySelfDamagePercentOfCurrent;

    private final double woundedThresholdBelow;
    private final double woundedMissingHealthPercent;
    private final double woundedStrengthScaling;
    private final double woundedSorceryScaling;

    public BloodthirsterAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> healthyState = AugmentValueReader.getMap(passives, "healthy_state");
        Map<String, Object> healthyBonus = AugmentValueReader.getMap(healthyState, "bonus_damage");
        Map<String, Object> healthySelfDamage = AugmentValueReader.getMap(healthyState, "self_damage");
        Map<String, Object> woundedState = AugmentValueReader.getMap(passives, "wounded_state");
        Map<String, Object> woundedHealing = AugmentValueReader.getMap(woundedState, "healing");

        int configuredHitCounter = AugmentValueReader.getInt(passives, "hit_counter", 0);
        if (configuredHitCounter <= 0) {
            configuredHitCounter = Math.max(
                    AugmentValueReader.getInt(healthyState, "hit_counter", 0),
                    AugmentValueReader.getInt(woundedState, "hit_counter", 0));
        }
        double configuredDurationSeconds = AugmentValueReader.getDouble(passives, "hit_counter_duration", 0.0D);
        if (configuredDurationSeconds <= 0.0D) {
            configuredDurationSeconds = Math.max(
                    AugmentValueReader.getDouble(healthyState, "hit_counter_duration", 0.0D),
                    AugmentValueReader.getDouble(woundedState, "hit_counter_duration", 0.0D));
        }

        this.healthyThresholdAbove = clampRatio(
                AugmentValueReader.getDouble(healthyState, "health_threshold_above", 0.50D));
        this.sharedHitCounter = Math.max(1, configuredHitCounter > 0 ? configuredHitCounter : 3);
        this.sharedHitCounterDurationMillis = AugmentUtils.secondsToMillis(configuredDurationSeconds);
        this.healthyFlatDamage = Math.max(0.0D,
            AugmentValueReader.getDouble(healthyBonus, "flat_damage", 0.0D));
        this.healthyStrengthScaling = Math.max(0.0D,
            AugmentValueReader.getDouble(healthyBonus, "strength_scaling", 0.0D));
        this.healthySelfDamagePercentOfCurrent = clampRatio(
                AugmentValueReader.getDouble(healthySelfDamage, "percent_of_current_hp", 0.0D));

        this.woundedThresholdBelow = clampRatio(
                AugmentValueReader.getDouble(woundedState, "health_threshold_below", 0.50D));
        this.woundedMissingHealthPercent = clampRatio(
                AugmentValueReader.getDouble(woundedHealing, "missing_health_percent", 0.0D));
        this.woundedStrengthScaling = Math.max(0.0D,
                AugmentValueReader.getDouble(woundedHealing, "strength_scaling", 0.0D));
        this.woundedSorceryScaling = Math.max(0.0D,
                AugmentValueReader.getDouble(woundedHealing, "sorcery_scaling", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        EntityStatMap attackerStats = context.getAttackerStats();
        EntityStatValue hp = attackerStats == null ? null : attackerStats.get(DefaultEntityStatTypes.getHealth());
        if (runtime == null || hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }

        double healthRatio = Math.max(0.0D, Math.min(1.0D, hp.get() / hp.getMax()));
        int mode = healthRatio > healthyThresholdAbove ? MODE_HEALTHY
                : (healthRatio <= woundedThresholdBelow ? MODE_WOUNDED : MODE_NONE);
        if (mode == MODE_NONE) {
            return context.getDamage();
        }

        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (sharedHitCounterDurationMillis > 0L
                && state.getStacks() > 0
                && state.getExpiresAt() > 0L
                && now >= state.getExpiresAt()) {
            state.setStacks(0);
            state.setExpiresAt(0L);
        }

        if (!isStackDelayReady(runtime, now)) {
            return context.getDamage();
        }

        int nextHits = state.getStacks() + 1;
        if (nextHits < sharedHitCounter) {
            state.setStacks(nextHits);
            if (sharedHitCounterDurationMillis > 0L) {
                state.setExpiresAt(now + sharedHitCounterDurationMillis);
            }
            markStackDelay(runtime, now);
            return context.getDamage();
        }

        state.setStacks(0);
        state.setExpiresAt(0L);
        markStackDelay(runtime, now);
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());

        if (mode == MODE_HEALTHY) {
            double healthyBonusDamage = resolveHealthyBonusDamage(context);
            applyHealthySelfDamage(attackerStats, healthySelfDamagePercentOfCurrent);
            playTriggerSound(context, TRIGGER_SFX_HEALTHY);
            playTriggerVfx(context);

            if (healthyBonusDamage > 0.0D
                    && context.getCommandBuffer() != null
                    && context.getTargetRef() != null
                    && EntityRefUtil.isUsable(context.getTargetRef())) {
                Damage proc = PlayerCombatSystem.createAugmentProcDamage(context.getAttackerRef(),
                        (float) healthyBonusDamage);
                AugmentDamageSafety.tryExecuteDamage(context.getTargetRef(), context.getCommandBuffer(), proc, ID);
            }

            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                String.format("%s activated! +%.1f bonus damage (healthy state).",
                    getName(), healthyBonusDamage));
            }
            return context.getDamage();
        }

        double healAmount = resolveWoundedHealing(context, hp);
        float healed = AugmentUtils.heal(attackerStats, healAmount);
        playTriggerSound(context, TRIGGER_SFX_WOUNDED);
        playTriggerVfx(context);
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    String.format("%s activated! Healed %.1f HP (wounded state).", getName(), healed));
        }
        return context.getDamage();
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private void playTriggerSound(AugmentHooks.HitContext context, String soundId) {
        int soundIndex = resolveSoundIndex(soundId);
        if (soundIndex == 0) {
            return;
        }
        var attackerRef = context.getAttackerRef();
        if (!EntityRefUtil.isUsable(attackerRef)) {
            return;
        }
        TransformComponent attackerTransform = EntityRefUtil.tryGetComponent(
            context.getCommandBuffer(), attackerRef, TransformComponent.getComponentType());
        if (attackerTransform == null || attackerTransform.getPosition() == null) {
            return;
        }
        for (int i = 0; i < TRIGGER_SFX_PLAY_COUNT; i++) {
            SoundUtil.playSoundEvent3d(null, soundIndex, attackerTransform.getPosition(), attackerRef.getStore());
        }
    }

    private void playTriggerVfx(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            LOGGER.atInfo().log("[BLOODTHIRSTER] VFX skipped: missing context/commandBuffer");
            return;
        }
        Ref<EntityStore> targetRef = context.getTargetRef();
        if (!EntityRefUtil.isUsable(targetRef)) {
            LOGGER.atInfo().log("[BLOODTHIRSTER] VFX skipped: target ref unusable");
            return;
        }

        TransformComponent targetTransform = EntityRefUtil.tryGetComponent(context.getCommandBuffer(),
                targetRef,
                TransformComponent.getComponentType());
        if (targetTransform == null || targetTransform.getPosition() == null) {
            LOGGER.atInfo().log("[BLOODTHIRSTER] VFX skipped: target transform/position missing");
            return;
        }

        Vector3d baseTargetPosition = targetTransform.getPosition();
        Vector3d targetPosition = new Vector3d(
            baseTargetPosition.getX(),
            baseTargetPosition.getY() + TRIGGER_VFX_Y_OFFSET,
            baseTargetPosition.getZ());
        LOGGER.atInfo().log("[BLOODTHIRSTER] VFX trigger at pos=(%.2f, %.2f, %.2f)",
                targetPosition.getX(),
                targetPosition.getY(),
                targetPosition.getZ());

        for (String vfxId : TRIGGER_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(vfxId, targetPosition, targetRef.getStore());
                LOGGER.atInfo().log("[BLOODTHIRSTER] VFX applied id=%s", vfxId);
                return;
            } catch (RuntimeException ex) {
                LOGGER.atWarning().log("[BLOODTHIRSTER] VFX failed id=%s cause=%s", vfxId, ex.getMessage());
            }
        }
        LOGGER.atWarning().log("[BLOODTHIRSTER] VFX failed: no candidate particle system could be spawned");
    }

    private double resolveWoundedHealing(AugmentHooks.HitContext context, EntityStatValue hp) {
        if (hp == null || hp.getMax() <= 0f || woundedMissingHealthPercent <= 0.0D) {
            return 0.0D;
        }
        double missing = Math.max(0.0D, hp.getMax() - hp.get());
        double baseHeal = missing * woundedMissingHealthPercent;

        double strength = AugmentUtils.resolveStrength(context);
        double sorcery = AugmentUtils.resolveSorcery(context);
        double scalingMultiplier = 1.0D
                + ((strength * woundedStrengthScaling) / 100.0D)
                + ((sorcery * woundedSorceryScaling) / 100.0D);
        return Math.max(0.0D, baseHeal * scalingMultiplier);
    }

    private void applyHealthySelfDamage(EntityStatMap statMap, double percentOfCurrent) {
        if (statMap == null || percentOfCurrent <= 0.0D) {
            return;
        }
        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.get() <= 0f) {
            return;
        }
        float current = hp.get();
        float selfDamage = (float) (current * percentOfCurrent);
        float updated = Math.max(1.0f, current - selfDamage);
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), updated);
    }

    private double resolveHealthyBonusDamage(AugmentHooks.HitContext context) {
        double strength = AugmentUtils.resolveStrength(context);
        return healthyFlatDamage + (strength * healthyStrengthScaling);
    }

    private static boolean isStackDelayReady(AugmentRuntimeState runtime, long now) {
        var delayState = runtime.getState(STACK_DELAY_STATE_ID);
        return delayState.getLastProc() <= 0L || now - delayState.getLastProc() >= INTERNAL_STACKING_DELAY_MILLIS;
    }

    private static void markStackDelay(AugmentRuntimeState runtime, long now) {
        runtime.getState(STACK_DELAY_STATE_ID).setLastProc(now);
    }

    private double clampRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}