package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.managers.LevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Set;

/**
 * Simulates increased mob health by scaling incoming damage to mobs.
 * This avoids modifying stat objects directly (which can lead to inconsistent
 * state and invulnerability). When mob health-scaling is enabled in config,
 * damage received by mobs will be divided by the health multiplier.
 */
public class MobIncomingDamageScalingSystem extends DamageEventSystem {

    private final LevelingManager levelingManager;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public MobIncomingDamageScalingSystem(LevelingManager levelingManager) {
        this.levelingManager = Objects.requireNonNull(levelingManager);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Nonnull
    @Override
    public Set<com.hypixel.hytale.component.dependency.Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        // Only apply when health-scaling enabled
        if (!levelingManager.isMobHealthScalingEnabled())
            return;

        // Target is the entity this damage event is for (index into the chunk)
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        if (targetPlayer != null && targetPlayer.isValid()) {
            // target is a player -> don't scale incoming damage
            return;
        }

        // Target is a mob (or non-player). Apply inverse of the health multiplier
        // so mobs effectively have more HP without changing stat objects.
        int mobLevel = 100; // TODO: derive per-entity level when available
        double hpMult = levelingManager.getMobHealthMultiplierForLevel(mobLevel);
        if (hpMult <= 0.0)
            return;

        float old = damage.getAmount();
        float updated = (float) (old / hpMult);
        damage.setAmount(updated);
        try {
            LOGGER.atInfo().log("MobIncomingDamageScaling: scaled incoming damage from %f to %f for target %d", old,
                    updated, targetRef.getIndex());
        } catch (Throwable ignored) {
        }
    }
}
