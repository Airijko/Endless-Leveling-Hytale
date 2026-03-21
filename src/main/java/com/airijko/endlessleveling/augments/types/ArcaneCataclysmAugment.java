package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ArcaneCataclysmAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "arcane_cataclysm";
    private static final long INTERNAL_STACKING_DELAY_MILLIS = 400L;
    private static final String STACK_DELAY_STATE_ID = ID + "_stack_delay";
    private static final String HIT_COUNTER_WINDOW_STATE_ID = ID + "_hit_counter_window";
    private static final String EXPLOSION_PARTICLE_SMALL = "Explosion_Small";
    private static final String EXPLOSION_PARTICLE_MEDIUM = "Explosion_Medium";
    private static final String EXPLOSION_PARTICLE_BIG = "Explosion_Big";
    private static final String[] EXPLOSION_SFX_PRIMARY_IDS = new String[] {
        "SFX_Goblin_Lobber_Bomb_Death",
    };
    private static final String[] EXPLOSION_SFX_LAYER_IDS = new String[] {
        "SFX_Staff_Flame_Fireball_Impact",
        "SFX_Goblin_Lobber_Bomb_Death"
    };

    private final double flatDamage;
    private final double sorceryScaling;
    private final double radius;
    private final int hitsRequired;
    private final long hitCounterDurationMillis;

    public ArcaneCataclysmAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> explosion = AugmentValueReader.getMap(passives, "explosion_damage");
        Map<String, Object> hitCounter = AugmentValueReader.getMap(passives, "hit_counter");

        this.flatDamage = Math.max(0.0D, AugmentValueReader.getDouble(explosion, "flat_damage", 0.0D));
        this.sorceryScaling = Math.max(0.0D, AugmentValueReader.getDouble(explosion, "sorcery_scaling", 0.0D));
        this.radius = Math.max(0.0D, AugmentValueReader.getDouble(explosion, "radius", 3.0D));
        this.hitsRequired = Math.max(1, AugmentValueReader.getInt(hitCounter, "hits_required", 5));
        this.hitCounterDurationMillis = AugmentValueReader.getDouble(hitCounter, "duration", 0.0D) <= 0.0D
                ? 0L
                : (long) (AugmentValueReader.getDouble(hitCounter, "duration", 0.0D) * 1000.0D);
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
        var counterState = runtime.getState(ID);
        var hitCounterWindowState = runtime.getState(HIT_COUNTER_WINDOW_STATE_ID);

        if (hitCounterDurationMillis > 0L
                && counterState.getStacks() > 0
                && hitCounterWindowState.getExpiresAt() > 0L
                && now >= hitCounterWindowState.getExpiresAt()) {
            counterState.setStacks(0);
            hitCounterWindowState.setExpiresAt(0L);
        }

        if (!isStackDelayReady(runtime, now)) {
            return context.getDamage();
        }

        int hits = counterState.getStacks() + 1;
        if (hits < hitsRequired) {
            counterState.setStacks(hits);
            if (hitCounterDurationMillis > 0L) {
                hitCounterWindowState.setExpiresAt(now + hitCounterDurationMillis);
            }
            markStackDelay(runtime, now);
            return context.getDamage();
        }

        counterState.setStacks(0);
        hitCounterWindowState.setExpiresAt(0L);
        markStackDelay(runtime, now);

        SkillManager skillManager = context.getSkillManager();
        double sorcery = (skillManager == null || context.getPlayerData() == null)
                ? 0.0D
                : Math.max(0.0D, skillManager.calculatePlayerSorcery(context.getPlayerData()));
        double explosionDamage = flatDamage + (sorcery * sorceryScaling);
        if (explosionDamage <= 0.0D) {
            return context.getDamage();
        }

        if (context.getCommandBuffer() == null || context.getTargetRef() == null
                || !EntityRefUtil.isUsable(context.getTargetRef())) {
            return context.getDamage();
        }

        Vector3d explosionPosition = resolveTargetEffectPosition(context.getCommandBuffer(), context.getTargetRef());
        if (explosionPosition == null) {
            return context.getDamage();
        }

        spawnExplosionParticles(context.getAttackerRef(), explosionPosition);
        applyExplosionDamage(context.getAttackerRef(), context.getCommandBuffer(), explosionPosition, (float) explosionDamage);
        return context.getDamage();
    }

    private void applyExplosionDamage(Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            Vector3d explosionPosition,
            float explosionDamage) {
        if (commandBuffer == null || explosionPosition == null || explosionDamage <= 0.0f || radius <= 0.0D) {
            return;
        }

        Set<Integer> visitedEntityIds = new HashSet<>();
        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(explosionPosition, radius, commandBuffer)) {
            if (targetRef == null || !targetRef.isValid()) {
                continue;
            }
            if (!visitedEntityIds.add(targetRef.getIndex())) {
                continue;
            }
            if (attackerRef != null && targetRef.equals(attackerRef)) {
                continue;
            }
            if (!EntityRefUtil.isUsable(targetRef)) {
                continue;
            }

            Damage proc = PlayerCombatSystem.createAugmentProcDamage(attackerRef, explosionDamage);
            DamageSystems.executeDamage(targetRef, commandBuffer, proc);
        }
    }

    private boolean isStackDelayReady(AugmentRuntimeState runtime, long now) {
        var delayState = runtime.getState(STACK_DELAY_STATE_ID);
        return delayState.getLastProc() <= 0L || now - delayState.getLastProc() >= INTERNAL_STACKING_DELAY_MILLIS;
    }

    private void markStackDelay(AugmentRuntimeState runtime, long now) {
        runtime.getState(STACK_DELAY_STATE_ID).setLastProc(now);
    }

    private Vector3d resolveTargetEffectPosition(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> targetRef) {
        if (commandBuffer == null || !EntityRefUtil.isUsable(targetRef)) {
            return null;
        }

        TransformComponent targetTransform = EntityRefUtil.tryGetComponent(
                commandBuffer,
                targetRef,
                TransformComponent.getComponentType());
        if (targetTransform == null || targetTransform.getPosition() == null) {
            return null;
        }

        Vector3d baseTargetPosition = targetTransform.getPosition();
        return new Vector3d(
                baseTargetPosition.getX(),
                baseTargetPosition.getY(),
                baseTargetPosition.getZ());
    }

    private void spawnExplosionParticles(Ref<EntityStore> attackerRef, Vector3d position) {
        if (attackerRef == null || !attackerRef.isValid() || position == null) {
            return;
        }

        playExplosionSound(attackerRef, position);

        int bigBursts = radius >= 8.0D ? 3 : (radius >= 5.0D ? 2 : 1);
        boolean spawnedAny = false;
        for (int i = 0; i < bigBursts; i++) {
            if (trySpawnExplosionParticle(EXPLOSION_PARTICLE_BIG, position, attackerRef)) {
                spawnedAny = true;
            }
        }

        if (!spawnedAny) {
            for (String particleSystemId : getPreferredExplosionParticleOrder(radius)) {
                if (EXPLOSION_PARTICLE_BIG.equals(particleSystemId)) {
                    continue;
                }
                if (trySpawnExplosionParticle(particleSystemId, position, attackerRef)) {
                    return;
                }
            }
            return;
        }

        if (radius >= 4.0D) {
            trySpawnExplosionParticle(EXPLOSION_PARTICLE_MEDIUM, position, attackerRef);
        }
    }

    private boolean trySpawnExplosionParticle(String particleSystemId,
            Vector3d position,
            Ref<EntityStore> visualSource) {
        if (particleSystemId == null || position == null || visualSource == null || visualSource.getStore() == null) {
            return false;
        }
        try {
            ParticleUtil.spawnParticleEffect(particleSystemId, position, visualSource.getStore());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void playExplosionSound(Ref<EntityStore> sourceRef, Vector3d position) {
        if (sourceRef == null || !sourceRef.isValid() || position == null) {
            return;
        }

        int primaryIndex = resolveFirstAvailableSoundIndex(EXPLOSION_SFX_PRIMARY_IDS, 0);
        if (primaryIndex != 0) {
            SoundUtil.playSoundEvent3d(null, primaryIndex, position, sourceRef.getStore());
        }

        int layerIndex = resolveFirstAvailableSoundIndex(EXPLOSION_SFX_LAYER_IDS, primaryIndex);
        if (layerIndex != 0) {
            SoundUtil.playSoundEvent3d(null, layerIndex, position, sourceRef.getStore());
        }
    }

    private int resolveFirstAvailableSoundIndex(String[] ids, int excludedIndex) {
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

    private int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private String[] getPreferredExplosionParticleOrder(double explosionRadius) {
        if (explosionRadius >= 7.0D) {
            return new String[] { EXPLOSION_PARTICLE_BIG, EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_SMALL };
        }
        if (explosionRadius >= 4.0D) {
            return new String[] { EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_BIG, EXPLOSION_PARTICLE_SMALL };
        }
        return new String[] { EXPLOSION_PARTICLE_SMALL, EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_BIG };
    }
}
