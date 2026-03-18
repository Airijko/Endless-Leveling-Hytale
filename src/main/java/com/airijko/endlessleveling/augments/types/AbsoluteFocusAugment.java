package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class AbsoluteFocusAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "absolute_focus";
    private static final double TRIGGER_VFX_Y_OFFSET = 1.0D;
    private static final String[] TRIGGER_VFX_IDS = new String[] {
        "Impact_Critical",
        "Impact_Dagger_Slash",
        "Impact_Sword_Basic",
        "Impact_Blade_01",
        "Explosion_Small"
    };
    private static final String[] TRIGGER_SFX_IDS = new String[] {
        "SFX_Daggers_T2_Slash_Impact",
        "SFX_Sword_T2_Impact"
    };
    private static final int TRIGGER_SFX_PLAY_COUNT = 3;

    private final long guaranteedCritCooldownMillis;
    private final double guaranteedCritChance;
    private final double conversionRatio;

    public AbsoluteFocusAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> guaranteedCrit = AugmentValueReader.getMap(passives, "guaranteed_crit");
        Map<String, Object> excessCrit = AugmentValueReader.getMap(passives, "excess_crit_conversion");

        this.guaranteedCritCooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(guaranteedCrit, "cooldown", 0.0D));
        this.guaranteedCritChance = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(guaranteedCrit, "crit_chance", 1.0D)));
        this.conversionRatio = Math.max(0.0D, AugmentValueReader.getDouble(excessCrit, "conversion_ratio", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        SkillManager skillManager = context.getSkillManager();
        if (skillManager == null || context.getPlayerData() == null) {
            return context.getDamage();
        }

        float originalDamage = context.getDamage();
        double totalBonusMultiplier = resolveExcessCritDamageBonus(skillManager, context);
        boolean activated = false;

        if (guaranteedCritChance > 0.0D
                && AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(),
                        guaranteedCritCooldownMillis)) {
            activated = true;
            if (!context.isCritical()) {
                double ferocity = skillManager.calculatePlayerFerocity(context.getPlayerData());
                totalBonusMultiplier += (ferocity / 100.0D) * guaranteedCritChance;
            }
        }
        float finalDamage = AugmentUtils.applyAdditiveBonusFromBase(
                originalDamage,
                context.getBaseDamage(),
                totalBonusMultiplier);
        if (activated) {
            playTriggerSound(context);
            playTriggerVfx(context);
            AugmentUtils.sendAugmentMessage(
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    String.format("%s activated! Damage %.2f -> %.2f (+%.2f%%)",
                            getName(),
                            originalDamage,
                            finalDamage,
                            Math.max(0.0D, totalBonusMultiplier * 100.0D)));
        }
        return finalDamage;
    }

    private double resolveExcessCritDamageBonus(SkillManager skillManager, AugmentHooks.HitContext context) {
        if (conversionRatio <= 0.0D) {
            return 0.0D;
        }
        SkillManager.PrecisionBreakdown breakdown = skillManager.getPrecisionBreakdown(context.getPlayerData());
        double rawCritPercent = breakdown.racePercent() + breakdown.skillPercent();
        double rawCritChance = Math.max(0.0D, rawCritPercent / 100.0D);
        double excessCritChance = Math.max(0.0D, rawCritChance - 1.0D);
        return excessCritChance * conversionRatio;
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

        for (String vfxId : TRIGGER_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(vfxId, targetPosition, targetRef.getStore());
                return;
            } catch (RuntimeException ignored) {
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

        for (String soundId : TRIGGER_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            for (int i = 0; i < TRIGGER_SFX_PLAY_COUNT; i++) {
                SoundUtil.playSoundEvent3d(null, soundIndex, targetPosition, targetRef.getStore());
            }
            return;
        }
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
