package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AmbushAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "ambush";
    private static final double MOVEMENT_EPSILON_SQ = 0.0025D;

    private static final class StealthState {
        double lastX;
        double lastZ;
        boolean hasLastPos;
        double idleSeconds;
        long buffExpiresAt;
        boolean buffsApplied;
        boolean invisApplied;
    }

    private static final Map<UUID, StealthState> STATES = new ConcurrentHashMap<>();

    private final double triggerAfterSeconds;
    private final double durationSeconds;
    private final double strengthBonus;
    private final double sorceryBonus;
    private final String effectId;

    public AmbushAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> invis = AugmentValueReader.getMap(passives, "invisibility_on_idle");
        this.triggerAfterSeconds = AugmentValueReader.getDouble(invis, "trigger_after_seconds", 0.0D);
        this.durationSeconds = AugmentValueReader.getDouble(invis, "duration", 0.0D);
        Map<String, Object> buffs = AugmentValueReader.getMap(invis, "buffs");
        this.strengthBonus = AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value");
        this.sorceryBonus = AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery", "value");
        Object effectObj = invis.get("effect");
        this.effectId = effectObj != null ? effectObj.toString() : "";
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        Ref<EntityStore> ref = context.getPlayerRef();
        var commandBuffer = context.getCommandBuffer();
        if (runtime == null || ref == null || commandBuffer == null) {
            return;
        }

        UUID playerId = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
        if (playerId == null) {
            return;
        }

        StealthState state = STATES.computeIfAbsent(playerId, id -> new StealthState());
        long now = System.currentTimeMillis();

        if (state.buffExpiresAt > 0 && now >= state.buffExpiresAt) {
            state.buffExpiresAt = 0L;
            state.buffsApplied = false;
        }

        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        var pos = transform != null ? transform.getPosition() : null;
        boolean moved = false;
        if (pos == null) {
            moved = true;
            state.hasLastPos = false;
        } else if (state.hasLastPos) {
            double dx = pos.getX() - state.lastX;
            double dz = pos.getZ() - state.lastZ;
            moved = (dx * dx + dz * dz) > MOVEMENT_EPSILON_SQ;
        }
        if (pos != null) {
            state.lastX = pos.getX();
            state.lastZ = pos.getZ();
            state.hasLastPos = true;
        }

        if (moved) {
            state.idleSeconds = 0.0D;
            if (state.invisApplied) {
                handleInvisBreak(state, ref, commandBuffer, now);
            }
        } else {
            state.idleSeconds += context.getDeltaSeconds();
        }

        if (!state.buffsApplied && triggerAfterSeconds > 0.0D && state.idleSeconds >= triggerAfterSeconds) {
            if (effectId != null && !effectId.isBlank()
                    && applyInvisibility(ref, commandBuffer, effectId, (float) durationSeconds)) {
                state.invisApplied = true;
                state.buffsApplied = true;
                state.buffExpiresAt = 0L;
                notifyPlayer(ref, commandBuffer, "Ambush active: You are now invisible and empowered.");
            }
        }

        if (state.buffsApplied && (state.invisApplied || state.buffExpiresAt > 0L)) {
            double scale = 1.0D;
            // While invis is active, keep buff indefinite and let explicit break/clear
            // remove it.
            long bonusDurationMillis = 0L;
            if (state.buffExpiresAt > 0L) {
                long totalMillis = AugmentUtils.secondsToMillis(durationSeconds);
                if (totalMillis <= 0L || now >= state.buffExpiresAt) {
                    state.buffsApplied = false;
                    applyAttributeBonuses(runtime, 0.0D, 0L);
                    return;
                }
                scale = Math.max(0.0D, (double) (state.buffExpiresAt - now) / (double) totalMillis);
                if (scale <= 0.0D) {
                    state.buffsApplied = false;
                    applyAttributeBonuses(runtime, 0.0D, 0L);
                    return;
                }
                bonusDurationMillis = Math.max(50L, state.buffExpiresAt - now);
            }
            applyAttributeBonuses(runtime, scale, bonusDurationMillis);
        } else if (!state.invisApplied && state.buffExpiresAt <= 0L) {
            state.buffsApplied = false;
            applyAttributeBonuses(runtime, 0.0D, 0L);
        }
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime,
            double scale,
            long durationMillis) {
        if (runtime == null) {
            return;
        }
        double clampedScale = Math.max(0.0D, scale);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthBonus * 100.0D * clampedScale,
                durationMillis);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBonus * 100.0D * clampedScale,
                durationMillis);
    }

    private void handleInvisBreak(StealthState state,
            Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            long now) {
        if (!state.invisApplied) {
            return;
        }
        state.buffExpiresAt = now + AugmentUtils.secondsToMillis(durationSeconds);
        state.invisApplied = false;
        if (ref != null && commandBuffer != null) {
            notifyPlayer(ref, commandBuffer,
                    String.format("Ambush invisibility broken. Buffs will last %.1fs.", durationSeconds));
            removeInvisibility(ref, commandBuffer);
        }
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getPlayerData() == null) {
            return context != null ? context.getDamage() : 0f;
        }
        UUID playerId = context.getPlayerData().getUuid();
        if (playerId == null) {
            return context.getDamage();
        }
        StealthState state = STATES.get(playerId);
        if (state != null && state.invisApplied) {
            handleInvisBreak(state, context.getAttackerRef(), context.getCommandBuffer(), System.currentTimeMillis());
        }
        return context.getDamage();
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getPlayerData() == null) {
            return context != null ? context.getIncomingDamage() : 0f;
        }
        UUID playerId = context.getPlayerData().getUuid();
        if (playerId == null) {
            return context.getIncomingDamage();
        }
        StealthState state = STATES.get(playerId);
        if (state != null && state.invisApplied) {
            handleInvisBreak(state, context.getDefenderRef(), context.getCommandBuffer(), System.currentTimeMillis());
        }
        return context.getIncomingDamage();
    }

    private boolean applyInvisibility(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            String effectId,
            float durationSeconds) {
        EffectControllerComponent controller = commandBuffer.getComponent(ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return false;
        }
        EntityEffect effect = resolveEntityEffect(effectId);
        if (effect == null) {
            return false;
        }
        return controller.addEffect(ref, effect, durationSeconds, OverlapBehavior.OVERWRITE, commandBuffer);
    }

    private void removeInvisibility(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        EffectControllerComponent controller = commandBuffer.getComponent(ref,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }
        // Use effect index when available; fall back to clearing all if lookup fails.
        EntityEffect effect = resolveEntityEffect(effectId);
        if (effect != null) {
            int idx = EntityEffect.getAssetMap().getIndex(effect.getId());
            if (idx != Integer.MIN_VALUE) {
                controller.removeEffect(ref, idx, commandBuffer);
                return;
            }
        }
        controller.clearEffects(ref, commandBuffer);
    }

    private void notifyPlayer(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            String text) {
        var playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
        AugmentUtils.sendAugmentMessage(playerRef, text);
    }

    private EntityEffect resolveEntityEffect(String configuredId) {
        if (configuredId == null || configuredId.isBlank()) {
            return null;
        }
        String trimmed = configuredId.trim();
        String lower = trimmed.toLowerCase();
        String upper = trimmed.toUpperCase();

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(trimmed);
        if (effect != null) {
            return effect;
        }
        effect = EntityEffect.getAssetMap().getAsset(lower);
        if (effect != null) {
            return effect;
        }
        effect = EntityEffect.getAssetMap().getAsset(upper);
        if (effect != null) {
            return effect;
        }

        if ("invisible".equalsIgnoreCase(trimmed)) {
            effect = EntityEffect.getAssetMap().getAsset("invisibility");
            if (effect != null) {
                return effect;
            }
            effect = EntityEffect.getAssetMap().getAsset("invisible");
            if (effect != null) {
                return effect;
            }
        }
        return null;
    }
}
