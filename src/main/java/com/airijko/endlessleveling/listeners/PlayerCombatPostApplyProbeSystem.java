package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostic probe that runs after ApplyDamage to inspect final HP/death state.
 */
public class PlayerCombatPostApplyProbeSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final Map<Long, Integer> lethalWithoutDeathStreak = new ConcurrentHashMap<>();
    private final Map<Long, Float> lastObservedHpByTarget = new ConcurrentHashMap<>();
    private final MobLevelingManager mobLevelingManager;

    public PlayerCombatPostApplyProbeSystem(MobLevelingManager mobLevelingManager) {
        this.mobLevelingManager = mobLevelingManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null) {
            return;
        }

        PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayer == null || !attackerPlayer.isValid()) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        boolean targetIsPlayer = targetPlayer != null && targetPlayer.isValid();
        if (targetIsPlayer) {
            return;
        }

        float hp = Float.NaN;
        float max = Float.NaN;
        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (targetStats != null) {
            EntityStatValue targetHp = targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp != null) {
                hp = targetHp.get();
                max = targetHp.getMax();
            }
        }

        boolean dead = commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) != null;
        int targetId = targetRef.getIndex();
        long targetKey = resolveTrackingKey(targetRef, commandBuffer);
        int mobLevel = -1;
        if (mobLevelingManager != null) {
            Integer assignedLevel = mobLevelingManager.getEntityLevelOverride(targetRef.getStore(), targetId);
            if (assignedLevel != null && assignedLevel > 0) {
                mobLevel = assignedLevel;
            }
        }

        if (dead) {
            lethalWithoutDeathStreak.remove(targetKey);
            lastObservedHpByTarget.remove(targetKey);
        }

        lethalWithoutDeathStreak.remove(targetKey);

        Float previousObservedHp = lastObservedHpByTarget.get(targetKey);
        if (Float.isFinite(hp)) {
            lastObservedHpByTarget.put(targetKey, hp);
        }

        if (!dead && previousObservedHp != null && Float.isFinite(previousObservedHp) && Float.isFinite(hp)) {
            if (hp > previousObservedHp + 1.0f) {
                LOGGER.atWarning().log(
                        "HpJumpWithoutDeath target=%d previousHp=%.3f currentHp=%.3f max=%.3f dead=%s",
                        targetId,
                        previousObservedHp,
                        hp,
                        max,
                        dead);
            }
            if (previousObservedHp <= 0.0001f && max > 0.0f && hp >= max - 0.001f) {
                LOGGER.atWarning().log(
                        "HpResetAfterZeroWithoutDeath target=%d previousHp=%.3f currentHp=%.3f max=%.3f dead=%s",
                        targetId,
                        previousObservedHp,
                        hp,
                        max,
                        dead);
            }
        }

        LOGGER.atInfo().log(
                "PlayerHitPostApply target=%d attacker=%d finalDamage=%.3f hp=%.3f max=%.3f mobLevel=%d",
                targetId,
                attackerRef.getIndex(),
                damage.getAmount(),
                hp,
                max,
                mobLevel);
    }

    private long toEntityKey(Store<EntityStore> store, int entityId) {
        long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
        long entityPart = Integer.toUnsignedLong(entityId);
        return (storePart << 32) | entityPart;
    }

    private long resolveTrackingKey(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return -1L;
        }

        Store<EntityStore> store = ref.getStore();
        UUIDComponent uuidComponent = commandBuffer != null
                ? commandBuffer.getComponent(ref, UUIDComponent.getComponentType())
                : null;

        if (uuidComponent == null && store != null) {
            uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        }

        if (uuidComponent != null) {
            try {
                UUID uuid = uuidComponent.getUuid();
                if (uuid != null) {
                    long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
                    long uuidPart = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 1);
                    return uuidPart ^ (storePart << 32);
                }
            } catch (Throwable ignored) {
            }
        }

        return toEntityKey(store, ref.getIndex());
    }
}
