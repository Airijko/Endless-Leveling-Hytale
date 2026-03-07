package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.systems.MobDamageScalingSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Listens for player join/ready events and applies defense stat modifiers using
 * SkillManager.
 * Also reduces incoming damage based on defense stat.
 */
public class PlayerDefenseListener extends DamageEventSystem {
	private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
	private final PlayerDataManager playerDataManager;
	private final SkillManager skillManager;
	private final PassiveManager passiveManager;
	private final ArchetypePassiveManager archetypePassiveManager;
	private final AugmentExecutor augmentExecutor;
	private final CombatHookProcessor combatHookProcessor;

	public PlayerDefenseListener(PlayerDataManager playerDataManager, SkillManager skillManager,
			PassiveManager passiveManager,
			ArchetypePassiveManager archetypePassiveManager,
			AugmentExecutor augmentExecutor) {
		this.playerDataManager = playerDataManager;
		this.skillManager = skillManager;
		this.passiveManager = passiveManager;
		this.archetypePassiveManager = archetypePassiveManager;
		this.augmentExecutor = augmentExecutor;
		this.combatHookProcessor = new CombatHookProcessor(skillManager,
				passiveManager,
				archetypePassiveManager,
				null,
				augmentExecutor);
	}

	@Override
	@Nonnull
	public Query<EntityStore> getQuery() {
		return Query.any();
	}

	@Override
	@Nonnull
	public Set<Dependency<EntityStore>> getDependencies() {
		return Set.of(
				new SystemDependency<>(Order.AFTER, PlayerCombatListener.class),
				new SystemDependency<>(Order.AFTER, MobDamageScalingSystem.class),
				new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
	}

	@Override
	public void handle(
			int index,
			@Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
			@Nonnull Store<EntityStore> store,
			@Nonnull CommandBuffer<EntityStore> commandBuffer,
			@Nonnull Damage damage) {
		Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
		PlayerRef defenderPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
		if (defenderPlayer == null || !defenderPlayer.isValid()) {
			return;
		}

		PlayerData playerData = playerDataManager.get(defenderPlayer.getUuid());
		if (playerData == null) {
			return;
		}

		Ref<EntityStore> attackerRef = null;
		if (damage.getSource() instanceof Damage.EntitySource entitySource) {
			attackerRef = entitySource.getRef();
		}

		PassiveRuntimeState runtimeState = passiveManager != null
				? passiveManager.getRuntimeState(playerData.getUuid())
				: null;
		EntityStatMap statMap = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
		ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
				? archetypePassiveManager.getSnapshot(playerData)
				: ArchetypePassiveSnapshot.empty();

		CombatHookProcessor.IncomingResult result = combatHookProcessor.processIncoming(
				new CombatHookProcessor.IncomingContext(
						playerData,
						defenderPlayer,
						targetRef,
						attackerRef,
						commandBuffer,
						damage,
						runtimeState,
						archetypeSnapshot,
						statMap));

		damage.setAmount(result.finalDamage());
		float reducedBy = result.originalDamage() - result.finalDamage();
		LOGGER.atInfo().log(
				"PlayerDefenseListener: Player %s took %.2f damage (original: %.2f, reduced by %.2f, %.1f%% resistance)",
				defenderPlayer.getUsername(), result.finalDamage(), result.originalDamage(), reducedBy,
				result.resistance() * 100);
	}
}
