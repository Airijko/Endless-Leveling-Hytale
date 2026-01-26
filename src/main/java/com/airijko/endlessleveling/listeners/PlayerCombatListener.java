package com.airijko.endlessleveling.listeners;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;

/**
 * Listens for when a player deals damage and sends a chat message with the
 * damage dealt.
 */
public class PlayerCombatListener extends DamageEventSystem {
    private final SkillManager skillManager = EndlessLeveling.getInstance().getSkillManager();

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        // Run before vanilla damage is applied
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        // Only notify if the source is a player
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayer != null && attackerPlayer.isValid()) {
                // Get the player's PlayerData (assume a PlayerDataManager is accessible
                // statically or via singleton)
                PlayerData playerData = com.airijko.endlessleveling.EndlessLeveling.getInstance().getPlayerDataManager()
                        .get(attackerPlayer.getUuid());
                if (playerData != null) {
                    // Calculate strength bonus using SkillManager
                    float baseAmount = skillManager.applyStrengthModifier(damage.getAmount(), playerData);
                    // Apply critical hit system
                    SkillManager.CritResult critResult = skillManager.applyCriticalHit(playerData, baseAmount);
                    damage.setAmount(critResult.damage);
                }
            }
        }
    }
}