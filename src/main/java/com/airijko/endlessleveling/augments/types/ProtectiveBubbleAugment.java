package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class ProtectiveBubbleAugment extends YamlAugment implements AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "protective_bubble";
    private static final String[] IMMUNITY_EFFECT_IDS = new String[] { "Dodge_Invulnerability", "Immune" };

    private final long cooldownMillis;
    private final long immunityWindowMillis;

    public ProtectiveBubbleAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bubble = AugmentValueReader.getMap(passives, "immunity_bubble");
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bubble, "cooldown", 25.0D));
        this.immunityWindowMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(bubble, "immunity_window", 0.25D));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        float incoming = Math.max(0f, context.getIncomingDamage());
        if (incoming <= 0f) {
            return incoming;
        }

        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        AugmentState state = runtime.getState(ID);

        if (state.getStacks() > 0 && state.getExpiresAt() > now) {
            return 0f;
        }

        if (state.getStacks() > 0 && state.getExpiresAt() <= now) {
            state.setStacks(0);
            state.setExpiresAt(0L);
        }

        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return incoming;
        }

        applySelfImmunityEffect(context);

        state.setStacks(1);
        state.setExpiresAt(now + Math.max(1L, immunityWindowMillis));
        return 0f;
    }

    private void applySelfImmunityEffect(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getCommandBuffer() == null || context.getDefenderRef() == null
                || !context.getDefenderRef().isValid()) {
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> defenderRef = context.getDefenderRef();
        EffectControllerComponent effectController = commandBuffer.getComponent(defenderRef,
                EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        EntityEffect immunityEffect = resolveImmunityEffect();
        if (immunityEffect == null) {
            return;
        }

        float durationSeconds = Math.max(0.1F, immunityWindowMillis / 1000.0F);
        effectController.addEffect(defenderRef,
                immunityEffect,
                durationSeconds,
                OverlapBehavior.OVERWRITE,
                commandBuffer);
    }

    private static EntityEffect resolveImmunityEffect() {
        for (String candidate : IMMUNITY_EFFECT_IDS) {
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
}
