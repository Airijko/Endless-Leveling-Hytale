package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class FirstStrikeAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "first_strike";
    private static final double TRIGGER_VFX_Y_OFFSET = 1.0D;
    private static final double TRIGGER_VFX_OVERHEAD_OFFSET = 1.15D;
    private static final String[] TRIGGER_VFX_CORE_IDS = new String[] {
        "Explosion_Big",
        "Explosion_Medium"
    };
    private static final String[] TRIGGER_VFX_ACCENT_IDS = new String[] {
        "Impact_Critical",
        "Impact_Sword_Basic"
    };
    private static final int TRIGGER_VFX_CORE_BURST_COUNT = 1;
    private static final int TRIGGER_VFX_ACCENT_BURST_COUNT = 1;
    private static final String[] TRIGGER_SFX_PRIMARY_IDS = new String[] {
        "SFX_Sword_T2_Signature_Part_2",
        "SFX_Sword_T2_Impact"
    };
    private static final String[] TRIGGER_SFX_LAYER_IDS = new String[] {
        "SFX_Daggers_T2_Slash_Impact"
    };
    private static final int TRIGGER_SFX_BASE_PLAY_COUNT = 2;
    private static final int TRIGGER_SFX_VOLUME_MULTIPLIER = 3;
    private static final int TRIGGER_SFX_PLAY_COUNT = TRIGGER_SFX_BASE_PLAY_COUNT * TRIGGER_SFX_VOLUME_MULTIPLIER;

    private final double baseMultiplier;
    private final long cooldownMillis;
    private final Map<String, Object> classValues;

    public FirstStrikeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");
        this.baseMultiplier = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonus, "value", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bonus, "cooldown", 0.0D));
        this.classValues = AugmentValueReader.getMap(bonus, "class_values");
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        double classMultiplier = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentUtils.resolveClassValue(classValues,
                        context.getPlayerData().getPrimaryClassId()));
        double multiplier = classMultiplier > 0 ? classMultiplier : baseMultiplier;
        if (multiplier <= 0.0D) {
            return context.getDamage();
        }
        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        playTriggerSound(context);
        playTriggerVfx(context);

        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.first_strike.triggered",
                            "{0} triggered! +{1}% damage.",
                            getName(), multiplier * 100.0D));
        }
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), multiplier);
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || cooldownMillis <= 0L || context.getIncomingDamage() <= 0f) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        if (!AugmentUtils.isCooldownReady(context.getRuntimeState(), ID, cooldownMillis)) {
            return context.getIncomingDamage();
        }

        if (AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        Lang.tr(playerRef.getUuid(),
                                "augments.first_strike.lost",
                                "{0} lost: you were hit before striking. Cooldown started.",
                                getName()));
            }
        }

        return context.getIncomingDamage();
    }

    private void playTriggerVfx(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetRef();
        if (!EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        Vector3d targetPosition = resolveTargetEffectPosition(context, targetRef);
        if (targetPosition == null) {
            return;
        }

        Vector3d overheadPosition = new Vector3d(
                targetPosition.getX(),
                targetPosition.getY() + TRIGGER_VFX_OVERHEAD_OFFSET,
                targetPosition.getZ());

        spawnVfxBursts(targetRef, targetPosition, TRIGGER_VFX_CORE_IDS, TRIGGER_VFX_CORE_BURST_COUNT);
        spawnVfxBursts(targetRef, overheadPosition, TRIGGER_VFX_ACCENT_IDS, TRIGGER_VFX_ACCENT_BURST_COUNT);
    }

    private void spawnVfxBursts(Ref<EntityStore> targetRef,
            Vector3d position,
            String[] vfxIds,
            int burstCount) {
        if (!EntityRefUtil.isUsable(targetRef) || position == null || vfxIds == null || vfxIds.length == 0) {
            return;
        }

        int safeBurstCount = Math.max(1, burstCount);
        for (int burst = 0; burst < safeBurstCount; burst++) {
            for (String vfxId : vfxIds) {
                if (vfxId == null || vfxId.isBlank()) {
                    continue;
                }
                try {
                    ParticleUtil.spawnParticleEffect(vfxId, position, targetRef.getStore());
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void playTriggerSound(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetRef();
        if (!EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        Vector3d targetPosition = resolveTargetEffectPosition(context, targetRef);
        if (targetPosition == null) {
            return;
        }

        int primaryIndex = resolveFirstAvailableSoundIndex(TRIGGER_SFX_PRIMARY_IDS, 0);
        if (primaryIndex == 0) {
            return;
        }

        int layerIndex = resolveFirstAvailableSoundIndex(TRIGGER_SFX_LAYER_IDS, primaryIndex);
        for (int i = 0; i < TRIGGER_SFX_PLAY_COUNT; i++) {
            SoundUtil.playSoundEvent3d(null, primaryIndex, targetPosition, targetRef.getStore());
            if (layerIndex != 0) {
                SoundUtil.playSoundEvent3d(null, layerIndex, targetPosition, targetRef.getStore());
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

    private Vector3d resolveTargetEffectPosition(AugmentHooks.HitContext context, Ref<EntityStore> targetRef) {
        TransformComponent targetTransform = EntityRefUtil.tryGetComponent(
                context.getCommandBuffer(),
                targetRef,
                TransformComponent.getComponentType());
        if (targetTransform == null || targetTransform.getPosition() == null) {
            return null;
        }

        Vector3d baseTargetPosition = targetTransform.getPosition();
        return new Vector3d(
                baseTargetPosition.getX(),
                baseTargetPosition.getY() + TRIGGER_VFX_Y_OFFSET,
                baseTargetPosition.getZ());
    }
}
