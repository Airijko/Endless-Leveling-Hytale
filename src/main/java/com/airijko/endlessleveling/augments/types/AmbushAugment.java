package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
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

public final class AmbushAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "ambush";

    private static final class StealthState {
        Object lastPos;
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
            if (state.invisApplied) {
                removeInvisibility(ref, commandBuffer);
            }
            state.invisApplied = false;
        }

        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        Object pos = transform != null ? transform.getPosition() : null;
        boolean moved = state.lastPos != null && pos != null && !state.lastPos.equals(pos);
        state.lastPos = pos;

        if (moved) {
            state.idleSeconds = 0.0D;
            if (state.invisApplied) {
                removeInvisibility(ref, commandBuffer);
                state.invisApplied = false;
            }
        } else {
            state.idleSeconds += context.getDeltaSeconds();
        }

        if (!state.buffsApplied && triggerAfterSeconds > 0.0D && state.idleSeconds >= triggerAfterSeconds) {
            state.buffExpiresAt = now + AugmentUtils.secondsToMillis(durationSeconds);
            state.buffsApplied = true;
            if (effectId != null && !effectId.isBlank() && durationSeconds > 0.0D) {
                if (applyInvisibility(ref, commandBuffer, effectId, (float) durationSeconds)) {
                    state.invisApplied = true;
                }
            }
        }

        if (state.buffsApplied && state.buffExpiresAt > now) {
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_str",
                    SkillAttributeType.STRENGTH,
                    strengthBonus * 100.0D,
                    state.buffExpiresAt - now);
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_sorc",
                    SkillAttributeType.SORCERY,
                    sorceryBonus * 100.0D,
                    state.buffExpiresAt - now);
        }
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
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
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
        EntityEffect effect = effectId != null && !effectId.isBlank() ? EntityEffect.getAssetMap().getAsset(effectId)
                : null;
        if (effect != null) {
            int idx = EntityEffect.getAssetMap().getIndex(effect.getId());
            if (idx != Integer.MIN_VALUE) {
                controller.removeEffect(ref, idx, commandBuffer);
                return;
            }
        }
        controller.clearEffects(ref, commandBuffer);
    }
}
