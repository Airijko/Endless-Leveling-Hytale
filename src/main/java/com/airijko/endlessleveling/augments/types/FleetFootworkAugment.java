package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.CommandBuffer;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class FleetFootworkAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String ID = "fleet_footwork";
    public static final String BUFF_WINDOW_STATE_ID = ID + "_buff_window";
    private static final String BUFF_AURA_STATE_ID = ID + "_buff_aura";
    private static final double TRIGGER_VFX_Y_OFFSET = 0.8D;
    private static final long AURA_REFRESH_INTERVAL_MILLIS = 900L;
    private static final String[] TRIGGER_AURA_EFFECT_IDS = new String[] {
        "Sword_Signature_SpinStab",
        "Mace_Signature",
        "Dagger_Signature"
    };
    private static final String[] TRIGGER_SFX_PRIMARY_IDS = new String[] {
        "SFX_Staff_Flame_Fireball_Impact",
        "SFX_Ice_Ball_Death",
        "SFX_Arrow_Frost_Miss"
    };
    private static final int TRIGGER_SFX_PLAY_COUNT = 1;

    private final long cooldownMillis;
    private final double healPercentOfDamage;
    private final double movementSpeedBonus;
    private final long movementDurationMillis;

    public FleetFootworkAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> empoweredHit = AugmentValueReader.getMap(passives, "empowered_hit");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> movementSpeed = AugmentValueReader.getMap(buffs, "movement_speed");

        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(empoweredHit, "cooldown", 8.0D));
        this.healPercentOfDamage = Math.max(0.0D,
                AugmentValueReader.getDouble(empoweredHit, "heal_percent_of_damage", 0.0D));
        this.movementSpeedBonus = AugmentValueReader.getDouble(movementSpeed, "value", 0.0D);
        this.movementDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(movementSpeed, "duration", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        var runtime = context.getRuntimeState();
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();

        if (healPercentOfDamage > 0.0D) {
            AugmentUtils.heal(context.getAttackerStats(), context.getDamage() * healPercentOfDamage);
        }

        if (movementSpeedBonus != 0.0D) {
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_haste",
                    SkillAttributeType.HASTE,
                    movementSpeedBonus * 100.0D,
                    movementDurationMillis);
            long expiresAt = movementDurationMillis > 0L ? now + movementDurationMillis : 0L;
            var state = runtime.getState(BUFF_WINDOW_STATE_ID);
            state.setStacks(1);
            state.setExpiresAt(expiresAt);
            state.setLastProc(now);

            var auraState = runtime.getState(BUFF_AURA_STATE_ID);
            if (expiresAt > 0L) {
                auraState.setStacks(1);
                auraState.setExpiresAt(expiresAt);
                auraState.setStoredValue(now);
                auraState.setLastProc(now);
                refreshAuraEffect(context.getAttackerRef(), context.getCommandBuffer(), expiresAt, now);
            } else {
                clearAura(context.getAttackerRef(), context.getCommandBuffer(), auraState);
            }
        }

        playTriggerSound(context);

        String playerId = context.getPlayerData() != null && context.getPlayerData().getUuid() != null
                ? context.getPlayerData().getUuid().toString()
                : "unknown";
        LOGGER.atInfo().log(
                "Fleet Footwork activated for player=%s damage=%.2f healPct=%.3f hastePct=%.3f durationMs=%d cooldownMs=%d",
                playerId,
                context.getDamage(),
                healPercentOfDamage,
                movementSpeedBonus,
                movementDurationMillis,
                cooldownMillis);

        return context.getDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        var buffState = context.getRuntimeState().getState(BUFF_WINDOW_STATE_ID);
        long expiresAt = buffState.getExpiresAt();
        if (expiresAt <= 0L || now >= expiresAt || buffState.getStacks() <= 0) {
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), context.getRuntimeState().getState(BUFF_AURA_STATE_ID));
            if (expiresAt > 0L && now >= expiresAt && buffState.getStacks() > 0) {
                buffState.setStacks(0);
            }
            return;
        }

        var auraState = context.getRuntimeState().getState(BUFF_AURA_STATE_ID);
        if (auraState.getExpiresAt() != expiresAt || auraState.getStacks() <= 0) {
            auraState.setStacks(1);
            auraState.setExpiresAt(expiresAt);
            auraState.setStoredValue(now);
        }

        if (auraState.getStacks() <= 0 || now >= auraState.getExpiresAt()) {
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), auraState);
            return;
        }

        long nextRefreshAt = (long) auraState.getStoredValue();
        if (nextRefreshAt > now) {
            return;
        }

        refreshAuraEffect(context.getPlayerRef(), context.getCommandBuffer(), expiresAt, now);
        auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
        auraState.setLastProc(now);
    }

    private void playTriggerSound(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        Ref<EntityStore> attackerRef = context.getAttackerRef();
        if (!EntityRefUtil.isUsable(attackerRef)) {
            return;
        }

        Vector3d attackerPosition = resolveEffectPosition(context.getCommandBuffer(), attackerRef);
        if (attackerPosition == null) {
            return;
        }

        int primaryIndex = resolveFirstAvailableSoundIndex(TRIGGER_SFX_PRIMARY_IDS, 0);
        if (primaryIndex == 0) {
            return;
        }

        PlayerRef playerRef = EntityRefUtil.tryGetComponent(
                context.getCommandBuffer(),
                attackerRef,
                PlayerRef.getComponentType());

        for (int i = 0; i < TRIGGER_SFX_PLAY_COUNT; i++) {
            if (playerRef != null && playerRef.isValid()) {
                SoundUtil.playSoundEvent2d(attackerRef, primaryIndex, SoundCategory.SFX, attackerRef.getStore());
            } else {
                SoundUtil.playSoundEvent3d(null, primaryIndex, attackerPosition, attackerRef.getStore());
            }
        }
    }

    private static int resolveFirstAvailableSoundIndex(String[] ids, int excludedIndex) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        for (String id : ids) {
            int index = resolveSoundIndex(id);
            if (index == 0 || index == excludedIndex) {
                continue;
            }
            return index;
        }
        return 0;
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private void refreshAuraEffect(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            long expiresAt,
            long now) {
        if (!EntityRefUtil.isUsable(targetRef) || commandBuffer == null || expiresAt <= now) {
            return;
        }

        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }

        EntityEffect auraEffect = resolveAuraEffect();
        if (auraEffect == null) {
            return;
        }

        float remainingSeconds = Math.max(0.1F, (float) ((expiresAt - now) / 1000.0D));
        controller.addEffect(targetRef,
                auraEffect,
                remainingSeconds,
                OverlapBehavior.OVERWRITE,
                commandBuffer);
    }

    private static EntityEffect resolveAuraEffect() {
        for (String candidate : TRIGGER_AURA_EFFECT_IDS) {
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

    private void clearAura(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            AugmentState auraState) {
        if (EntityRefUtil.isUsable(targetRef) && commandBuffer != null) {
            EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                    targetRef,
                    EffectControllerComponent.getComponentType());
            if (controller != null) {
                for (String candidate : TRIGGER_AURA_EFFECT_IDS) {
                    int effectIndex = EntityEffect.getAssetMap().getIndex(candidate);
                    if (effectIndex != Integer.MIN_VALUE) {
                        controller.removeEffect(targetRef, effectIndex, commandBuffer);
                    }
                }
            }
        }

        clearAuraState(auraState);
    }

    private void clearAuraState(AugmentState auraState) {
        if (auraState == null) {
            return;
        }
        auraState.setStacks(0);
        auraState.setExpiresAt(0L);
        auraState.setStoredValue(0.0D);
        auraState.setLastProc(0L);
    }

    private Vector3d resolveEffectPosition(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> sourceRef) {
        TransformComponent attackerTransform = EntityRefUtil.tryGetComponent(
                commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (attackerTransform == null || attackerTransform.getPosition() == null) {
            return null;
        }

        Vector3d attackerPosition = attackerTransform.getPosition();
        return new Vector3d(
                attackerPosition.getX(),
                attackerPosition.getY() + TRIGGER_VFX_Y_OFFSET,
                attackerPosition.getZ());
    }
}
