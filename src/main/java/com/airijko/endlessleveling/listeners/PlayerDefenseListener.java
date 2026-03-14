package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.augments.types.ProtectiveBubbleAugment;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.MobLevelingManager;
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
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Set;
import java.util.UUID;
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
	private final MobAugmentExecutor mobAugmentExecutor;
	private final MobLevelingManager mobLevelingManager;
	private final CombatHookProcessor combatHookProcessor;

	public PlayerDefenseListener(PlayerDataManager playerDataManager, SkillManager skillManager,
			PassiveManager passiveManager,
			ArchetypePassiveManager archetypePassiveManager,
			AugmentExecutor augmentExecutor,
			MobAugmentExecutor mobAugmentExecutor,
			MobLevelingManager mobLevelingManager) {
		this.playerDataManager = playerDataManager;
		this.skillManager = skillManager;
		this.passiveManager = passiveManager;
		this.archetypePassiveManager = archetypePassiveManager;
		this.augmentExecutor = augmentExecutor;
		this.mobAugmentExecutor = mobAugmentExecutor;
		this.mobLevelingManager = mobLevelingManager;
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
		UUID mobAttackerUuid = null;

		if (attackerRef != null && mobAugmentExecutor != null && mobLevelingManager != null) {
			PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
			boolean attackerIsPlayer = attackerPlayer != null && attackerPlayer.isValid();
			if (!attackerIsPlayer) {
				List<String> attackerAugments = mobLevelingManager.getMobOverrideAugmentIds(attackerRef,
						attackerRef.getStore(), commandBuffer);
				if (!attackerAugments.isEmpty()) {
					UUID attackerUuid = resolveEntityUuid(attackerRef, attackerRef.getStore(), commandBuffer);
					if (attackerUuid != null) {
						mobAttackerUuid = attackerUuid;
						if (!mobAugmentExecutor.hasMobAugments(attackerUuid)) {
							EndlessLeveling plugin = EndlessLeveling.getInstance();
							if (plugin != null && plugin.getAugmentManager() != null
									&& plugin.getAugmentRuntimeManager() != null) {
								mobAugmentExecutor.registerMobAugments(attackerUuid,
										attackerAugments,
										plugin.getAugmentManager(),
										plugin.getAugmentRuntimeManager());
							}
						}

						EntityStatMap attackerStats = commandBuffer.getComponent(attackerRef,
								EntityStatMap.getComponentType());
						EntityStatMap defenderStats = commandBuffer.getComponent(targetRef,
								EntityStatMap.getComponentType());
						float originalDamage = damage.getAmount();
						var onHit = mobAugmentExecutor.applyOnHit(attackerUuid,
								attackerRef,
								targetRef,
								commandBuffer,
								attackerStats,
								defenderStats,
								originalDamage);
						damage.setAmount(onHit.damage());

						float appliedTrueDamage = applyMobTrueDamage(playerData,
								targetRef,
								attackerRef,
								commandBuffer,
								onHit.trueDamageBonus());
						if (Math.abs(onHit.damage() - originalDamage) > 0.0001f || appliedTrueDamage > 0f) {
							LOGGER.atInfo().log(
									"MobOnHitAugments attacker=%d defender=%s damage=%.3f true=%.3f augments=%s",
									attackerRef.getIndex(),
									defenderPlayer.getUsername(),
									onHit.damage(),
									appliedTrueDamage,
									attackerAugments);
						}
					}
				}
			}
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
		if (mobAttackerUuid != null && attackerRef != null && statMap != null) {
			EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
			if (hp != null && hp.getMax() > 0f && hp.get() > 0f) {
				float predictedAfterHit = hp.get() - result.finalDamage();
				if (predictedAfterHit <= 0f) {
					mobAugmentExecutor.handleKill(
							mobAttackerUuid,
							attackerRef,
							targetRef,
							commandBuffer,
							statMap);
				}
			}
		}
		float reducedBy = result.originalDamage() - result.finalDamage();
		LOGGER.atInfo().log(
				"PlayerDefenseListener: Player %s took %.2f damage (original: %.2f, reduced by %.2f, %.1f%% resistance)",
				defenderPlayer.getUsername(), result.finalDamage(), result.originalDamage(), reducedBy,
				result.resistance() * 100);
	}

	private float applyMobTrueDamage(PlayerData defenderData,
			Ref<EntityStore> defenderRef,
			Ref<EntityStore> attackerRef,
			CommandBuffer<EntityStore> commandBuffer,
			double rawTrueDamage) {
		if (defenderData == null || defenderRef == null || commandBuffer == null || rawTrueDamage <= 0.0D) {
			return 0.0f;
		}

		EntityStatMap statMap = commandBuffer.getComponent(defenderRef, EntityStatMap.getComponentType());
		if (statMap == null) {
			return 0.0f;
		}

		if (augmentExecutor != null) {
			float afterBubble = augmentExecutor.applySpecificOnDamageTaken(defenderData,
					defenderRef,
					attackerRef,
					commandBuffer,
					statMap,
					(float) rawTrueDamage,
					ProtectiveBubbleAugment.ID);
			rawTrueDamage = Math.max(0.0D, afterBubble);
			if (rawTrueDamage <= 0.0D) {
				return 0.0f;
			}
		}

		EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
		if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
			return 0.0f;
		}

		float current = hp.get();
		float applied = (float) Math.min(current, rawTrueDamage);
		if (applied <= 0f) {
			return 0.0f;
		}

		statMap.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(0.0f, current - applied));
		return applied;
	}

	private UUID resolveEntityUuid(Ref<EntityStore> ref,
			Store<EntityStore> store,
			CommandBuffer<EntityStore> commandBuffer) {
		if (ref == null) {
			return null;
		}

		UUIDComponent uuidComponent = commandBuffer != null
				? commandBuffer.getComponent(ref, UUIDComponent.getComponentType())
				: null;
		if (uuidComponent == null && store != null) {
			uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
		}
		if (uuidComponent == null) {
			return null;
		}

		try {
			return uuidComponent.getUuid();
		} catch (Throwable ignored) {
			return null;
		}
	}
}
