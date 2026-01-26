package com.airijko.endlessleveling.listeners;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import java.util.Set;
import javax.annotation.Nonnull;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;

/**
 * Listens for player join/ready events and applies defense stat modifiers using SkillManager.
 * Also reduces incoming damage based on defense stat.
 */
public class PlayerDefenseListener extends DamageEventSystem {
	private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
	private final PlayerDataManager playerDataManager;
	private final SkillManager skillManager;

	public PlayerDefenseListener(PlayerDataManager playerDataManager, SkillManager skillManager) {
		this.playerDataManager = playerDataManager;
		this.skillManager = skillManager;
	}

	@Override
	@Nonnull
	public Query<EntityStore> getQuery() {
		// Listen to all entities for incoming damage events
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
		// Apply defense to the entity receiving damage (the target)
		Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
		PlayerRef defenderPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
		if (defenderPlayer != null && defenderPlayer.isValid()) {
			PlayerData playerData = playerDataManager.get(defenderPlayer.getUuid());
			if (playerData != null) {
				// Calculate defense resistance (0.0 = no reduction, 0.5 = 50% reduction)
				float resistance = skillManager.calculatePlayerDefense(playerData);
				float originalAmount = damage.getAmount();
				float reducedAmount = originalAmount * (1.0f - resistance);
				float reducedBy = originalAmount - reducedAmount;
				damage.setAmount(reducedAmount);
				
				LOGGER.atInfo().log("PlayerDefenseListener: Player %s took %.2f damage (original: %.2f, reduced by %.2f, %.1f%% resistance)",
					defenderPlayer.getUsername(), reducedAmount, originalAmount, reducedBy, resistance * 100);
			}
		}
	}
}
