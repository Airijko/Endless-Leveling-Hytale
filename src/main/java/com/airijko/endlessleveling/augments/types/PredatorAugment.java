package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
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

public final class PredatorAugment extends YamlAugment
    implements AugmentHooks.OnKillAugment, AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "predator";
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

    private final int maxStacks;
    private final long durationMillis;
    private final double strengthPerStack;
    private final double hastePerStack;
    private final double defensePenalty;
    private final boolean defensePenaltyActiveUntilMaxStacks;

    public PredatorAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> duration = AugmentValueReader.getMap(passives, "duration");
        this.maxStacks = AugmentValueReader.getInt(buffs, "max_stacks", 0);
        double durationSeconds = AugmentValueReader.getDouble(duration, "seconds", 0.0D);
        if (durationSeconds <= 0.0D) {
            // Backward compatibility for older predator.yml layout.
            durationSeconds = Math.max(
                    AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "duration"),
                    AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "duration"));
        }
        this.durationMillis = AugmentUtils.secondsToMillis(durationSeconds);
        this.strengthPerStack = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value"));
        this.hastePerStack = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "haste", "value"));
        Map<String, Object> debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.defensePenalty = AugmentUtils.normalizeConfiguredDebuffMultiplier(
            AugmentValueReader.getNestedDouble(debuffs, 0.0D, "defense", "value"));
        Map<String, Object> defenseDebuff = AugmentValueReader.getMap(debuffs, "defense");
        this.defensePenaltyActiveUntilMaxStacks = AugmentValueReader.getBoolean(defenseDebuff,
                "active_until_max_stacks",
                true);
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        if (context == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }

        var state = runtime.getState(ID);
        int existingStacks = state.getStacks();
        boolean wasAtFullStacks = isAtFullStacks(existingStacks);
        int stacks = AugmentUtils.setStacksWithNotify(runtime,
                ID,
                existingStacks + 1,
                maxStacks,
                AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getKillerRef()),
                getName());
        long now = System.currentTimeMillis();
        state.setExpiresAt(durationMillis > 0L ? now + durationMillis : 0L);
        applyAttributeBonuses(runtime, stacks, state.getExpiresAt());

        if (isAtFullStacks(stacks)) {
            AugmentState auraState = runtime.getState(AURA_STATE_ID);
            auraState.setStacks(1);
            auraState.setExpiresAt(state.getExpiresAt());
            if (auraState.getStoredValue() <= 0.0D) {
                auraState.setStoredValue(now);
            }

            if (!wasAtFullStacks) {
                boolean applied = refreshAuraEffect(context.getKillerRef(), context.getCommandBuffer(), state, now);
                if (applied) {
                    auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
                    auraState.setLastProc(now);
                } else {
                    auraState.setStoredValue(now + 150L);
                }
                playTriggerSound(context.getCommandBuffer(), context.getKillerRef());
            } else if ((long) auraState.getStoredValue() <= now) {
                boolean applied = refreshAuraEffect(context.getKillerRef(), context.getCommandBuffer(), state, now);
                if (applied) {
                    auraState.setStoredValue(now + AURA_REFRESH_INTERVAL_MILLIS);
                    auraState.setLastProc(now);
                } else {
                    auraState.setStoredValue(now + 150L);
                }
            }
        } else {
            clearAura(context.getKillerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
        }
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

        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            AugmentUtils.setStacksWithNotify(runtime,
                    ID,
                    0,
                    maxStacks,
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    getName());
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
            clearAura(context.getAttackerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return context.getDamage();
        }
        if (state.getStacks() <= 0) {
            clearAura(context.getAttackerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return context.getDamage();
        }
        applyAttributeBonuses(runtime, state.getStacks(), state.getExpiresAt());

        if (!isAtFullStacks(state.getStacks())) {
            clearAura(context.getAttackerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
        }

        double bonus = state.getStacks() * strengthPerStack;
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), bonus);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getPlayerRef() == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            state.clear();
            applyAttributeBonuses(runtime, 0, 0L);
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return;
        }

        if (state.getStacks() <= 0) {
            applyAttributeBonuses(runtime, 0, 0L);
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return;
        }

        if (!isAtFullStacks(state.getStacks())) {
            applyAttributeBonuses(runtime, state.getStacks(), state.getExpiresAt());
            clearAura(context.getPlayerRef(), context.getCommandBuffer(), runtime.getState(AURA_STATE_ID));
            return;
        }

        applyAttributeBonuses(runtime, state.getStacks(), state.getExpiresAt());

        AugmentState auraState = runtime.getState(AURA_STATE_ID);
        auraState.setStacks(1);
        auraState.setExpiresAt(state.getExpiresAt());

        long nextRefreshAt = (long) auraState.getStoredValue();
        if (nextRefreshAt > now) {
            return;
        }

        boolean applied = refreshAuraEffect(context.getPlayerRef(), context.getCommandBuffer(), state, now);
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

    private void playTriggerSound(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> sourceRef) {
        if (commandBuffer == null || !EntityRefUtil.isUsable(sourceRef)) {
            return;
        }

        Vector3d sourcePosition = resolveEffectPosition(commandBuffer, sourceRef);
        if (sourcePosition == null) {
            return;
        }

        int primaryIndex = resolveFirstAvailableSoundIndex(TRIGGER_SFX_PRIMARY_IDS, 0);
        if (primaryIndex == 0) {
            return;
        }

        PlayerRef playerRef = EntityRefUtil.tryGetComponent(
                commandBuffer,
                sourceRef,
                PlayerRef.getComponentType());

        for (int i = 0; i < TRIGGER_SFX_PLAY_COUNT; i++) {
            if (playerRef != null && playerRef.isValid()) {
                SoundUtil.playSoundEvent2d(sourceRef, primaryIndex, SoundCategory.SFX, sourceRef.getStore());
            } else {
                SoundUtil.playSoundEvent3d(null, primaryIndex, sourcePosition, sourceRef.getStore());
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
            AugmentState predatorState,
            long now) {
        if (!EntityRefUtil.isUsable(targetRef) || commandBuffer == null || predatorState == null
                || !isAtFullStacks(predatorState.getStacks())) {
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
        if (durationMillis > 0L && predatorState.getExpiresAt() > now) {
            durationSeconds = Math.max(1.5F, (float) ((predatorState.getExpiresAt() - now) / 1000.0D));
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

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stacks, long expiresAt) {
        if (runtime == null) {
            return;
        }
        long expiresAtMillis = durationMillis > 0L && stacks > 0 ? Math.max(0L, expiresAt) : 0L;
        double hasteBonus = stacks * hastePerStack * 100.0D;
        double strengthBonus = stacks * strengthPerStack * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.HASTE, ID + "_haste", hasteBonus, expiresAtMillis);
        runtime.setAttributeBonus(SkillAttributeType.STRENGTH, ID + "_str", strengthBonus, expiresAtMillis);

        boolean removeAtMaxStacks = defensePenaltyActiveUntilMaxStacks && maxStacks > 0 && stacks >= maxStacks;
        double defenseValue = stacks <= 0 || removeAtMaxStacks ? 0.0D : defensePenalty * 100.0D;
        runtime.setAttributeBonus(SkillAttributeType.DEFENSE, ID + "_def", defenseValue, expiresAtMillis);
    }
}
