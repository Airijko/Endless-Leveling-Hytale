package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class CrippleAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "cripple";

    // Hytale rag data: Server/Entity/Effects/Status/Stun.json disables movement and
    // abilities.
    private static final String[] STUN_EFFECT_IDS = new String[] { "Stun", "stun", "Freeze", "freeze" };

    private final long cooldownMillis;
    private final long stunDurationMillis;
    private final double lifeForceFlatBonus;

    public CrippleAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> onHit = AugmentValueReader.getMap(passives, "on_hit");
        Map<String, Object> stun = AugmentValueReader.getMap(onHit, "stun");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> lifeForce = AugmentValueReader.getMap(buffs, "life_force");

        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(stun, "cooldown", 20.0D));
        this.stunDurationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(stun, "duration", 2.0D));
        this.lifeForceFlatBonus = Math.max(0.0D, AugmentValueReader.getDouble(lifeForce, "value", 100.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null || context.getCommandBuffer() == null
                || context.getTargetRef() == null || !context.getTargetRef().isValid()) {
            return context != null ? context.getDamage() : 0f;
        }

        if (stunDurationMillis <= 0L) {
            return context.getDamage();
        }

        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        EntityEffect stunEffect = resolveStunEffect();
        if (stunEffect == null) {
            return context.getDamage();
        }

        applyStunEffect(context.getTargetRef(), context.getCommandBuffer(), stunEffect);
        return context.getDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }

        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_life_force",
                SkillAttributeType.LIFE_FORCE,
                lifeForceFlatBonus,
                0L);
    }

    private void applyStunEffect(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityEffect stunEffect) {
        EffectControllerComponent effectController = commandBuffer.getComponent(targetRef,
                EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        float durationSeconds = Math.max(0.1F, stunDurationMillis / 1000.0F);
        effectController.addEffect(targetRef,
                stunEffect,
                durationSeconds,
                OverlapBehavior.OVERWRITE,
                commandBuffer);
    }

    private static EntityEffect resolveStunEffect() {
        for (String candidate : STUN_EFFECT_IDS) {
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
