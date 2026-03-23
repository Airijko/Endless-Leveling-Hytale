package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDamageSafety;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class PhantomHitsAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "phantom_hits";
    public static final long INTERNAL_COOLDOWN_MILLIS = 400L;
    private static final double TRIGGER_VFX_Y_OFFSET = 1.0D;
    private static final double TRIGGER_VFX_OVERHEAD_OFFSET = 0.65D;
    private static final double TRIGGER_VFX_RING_RADIUS = 0.45D;
    private static final int TRIGGER_VFX_BURST_COUNT = 2;
    private static final String[] TRIGGER_VFX_IDS = new String[] {
        "Impact_Dagger_Slash",
        "Impact_Blade_01",
    };
    private static final double[][] TRIGGER_VFX_RING_OFFSETS = new double[][] {
        { 0.0D, 0.0D },
        { TRIGGER_VFX_RING_RADIUS, 0.0D },
        { -TRIGGER_VFX_RING_RADIUS, 0.0D },
        { 0.0D, TRIGGER_VFX_RING_RADIUS },
        { 0.0D, -TRIGGER_VFX_RING_RADIUS }
    };
    private static final String[] TRIGGER_SFX_IDS = new String[] {
        "SFX_Daggers_T2_Slash_Impact",
        "SFX_Sword_T2_Impact"
    };

    private final double flatDamage;
    private final double strengthScaling;
    private final double sorceryScaling;
    private final boolean canCrit;

    public PhantomHitsAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> phantomDamage = AugmentValueReader.getMap(passives, "phantom_damage");

        this.flatDamage = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "flat_damage", 0.0D));
        this.strengthScaling = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "strength_scaling", 0.0D));
        this.sorceryScaling = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "sorcery_scaling", 0.0D));
        this.canCrit = AugmentValueReader.getBoolean(phantomDamage, "can_crit", false);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        AugmentState state = null;
        if (context.getRuntimeState() != null) {
            state = context.getRuntimeState().getState(ID);
            long now = System.currentTimeMillis();
            if (state.getLastProc() > 0L && now - state.getLastProc() < INTERNAL_COOLDOWN_MILLIS) {
                return context.getDamage();
            }
        }

        double strength = AugmentUtils.resolveStrength(context);
        double sorcery = AugmentUtils.resolveSorcery(context);
        double phantomDamage = flatDamage + (strength * strengthScaling) + (sorcery * sorceryScaling);

        if (phantomDamage <= 0.0D) {
            return context.getDamage();
        }

        if (canCrit) {
            double critChance = Math.max(0.0D, Math.min(1.0D, AugmentUtils.resolvePrecision(context)));
            if (ThreadLocalRandom.current().nextDouble() <= critChance) {
                double ferocity = AugmentUtils.resolveFerocity(context);
                phantomDamage *= 1.0D + (ferocity / 100.0D);
            }
        }

        if (context.getCommandBuffer() != null && context.getTargetRef() != null
                && EntityRefUtil.isUsable(context.getTargetRef())) {
            if (state != null) {
                state.setLastProc(System.currentTimeMillis());
            }
            playTriggerVfx(context);
            playTriggerSound(context);
            Damage phantomProc = PlayerCombatSystem.createAugmentProcDamage(
                    context.getAttackerRef(),
                    (float) phantomDamage);
                AugmentDamageSafety.tryExecuteDamage(context.getTargetRef(), context.getCommandBuffer(), phantomProc, ID);
            return context.getDamage();
        }

        return context.getDamage() + (float) phantomDamage;
    }

    private void playTriggerVfx(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetRef();
        Vector3d position = resolveTargetEffectPosition(context, targetRef);
        if (position == null || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        Vector3d overheadPosition = new Vector3d(
                position.getX(),
                position.getY() + TRIGGER_VFX_OVERHEAD_OFFSET,
                position.getZ());

        for (int burst = 0; burst < TRIGGER_VFX_BURST_COUNT; burst++) {
            spawnVfxSet(targetRef, position);
            spawnVfxSet(targetRef, overheadPosition);
            for (double[] offset : TRIGGER_VFX_RING_OFFSETS) {
                if (offset == null || offset.length < 2) {
                    continue;
                }
                Vector3d ringPosition = new Vector3d(
                        position.getX() + offset[0],
                        position.getY(),
                        position.getZ() + offset[1]);
                spawnVfxSet(targetRef, ringPosition);
            }
        }
    }

    private void spawnVfxSet(Ref<EntityStore> targetRef, Vector3d position) {
        if (!EntityRefUtil.isUsable(targetRef) || position == null) {
            return;
        }

        for (String vfxId : TRIGGER_VFX_IDS) {
            if (vfxId == null || vfxId.isBlank()) {
                continue;
            }
            try {
                ParticleUtil.spawnParticleEffect(vfxId, position, targetRef.getStore());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void playTriggerSound(AugmentHooks.HitContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetRef();
        Vector3d position = resolveTargetEffectPosition(context, targetRef);
        if (position == null || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        for (String soundId : TRIGGER_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            SoundUtil.playSoundEvent3d(null, soundIndex, position, targetRef.getStore());
            return;
        }
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private Vector3d resolveTargetEffectPosition(AugmentHooks.HitContext context, Ref<EntityStore> targetRef) {
        if (context == null || context.getCommandBuffer() == null || !EntityRefUtil.isUsable(targetRef)) {
            return null;
        }

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
