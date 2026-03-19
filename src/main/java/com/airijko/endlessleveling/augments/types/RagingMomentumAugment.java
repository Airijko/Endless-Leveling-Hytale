package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
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

public final class RagingMomentumAugment extends YamlAugment
    implements AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "raging_momentum";
    private static final long INTERNAL_STACKING_DELAY_MILLIS = 400L;
    private static final String STACK_DELAY_STATE_ID = ID + "_stack_delay";
    private static final String AURA_STATE_ID = ID + "_aura";
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

    private final double perStackStrength; // percent per stack
    private final double perStackSorcery; // percent per stack
    private final int maxStacks;
    private final double durationPerStackSeconds;
    private final double decayPerSecond; // stacks lost per second after duration
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public RagingMomentumAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        this.perStackStrength = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
        this.perStackSorcery = AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery", "value");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 20);
        this.durationPerStackSeconds = AugmentValueReader.getDouble(buffs, "duration_per_stack", 8.0D);
        this.decayPerSecond = AugmentValueReader.getDouble(buffs, "decay_per_second", 0.0D);
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
        decayIfNeeded(state,
                runtime,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                now);

        int stacks = Math.max(0, state.getStacks());
        boolean wasAtFullStacks = isAtFullStacks(stacks);
        boolean gainedStack = false;
        if (stacks < maxStacks && isStackDelayReady(runtime, now)) {
            stacks = AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    stacks + 1,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    getName());
            gainedStack = stacks > 0;
            markStackDelay(runtime, now);
        }

        if (gainedStack) {
            long extendMillis = AugmentUtils.secondsToMillis(durationPerStackSeconds);
            if (extendMillis > 0L) {
                state.setExpiresAt(now + extendMillis);
            }
            state.setLastProc(now);
        }

        double strengthBonus = stacks * perStackStrength;
        double sorceryBonus = stacks * perStackSorcery;
        if (stacks >= maxStacks && maxStacks > 0) {
            strengthBonus *= 2.0D; // “doubles at cap” behaviour
            sorceryBonus *= 2.0D;
        }

        long durationMillis = state.getExpiresAt() > 0L ? state.getExpiresAt() - now : 0L;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthBonus * 100.0D,
                durationMillis);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBonus * 100.0D,
                durationMillis);

        double dbgStr = runtime.getAttributeBonus(SkillAttributeType.STRENGTH, now);
        double dbgSorc = runtime.getAttributeBonus(SkillAttributeType.SORCERY, now);
        LOGGER.atFine().log(
                "RagingMomentum set bonus: stacks=%d str=%.2f sorc=%.2f durMs=%d player=%s totalsNow str=%.2f sorc=%.2f expiresAt=%d now=%d",
                stacks,
                strengthBonus * 100.0D,
                sorceryBonus * 100.0D,
                durationMillis,
                context.getPlayerData().getPlayerName(),
                dbgStr,
                dbgSorc,
                state.getExpiresAt(),
                now);

        if (isAtFullStacks(stacks)) {
            AugmentState auraState = runtime.getState(AURA_STATE_ID);
            auraState.setStacks(1);
            auraState.setExpiresAt(state.getExpiresAt());
            if (auraState.getStoredValue() <= 0.0D) {
                auraState.setStoredValue(now);
            }

            if (gainedStack && !wasAtFullStacks) {
                boolean applied = refreshAuraEffect(context.getAttackerRef(), context.getCommandBuffer(), state, now);
                if (applied) {
                    auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
                    auraState.setLastProc(now);
                } else {
                    auraState.setStoredValue(now + 150L);
                }
                playTriggerSound(context);
            } else if ((long) auraState.getStoredValue() <= now) {
                boolean applied = refreshAuraEffect(context.getAttackerRef(), context.getCommandBuffer(), state, now);
                if (applied) {
                    auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
                    auraState.setLastProc(now);
                } else {
                    auraState.setStoredValue(now + 150L);
                }
            }
        } else {
            clearAura(context.getAttackerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
        }

        return context.getDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        var ragingState = runtime.getState(ID);
        long now = System.currentTimeMillis();
        PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
        decayIfNeeded(ragingState, runtime, playerRef, now);

        int stacks = Math.max(0, ragingState.getStacks());
        double strengthBonus = stacks * perStackStrength;
        double sorceryBonus = stacks * perStackSorcery;
        if (stacks >= maxStacks && maxStacks > 0) {
            strengthBonus *= 2.0D;
            sorceryBonus *= 2.0D;
        }

        AugmentUtils.setAttributeBonus(runtime,
            ID + "_str",
            SkillAttributeType.STRENGTH,
            strengthBonus * 100.0D,
            0L);
        AugmentUtils.setAttributeBonus(runtime,
            ID + "_sorc",
            SkillAttributeType.SORCERY,
            sorceryBonus * 100.0D,
            0L);

        if (ragingState.getExpiresAt() > 0L && ragingState.isExpired(now)) {
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return;
        }

        if (!isAtFullStacks(ragingState.getStacks())) {
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return;
        }

        var auraState = runtime.getState(AURA_STATE_ID);
        auraState.setStacks(1);
        auraState.setExpiresAt(ragingState.getExpiresAt());

        long nextRefreshAt = (long) auraState.getStoredValue();
        if (nextRefreshAt > now) {
            return;
        }

        boolean applied = refreshAuraEffect(context.getPlayerRef(), context.getCommandBuffer(), ragingState, now);
        if (applied) {
            auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
            auraState.setLastProc(now);
        } else {
            auraState.setStoredValue(now + 150L);
        }
    }

    private boolean isAtFullStacks(int stacks) {
        return maxStacks > 0 && stacks >= maxStacks;
    }

    private static boolean isStackDelayReady(AugmentRuntimeState runtime, long now) {
        var delayState = runtime.getState(STACK_DELAY_STATE_ID);
        return delayState.getLastProc() <= 0L || now - delayState.getLastProc() >= INTERNAL_STACKING_DELAY_MILLIS;
    }

    private static void markStackDelay(AugmentRuntimeState runtime, long now) {
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

    private boolean refreshAuraEffect(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            AugmentState ragingState,
            long now) {
        if (!EntityRefUtil.isUsable(targetRef) || commandBuffer == null || ragingState == null
                || !isAtFullStacks(ragingState.getStacks())) {
            return false;
        }

        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return false;
        }

        EntityEffect auraEffect = resolveAuraEffect();
        if (auraEffect == null) {
            return false;
        }

        float durationSeconds;
        if (ragingState.getExpiresAt() > now) {
            durationSeconds = Math.max(1.5F, (float) ((ragingState.getExpiresAt() - now) / 1000.0D));
        } else {
            durationSeconds = 2.0F;
        }

        return controller.addEffect(targetRef,
                auraEffect,
                durationSeconds,
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

    private void decayIfNeeded(AugmentState state, AugmentRuntimeState runtime, PlayerRef playerRef, long now) {
        if (state == null || runtime == null) {
            return;
        }
        long expiresAt = state.getExpiresAt();
        if (expiresAt <= 0L || decayPerSecond <= 0.0D) {
            return;
        }
        if (now <= expiresAt) {
            return;
        }
        double elapsedSeconds = (now - expiresAt) / 1000.0D;
        int decay = (int) Math.floor(elapsedSeconds * decayPerSecond);
        if (decay <= 0) {
            return;
        }
        int newStacks = Math.max(0, state.getStacks() - decay);
        if (newStacks != state.getStacks()) {
            newStacks = AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    newStacks,
                    maxStacks,
                    playerRef,
                    getName());
        }
        if (newStacks == 0) {
            state.setExpiresAt(0L);
        } else {
            // keep sliding window moving to allow further decay
            state.setExpiresAt(now);
        }
    }
}
