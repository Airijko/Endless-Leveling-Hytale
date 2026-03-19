package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathBombAugment extends YamlAugment
        implements AugmentHooks.OnDamageTakenAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "death_bomb";

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
    private static final double MIN_VISUAL_BROADCAST_RADIUS = 24.0D;
    private static final double VISUAL_BROADCAST_RADIUS_MULTIPLIER = 4.0D;
    private static final long PENDING_BOMB_STALE_AFTER_MILLIS = 20000L;
    private static final Map<UUID, PendingBomb> PENDING_BOMBS = new ConcurrentHashMap<>();

    private final double healthRatio;
    private final double strengthRatio;
    private final double sorceryRatio;
    private final long delayMillis;
    private final long cooldownMillis;
    private final double radius;

    private static final class PendingBomb {
        final Ref<EntityStore> sourceRef;
        final Object worldStore;
        final Vector3d position;
        final long explodeAt;
        final double damage;
        final double radius;

        PendingBomb(Ref<EntityStore> sourceRef,
                Object worldStore,
                Vector3d position,
                long explodeAt,
                double damage,
                double radius) {
            this.sourceRef = sourceRef;
            this.worldStore = worldStore;
            this.position = position;
            this.explodeAt = explodeAt;
            this.damage = Math.max(0.0D, damage);
            this.radius = Math.max(0.0D, radius);
        }
    }

    public DeathBombAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bomb = AugmentValueReader.getMap(passives, "death_bomb");
        Map<String, Object> damage = AugmentValueReader.getMap(bomb, "damage_scaling");

        this.healthRatio = normalizeConfiguredRatio(
                AugmentValueReader.getDouble(damage, "max_health_ratio", 0.50D));
        this.strengthRatio = normalizeConfiguredRatio(
                AugmentValueReader.getDouble(damage, "strength_ratio", 0.10D));
        this.sorceryRatio = normalizeConfiguredRatio(
                AugmentValueReader.getDouble(damage, "sorcery_ratio", 0.10D));
        this.delayMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bomb, "delay", 3.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bomb, "cooldown", 0.0D));
        this.radius = Math.max(0.0D, AugmentValueReader.getDouble(bomb, "radius", 5.0D));
    }

    private static double normalizeConfiguredRatio(double configured) {
        double ratio = configured;
        if (ratio > 1.0D && ratio <= 100.0D) {
            ratio = ratio / 100.0D;
        }
        return Math.max(0.0D, ratio);
    }

    private static Vector3d snapshotPosition(Vector3d position) {
        if (position == null) {
            return null;
        }
        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new Vector3d(x, y, z);
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null || context.getPlayerData() == null
                || context.getPlayerData().getUuid() == null || context.getCommandBuffer() == null
                || context.getDefenderRef() == null || context.getStatMap() == null) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        float incoming = Math.max(0f, context.getIncomingDamage());
        if (incoming <= 0f) {
            return incoming;
        }

        EntityStatValue hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
            return incoming;
        }

        double projected = hp.get() - incoming;
        if (projected > 0.0D) {
            return incoming;
        }

        UUID uuid = context.getPlayerData().getUuid();
        long now = System.currentTimeMillis();
        PendingBomb existing = PENDING_BOMBS.get(uuid);
        if (existing != null) {
            long staleAt = existing.explodeAt + PENDING_BOMB_STALE_AFTER_MILLIS;
            if (now >= staleAt) {
                PENDING_BOMBS.remove(uuid, existing);
            } else {
                return incoming;
            }
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return incoming;
        }

        TransformComponent transform = context.getCommandBuffer().getComponent(context.getDefenderRef(),
                TransformComponent.getComponentType());
        Vector3d position = transform != null ? snapshotPosition(transform.getPosition()) : null;
        if (position == null) {
            return incoming;
        }

        double strength = context.getSkillManager() != null
                ? Math.max(0.0D, context.getSkillManager().calculatePlayerStrength(context.getPlayerData()))
                : 0.0D;
        double sorcery = context.getSkillManager() != null
                ? Math.max(0.0D, context.getSkillManager().calculatePlayerSorcery(context.getPlayerData()))
                : 0.0D;
        double scaledDamage = (hp.getMax() * healthRatio)
                + (strength * strengthRatio)
                + (sorcery * sorceryRatio);
        if (scaledDamage <= 0.0D) {
            return incoming;
        }

        PENDING_BOMBS.put(uuid,
                new PendingBomb(context.getDefenderRef(),
                context.getDefenderRef().getStore(),
                        position,
                        now + Math.max(1L, delayMillis),
                        scaledDamage,
                        radius));

        return incoming;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getCommandBuffer() == null) {
            return;
        }

        tickPendingBombs(context.getCommandBuffer(), context.getPlayerRef());
    }

    public static void tickPendingBombs(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> fallbackVisualRef) {
        processPendingBombs(commandBuffer, fallbackVisualRef);
    }

    private static void processPendingBombs(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> fallbackVisualRef) {
        if (commandBuffer == null || PENDING_BOMBS.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingBomb> entry : PENDING_BOMBS.entrySet()) {
            UUID ownerUuid = entry.getKey();
            PendingBomb pending = entry.getValue();
            if (pending == null) {
                PENDING_BOMBS.remove(ownerUuid);
                continue;
            }

            long staleAt = pending.explodeAt + PENDING_BOMB_STALE_AFTER_MILLIS;
            if (now >= staleAt) {
                PENDING_BOMBS.remove(ownerUuid, pending);
                continue;
            }

            if (now < pending.explodeAt) {
                continue;
            }

            if (pending.worldStore == null || fallbackVisualRef == null || fallbackVisualRef.getStore() == null
                    || fallbackVisualRef.getStore() != pending.worldStore) {
                continue;
            }

            if (!PENDING_BOMBS.remove(ownerUuid, pending)) {
                continue;
            }

            explode(commandBuffer, pending, fallbackVisualRef);
        }
    }

    private static void explode(CommandBuffer<EntityStore> commandBuffer,
            PendingBomb pending,
            Ref<EntityStore> fallbackVisualRef) {
        if (pending == null || commandBuffer == null || pending.position == null
                || pending.damage <= 0.0D || pending.radius <= 0.0D) {
            return;
        }
        if (pending.worldStore == null || fallbackVisualRef == null || fallbackVisualRef.getStore() == null
                || fallbackVisualRef.getStore() != pending.worldStore) {
            return;
        }

        Ref<EntityStore> sourceRef = EntityRefUtil.isUsable(pending.sourceRef) ? pending.sourceRef : null;
        float configuredDamage = (float) Math.max(0.0D, pending.damage);
        if (configuredDamage <= 0.0f) {
            return;
        }

        spawnExplosionParticles(commandBuffer, sourceRef, fallbackVisualRef, pending);
        HashSet<Integer> visitedEntityIds = new HashSet<>();

        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(
                pending.position,
                pending.radius,
                commandBuffer)) {
            if (targetRef == null || !targetRef.isValid()) {
                continue;
            }
            if (!visitedEntityIds.add(targetRef.getIndex())) {
                continue;
            }
            if (sourceRef != null && targetRef.equals(sourceRef)) {
                continue;
            }

            if (!EntityRefUtil.isUsable(targetRef)) {
                continue;
            }

            applyBombDamageToTarget(sourceRef, targetRef, commandBuffer, configuredDamage);
        }
    }

    private static void applyBombDamageToTarget(Ref<EntityStore> sourceRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            float configuredDamage) {
        if (targetRef == null || commandBuffer == null || configuredDamage <= 0.0f) {
            return;
        }

        EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                EntityStatMap.getComponentType());
        EntityStatValue hpBeforeValue = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
        float hpBefore = hpBeforeValue == null ? 0.0f : hpBeforeValue.get();

        DamageSystems.executeDamage(targetRef,
                commandBuffer,
                PlayerCombatSystem.createAugmentProcDamage(sourceRef, configuredDamage));

        if (targetStats == null || hpBeforeValue == null || hpBefore <= 0.0f) {
            return;
        }

        EntityStatValue hpAfterValue = targetStats.get(DefaultEntityStatTypes.getHealth());
        float hpAfter = hpAfterValue == null ? hpBefore : hpAfterValue.get();
        if (hpAfter <= 0.0f) {
            return;
        }

        float alreadyApplied = Math.max(0.0f, hpBefore - hpAfter);
        float remaining = configuredDamage - alreadyApplied;
        if (remaining <= 0.001f) {
            return;
        }

        if (remaining >= hpAfter) {
            markBombKill(sourceRef, targetRef, commandBuffer, targetStats);
            return;
        }

        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(0.0f, hpAfter - remaining));
    }

    private static void markBombKill(Ref<EntityStore> sourceRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats) {
        if (targetRef == null || commandBuffer == null || targetStats == null) {
            return;
        }

        if (EntityRefUtil.tryGetComponent(commandBuffer, targetRef, DeathComponent.getComponentType()) == null) {
            Damage damage;
            if (EntityRefUtil.isUsable(sourceRef)) {
                try {
                    damage = new Damage(new Damage.EntitySource(sourceRef), DamageCause.PHYSICAL, Float.MAX_VALUE);
                } catch (IllegalStateException ignored) {
                    damage = new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE);
                }
            } else {
                damage = new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE);
            }
            DeathComponent.tryAddComponent(commandBuffer, targetRef, damage);
        }

        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
    }

    private static void spawnExplosionParticles(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> sourceRef,
            Ref<EntityStore> fallbackVisualRef,
            PendingBomb pending) {
        if (commandBuffer == null || pending == null || pending.position == null) {
            return;
        }
        if (pending.worldStore == null || fallbackVisualRef == null || fallbackVisualRef.getStore() == null
                || fallbackVisualRef.getStore() != pending.worldStore) {
            return;
        }

        Ref<EntityStore> visualSource = EntityRefUtil.isUsable(fallbackVisualRef)
                ? fallbackVisualRef
                : (EntityRefUtil.isUsable(sourceRef) ? sourceRef : null);
        if (visualSource == null || visualSource.getStore() == null) {
            return;
        }

        playExplosionSound(visualSource, pending.position);

        int bigBursts = pending.radius >= 8.0D ? 3 : (pending.radius >= 5.0D ? 2 : 1);
        boolean spawnedAny = false;
        for (int i = 0; i < bigBursts; i++) {
            if (trySpawnExplosionParticle(EXPLOSION_PARTICLE_BIG, pending.position, visualSource)) {
                spawnedAny = true;
            }
        }

        if (!spawnedAny) {
            for (String particleSystemId : getPreferredExplosionParticleOrder(pending.radius)) {
                if (EXPLOSION_PARTICLE_BIG.equals(particleSystemId)) {
                    continue;
                }
                if (trySpawnExplosionParticle(particleSystemId, pending.position, visualSource)) {
                    return;
                }
            }
            return;
        }

        if (pending.radius >= 4.0D) {
            trySpawnExplosionParticle(EXPLOSION_PARTICLE_MEDIUM, pending.position, visualSource);
        }
    }

    private static boolean trySpawnExplosionParticle(String particleSystemId,
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

    private static void playExplosionSound(Ref<EntityStore> sourceRef, Vector3d position) {
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

    private static String[] getPreferredExplosionParticleOrder(double explosionRadius) {
        if (explosionRadius >= 7.0D) {
            return new String[] { EXPLOSION_PARTICLE_BIG, EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_SMALL };
        }
        if (explosionRadius >= 4.0D) {
            return new String[] { EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_BIG, EXPLOSION_PARTICLE_SMALL };
        }
        return new String[] { EXPLOSION_PARTICLE_SMALL, EXPLOSION_PARTICLE_MEDIUM, EXPLOSION_PARTICLE_BIG };
    }
}
