package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.managers.MobLevelingManager;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import java.util.Set;
import java.util.Objects;

/**
 * Scales damage dealt by non-player entities (mobs) using LevelingManager
 * multipliers.
 */
public class MobDamageScalingSystem extends DamageEventSystem {

    private final MobLevelingManager levelingManager;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public MobDamageScalingSystem(MobLevelingManager levelingManager) {
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
        if (!levelingManager.isMobDamageScalingEnabled())
            return;
        // If the source is an entity but not a player, scale the damage
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
            PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayer == null || !attackerPlayer.isValid()) {
                // Treat as mob source
                int mobLevel = levelingManager.resolveMobLevel(attackerRef, commandBuffer);
                PlayerRef defenderPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());

                double mult;
                if (defenderPlayer != null && defenderPlayer.isValid()) {
                    int playerLevel = levelingManager.getPlayerLevel(defenderPlayer);
                    mult = levelingManager.getMobDamageMultiplierForLevels(
                            attackerRef,
                            commandBuffer,
                            mobLevel,
                            playerLevel);
                } else {
                    mult = levelingManager.getMobDamageMultiplierForLevel(attackerRef, commandBuffer, mobLevel);
                }

                float old = damage.getAmount();
                float updated = (float) (old * mult);
                damage.setAmount(updated);
                try {
                    LOGGER.atInfo().log("MobDamageScaling: scaled damage from %f to %f for entity %d", old, updated,
                            attackerRef.getIndex());
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
