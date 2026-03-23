package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
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

public final class PhaseRushAugment extends Augment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "phase_rush";
    private static final long INTERNAL_STACKING_DELAY_MILLIS = 400L;
    private static final String STACK_DELAY_STATE_ID = ID + "_stack_delay";
    private static final String HIT_COUNTER_WINDOW_STATE_ID = ID + "_hit_counter_window";
    private static final String BURST_AURA_STATE_ID = ID + "_burst_aura";
    private static final double TRIGGER_VFX_Y_OFFSET = 0.8D;
    private static final long AURA_REFRESH_INTERVAL_MILLIS = 900L;
    private static final String[] TRIGGER_AURA_EFFECT_IDS = new String[] {
        "Sword_Signature_SpinStab",
        "Mace_Signature",
        "Dagger_Signature"
    };
    private static final String[] TRIGGER_SFX_PRIMARY_IDS = new String[] {
        "SFX_Staff_Ice_Shoot",
        "SFX_Bow_T2_Draw_Local",
        "SFX_Arrow_Frost_Miss"
    };
    private static final int TRIGGER_SFX_PLAY_COUNT = 1;

    private final double baseHasteBonus;
    private final int hitsRequired;
    private final long hitCounterDurationMillis;
    private final double hasteBurstMultiplier;
    private final long hasteBurstDurationMillis;
    private final double hasteToDamageConversionPercent;

    public PhaseRushAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> hasteNode = AugmentValueReader.getMap(buffs, "haste");
        Map<String, Object> hitCounter = AugmentValueReader.getMap(passives, "hit_counter");
        Map<String, Object> hasteBurst = AugmentValueReader.getMap(passives, "haste_burst");
        Map<String, Object> conversion = AugmentValueReader.getMap(passives, "haste_to_damage_conversion");

        this.baseHasteBonus = Math.max(0.0D, AugmentValueReader.getDouble(hasteNode, "value", 0.0D));
        this.hitsRequired = Math.max(1, AugmentValueReader.getInt(hitCounter, "hits_required", 5));
        this.hitCounterDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(hitCounter, "duration", 0.0D));
        this.hasteBurstMultiplier = Math.max(1.0D, AugmentValueReader.getDouble(hasteBurst, "multiplier", 1.0D));
        this.hasteBurstDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(hasteBurst, "duration", 0.0D));
        this.hasteToDamageConversionPercent = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(conversion,
                        "conversion_percent",
                        0.0D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        boolean burstActive = state.getExpiresAt() > now;
        double hasteBonus = baseHasteBonus * (burstActive ? hasteBurstMultiplier : 1.0D);

        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteBonus * 100.0D,
                0L);

        if (!burstActive) {
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(BURST_AURA_STATE_ID));
            return;
        }

        AugmentState auraState = runtime.getState(BURST_AURA_STATE_ID);
        long burstExpiresAt = state.getExpiresAt();
        if (auraState.getExpiresAt() != burstExpiresAt || auraState.getStacks() <= 0) {
            auraState.setStacks(1);
            auraState.setExpiresAt(burstExpiresAt);
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

        refreshAuraEffect(context.getPlayerRef(), context.getCommandBuffer(), burstExpiresAt, now);
        auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
        auraState.setLastProc(now);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        var hitCounterWindowState = runtime.getState(HIT_COUNTER_WINDOW_STATE_ID);

        if (hitCounterDurationMillis > 0L
                && state.getStacks() > 0
                && hitCounterWindowState.getExpiresAt() > 0L
                && now >= hitCounterWindowState.getExpiresAt()) {
            state.setStacks(0);
            hitCounterWindowState.setExpiresAt(0L);
        }

        if (isStackDelayReady(runtime, now)) {
            int hits = state.getStacks() + 1;
            if (hits >= hitsRequired) {
                state.setStacks(0);
                hitCounterWindowState.setExpiresAt(0L);
                if (hasteBurstDurationMillis > 0L) {
                    state.setExpiresAt(now + hasteBurstDurationMillis);
                }

                long burstExpiresAt = state.getExpiresAt();
                if (burstExpiresAt > now) {
                    AugmentState auraState = runtime.getState(BURST_AURA_STATE_ID);
                    auraState.setStacks(1);
                    auraState.setExpiresAt(burstExpiresAt);
                    auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
                    auraState.setLastProc(now);
                    refreshAuraEffect(context.getAttackerRef(), context.getCommandBuffer(), burstExpiresAt, now);
                }

                playTriggerSound(context);

                var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
                if (playerRef != null && playerRef.isValid()) {
                    AugmentUtils.sendAugmentMessage(playerRef,
                            String.format("%s activated! Haste burst for %.1fs.",
                                    getName(),
                                    hasteBurstDurationMillis / 1000.0D));
                }
            } else {
                state.setStacks(hits);
                if (hitCounterDurationMillis > 0L) {
                    hitCounterWindowState.setExpiresAt(now + hitCounterDurationMillis);
                }
            }
            markStackDelay(runtime, now);
        }

        boolean burstActive = state.getExpiresAt() > now;
        double hasteBonus = baseHasteBonus * (burstActive ? hasteBurstMultiplier : 1.0D);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteBonus * 100.0D,
                0L);

        double conversionBonus = resolveHasteConversionBonus(context, burstActive);
        return AugmentUtils.applyAdditiveBonusFromBase(
                context.getDamage(),
                context.getBaseDamage(),
                conversionBonus);
    }

    private double resolveHasteConversionBonus(AugmentHooks.HitContext context, boolean burstActive) {
        if (!burstActive
                || hasteToDamageConversionPercent <= 0.0D
                || context.getSkillManager() == null
                || context.getPlayerData() == null) {
            return 0.0D;
        }

        SkillManager.HasteBreakdown breakdown = context.getSkillManager().getHasteBreakdown(context.getPlayerData());
        double hasteRatio = Math.max(0.0D, breakdown.totalMultiplier() - 1.0D);
        return hasteRatio * hasteToDamageConversionPercent;
    }

    private boolean isStackDelayReady(AugmentRuntimeState runtime, long now) {
        var delayState = runtime.getState(STACK_DELAY_STATE_ID);
        return delayState.getLastProc() <= 0L || now - delayState.getLastProc() >= INTERNAL_STACKING_DELAY_MILLIS;
    }

    private void markStackDelay(AugmentRuntimeState runtime, long now) {
        runtime.getState(STACK_DELAY_STATE_ID).setLastProc(now);
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
        TransformComponent transform = EntityRefUtil.tryGetComponent(
                commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }

        Vector3d position = transform.getPosition();
        return new Vector3d(
                position.getX(),
                position.getY() + TRIGGER_VFX_Y_OFFSET,
                position.getZ());
    }
}