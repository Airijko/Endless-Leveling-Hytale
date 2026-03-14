package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
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
    private static final double MIN_VISUAL_BROADCAST_RADIUS = 24.0D;
    private static final double VISUAL_BROADCAST_RADIUS_MULTIPLIER = 4.0D;
    private static final Map<UUID, PendingBomb> PENDING_BOMBS = new ConcurrentHashMap<>();

    private final double healthRatio;
    private final double strengthRatio;
    private final double sorceryRatio;
    private final long delayMillis;
    private final long cooldownMillis;
    private final double radius;

    private static final class PendingBomb {
        final Vector3d position;
        final long explodeAt;
        final double damage;
        final double radius;

        PendingBomb(Vector3d position,
                long explodeAt,
                double damage,
                double radius) {
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

        this.healthRatio = Math.max(0.0D,
                AugmentValueReader.getDouble(damage, "max_health_ratio", 0.50D));
        this.strengthRatio = Math.max(0.0D,
                AugmentValueReader.getDouble(damage, "strength_ratio", 0.10D));
        this.sorceryRatio = Math.max(0.0D,
                AugmentValueReader.getDouble(damage, "sorcery_ratio", 0.10D));
        this.delayMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bomb, "delay", 3.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bomb, "cooldown", 0.0D));
        this.radius = Math.max(0.0D, AugmentValueReader.getDouble(bomb, "radius", 5.0D));
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
        if (PENDING_BOMBS.containsKey(uuid)) {
            return incoming;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return incoming;
        }

        TransformComponent transform = context.getCommandBuffer().getComponent(context.getDefenderRef(),
                TransformComponent.getComponentType());
        Vector3d position = transform != null ? transform.getPosition() : null;
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

        long now = System.currentTimeMillis();
        PENDING_BOMBS.put(uuid,
                new PendingBomb(position, now + Math.max(1L, delayMillis), scaledDamage, radius));

        return incoming;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getPlayerData() == null || context.getPlayerData().getUuid() == null
                || context.getCommandBuffer() == null || context.getPlayerRef() == null) {
            return;
        }

        UUID uuid = context.getPlayerData().getUuid();
        PendingBomb pending = PENDING_BOMBS.get(uuid);
        if (pending == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < pending.explodeAt) {
            return;
        }

        explode(context.getCommandBuffer(), context.getPlayerRef(), pending);
        PENDING_BOMBS.remove(uuid);
    }

    private void explode(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> sourceRef, PendingBomb pending) {
        if (pending == null || commandBuffer == null || sourceRef == null || pending.position == null
                || pending.damage <= 0.0D || pending.radius <= 0.0D) {
            return;
        }

        spawnExplosionParticles(commandBuffer, sourceRef, pending);
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
            if (targetRef.equals(sourceRef)) {
                continue;
            }

            EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
            EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
                continue;
            }

            double damageToApply = applyProtectiveBubbleGuard(commandBuffer,
                    targetRef,
                    sourceRef,
                    targetStats,
                    pending.damage);
            if (damageToApply <= 0.0D) {
                continue;
            }

            if (damageToApply >= targetHp.get()) {
                markBombKill(sourceRef, targetRef, commandBuffer, targetStats);
                continue;
            }

            float updatedHealth = (float) Math.max(0.0D, targetHp.get() - damageToApply);
            targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), updatedHealth);
        }
    }

    private double applyProtectiveBubbleGuard(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> targetRef,
            Ref<EntityStore> sourceRef,
            EntityStatMap targetStats,
            double incomingDamage) {
        if (incomingDamage <= 0.0D || commandBuffer == null || targetRef == null || targetStats == null) {
            return Math.max(0.0D, incomingDamage);
        }

        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return incomingDamage;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return incomingDamage;
        }

        AugmentExecutor augmentExecutor = plugin.getAugmentExecutor();
        if (augmentExecutor == null || plugin.getPlayerDataManager() == null) {
            return incomingDamage;
        }

        PlayerData defenderData = plugin.getPlayerDataManager().get(targetPlayer.getUuid());
        if (defenderData == null) {
            return incomingDamage;
        }

        float guarded = augmentExecutor.applySpecificOnDamageTaken(defenderData,
                targetRef,
                sourceRef,
                commandBuffer,
                targetStats,
                (float) incomingDamage,
                ProtectiveBubbleAugment.ID);
        return Math.max(0.0D, guarded);
    }

    private void markBombKill(Ref<EntityStore> sourceRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats) {
        if (targetRef == null || commandBuffer == null || targetStats == null) {
            return;
        }

        if (commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) == null) {
            Damage damage = sourceRef != null
                    ? new Damage(new Damage.EntitySource(sourceRef), DamageCause.PHYSICAL, Float.MAX_VALUE)
                    : new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE);
            DeathComponent.tryAddComponent(commandBuffer, targetRef, damage);
        }

        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
    }

    private void spawnExplosionParticles(CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> sourceRef,
            PendingBomb pending) {
        if (commandBuffer == null || pending == null || pending.position == null) {
            return;
        }

        double broadcastRadius = Math.max(MIN_VISUAL_BROADCAST_RADIUS,
                pending.radius * VISUAL_BROADCAST_RADIUS_MULTIPLIER);
        List<Ref<EntityStore>> nearbyPlayers = collectNearbyPlayers(commandBuffer, pending.position, broadcastRadius);

        if (nearbyPlayers.isEmpty() && sourceRef != null && sourceRef.isValid()) {
            PlayerRef sourcePlayerRef = commandBuffer.getComponent(sourceRef, PlayerRef.getComponentType());
            if (sourcePlayerRef != null && sourcePlayerRef.isValid()) {
                nearbyPlayers.add(sourceRef);
            }
        }

        if (nearbyPlayers.isEmpty()) {
            return;
        }

        for (String particleSystemId : getPreferredExplosionParticleOrder(pending.radius)) {
            try {
                ParticleUtil.spawnParticleEffect(
                        particleSystemId,
                        pending.position,
                        sourceRef,
                        nearbyPlayers,
                        commandBuffer);
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private List<Ref<EntityStore>> collectNearbyPlayers(CommandBuffer<EntityStore> commandBuffer,
            Vector3d position,
            double radius) {
        List<Ref<EntityStore>> nearbyPlayers = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();

        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(position, radius, commandBuffer)) {
            if (targetRef == null || !targetRef.isValid() || !seen.add(targetRef.getIndex())) {
                continue;
            }

            PlayerRef playerRef = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            nearbyPlayers.add(targetRef);
        }

        return nearbyPlayers;
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
