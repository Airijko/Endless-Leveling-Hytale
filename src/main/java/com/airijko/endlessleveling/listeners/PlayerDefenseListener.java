package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.FirstStrikeSettings;
import com.airijko.endlessleveling.passives.RetaliationSettings;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
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
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
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

	public PlayerDefenseListener(PlayerDataManager playerDataManager, SkillManager skillManager,
			PassiveManager passiveManager,
			ArchetypePassiveManager archetypePassiveManager) {
		this.playerDataManager = playerDataManager;
		this.skillManager = skillManager;
		this.passiveManager = passiveManager;
		this.archetypePassiveManager = archetypePassiveManager;
	}

	@Override
	@Nonnull
	public Query<EntityStore> getQuery() {
		return Query.any();
	}

	@Override
	@Nonnull
	public Set<Dependency<EntityStore>> getDependencies() {
		return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
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

		float originalAmount = damage.getAmount();
		float resistance = skillManager.calculatePlayerDefense(playerData);
		float reducedAmount = originalAmount * (1.0f - resistance);

		PassiveRuntimeState runtimeState = passiveManager != null
				? passiveManager.getRuntimeState(playerData.getUuid())
				: null;
		EntityStatMap statMap = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
		ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
				? archetypePassiveManager.getSnapshot(playerData)
				: ArchetypePassiveSnapshot.empty();
		FirstStrikeSettings firstStrikeSettings = FirstStrikeSettings.fromSnapshot(archetypeSnapshot);
		RetaliationSettings retaliationSettings = RetaliationSettings.fromSnapshot(archetypeSnapshot);

		float adjustedAmount = reducedAmount;
		if (runtimeState != null && statMap != null && !archetypeSnapshot.isEmpty()) {
			adjustedAmount = applyLastStand(playerData, defenderPlayer, runtimeState, archetypeSnapshot, statMap,
					reducedAmount);
		}

		if (runtimeState != null) {
			handleRetaliation(runtimeState, retaliationSettings, adjustedAmount);
		}

		damage.setAmount(adjustedAmount);
		if (runtimeState != null) {
			suppressFirstStrikeIfHit(runtimeState, firstStrikeSettings);
		}

		if (passiveManager != null) {
			passiveManager.markCombat(playerData.getUuid());
		}

		float reducedBy = originalAmount - adjustedAmount;
		LOGGER.atInfo().log(
				"PlayerDefenseListener: Player %s took %.2f damage (original: %.2f, reduced by %.2f, %.1f%% resistance)",
				defenderPlayer.getUsername(), adjustedAmount, originalAmount, reducedBy, resistance * 100);
	}

	private float applyLastStand(@Nonnull PlayerData playerData,
			@Nonnull PlayerRef defenderPlayer,
			@Nonnull PassiveRuntimeState runtimeState,
			@Nonnull ArchetypePassiveSnapshot snapshot,
			@Nonnull EntityStatMap statMap,
			float incomingDamage) {
		LastStandSettings settings = resolveLastStandSettings(snapshot);
		if (!settings.enabled() || incomingDamage <= 0) {
			return incomingDamage;
		}

		EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
		if (healthStat == null) {
			return incomingDamage;
		}

		float maxHealth = healthStat.getMax();
		float currentHealth = healthStat.get();
		if (maxHealth <= 0 || currentHealth <= 0) {
			return incomingDamage;
		}

		float predictedHealth = Math.max(0f, currentHealth - incomingDamage);
		if (predictedHealth <= 0f) {
			return incomingDamage;
		}

		float thresholdHealth = (float) (maxHealth * settings.thresholdPercent());
		if (thresholdHealth <= 0 || predictedHealth > thresholdHealth) {
			return incomingDamage;
		}

		long now = System.currentTimeMillis();
		if (now < runtimeState.getLastStandCooldownExpiresAt()
				|| now < runtimeState.getLastStandActiveUntil()) {
			return incomingDamage;
		}

		float healAmount = (float) Math.max(0.0D, maxHealth * settings.healPercent());
		if (healAmount <= 0f) {
			return incomingDamage;
		}

		double durationSeconds = Math.max(0.1D, settings.durationSeconds());
		double totalHeal = Math.min(healAmount, maxHealth);
		double perSecond = totalHeal / durationSeconds;
		runtimeState.setLastStandHealPerSecond(perSecond);
		runtimeState.setLastStandHealRemaining(totalHeal);

		runtimeState.setLastStandCooldownExpiresAt(now + settings.cooldownMillis());
		runtimeState.setLastStandActiveUntil(now + settings.durationMillis());
		runtimeState.setLastStandReadyNotified(false);
		sendPassiveMessage(defenderPlayer,
				String.format("Last Stand triggered! Healing %.0f%% HP over %.0fs",
						settings.healPercent() * 100.0D,
						settings.durationSeconds()));
		LOGGER.atFine().log("Last Stand triggered for %s", playerData.getPlayerName());
		return incomingDamage;
	}

	private LastStandSettings resolveLastStandSettings(ArchetypePassiveSnapshot snapshot) {
		double healPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.LAST_STAND));
		if (healPercent <= 0) {
			return LastStandSettings.disabled();
		}

		List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.LAST_STAND);
		double threshold = 0.0D;
		double durationSeconds = 0.0D;
		double cooldownSeconds = 0.0D;

		for (RacePassiveDefinition definition : definitions) {
			Map<String, Object> props = definition.properties();
			threshold = Math.max(threshold, parsePositiveDouble(props.get("threshold")));
			durationSeconds = Math.max(durationSeconds, parsePositiveDouble(props.get("duration")));
			double cooldownCandidate = parsePositiveDouble(props.get("cooldown"));
			if (cooldownCandidate > 0) {
				cooldownSeconds = cooldownSeconds <= 0
						? cooldownCandidate
						: Math.min(cooldownSeconds, cooldownCandidate);
			}
		}

		double resolvedThreshold = threshold > 0 ? threshold : 0.2D;
		double resolvedDuration = durationSeconds > 0 ? durationSeconds : 5.0D;
		double resolvedCooldown = cooldownSeconds > 0 ? cooldownSeconds : 60.0D;
		return new LastStandSettings(true, healPercent, resolvedThreshold, resolvedDuration, resolvedCooldown);
	}

	private double parsePositiveDouble(Object raw) {
		if (raw instanceof Number number) {
			double value = number.doubleValue();
			return value > 0 ? value : 0.0D;
		}
		if (raw instanceof String string) {
			try {
				double parsed = Double.parseDouble(string.trim());
				return parsed > 0 ? parsed : 0.0D;
			} catch (NumberFormatException ignored) {
			}
		}
		return 0.0D;
	}

	private record LastStandSettings(boolean enabled,
			double healPercent,
			double thresholdPercent,
			double durationSeconds,
			double cooldownSeconds) {

		static LastStandSettings disabled() {
			return new LastStandSettings(false, 0.0D, 0.0D, 0.0D, 0.0D);
		}

		long durationMillis() {
			return (long) Math.max(0L, Math.round(durationSeconds * 1000.0D));
		}

		long cooldownMillis() {
			return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
		}
	}

	/**
	 * Collects incoming damage for the Retaliation passive so it can be released on
	 * the player's next attack.
	 */
	private void handleRetaliation(@Nonnull PassiveRuntimeState runtimeState,
			@Nonnull RetaliationSettings settings,
			float damageTaken) {
		double reflectPercent = Math.max(0.0D, settings.reflectPercent());
		if (!settings.enabled() || reflectPercent <= 0.0D) {
			clearRetaliationState(runtimeState);
			return;
		}
		if (damageTaken <= 0f) {
			return;
		}

		double contribution = damageTaken * reflectPercent;
		if (contribution <= 0.0D) {
			return;
		}

		long now = System.currentTimeMillis();
		expireRetaliationWindowIfNeeded(runtimeState, now);

		if (now < runtimeState.getRetaliationCooldownExpiresAt()) {
			return;
		}

		long windowMillis = settings.windowMillis();
		if (windowMillis <= 0L) {
			return;
		}

		double stored = runtimeState.getRetaliationDamageStored();
		long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
		if (stored <= 0.0D || windowExpiresAt <= 0L || now > windowExpiresAt) {
			runtimeState.setRetaliationDamageStored(contribution);
			runtimeState.setRetaliationWindowExpiresAt(now + windowMillis);
		} else {
			runtimeState.setRetaliationDamageStored(stored + contribution);
		}
	}

	private void expireRetaliationWindowIfNeeded(@Nonnull PassiveRuntimeState runtimeState, long now) {
		long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
		if (windowExpiresAt > 0L && now > windowExpiresAt) {
			runtimeState.setRetaliationWindowExpiresAt(0L);
			runtimeState.setRetaliationDamageStored(0.0D);
		}
	}

	private void clearRetaliationState(@Nonnull PassiveRuntimeState runtimeState) {
		runtimeState.setRetaliationWindowExpiresAt(0L);
		runtimeState.setRetaliationDamageStored(0.0D);
		runtimeState.setRetaliationCooldownExpiresAt(0L);
		runtimeState.setRetaliationReadyNotified(true);
	}

	private void suppressFirstStrikeIfHit(PassiveRuntimeState runtimeState,
			FirstStrikeSettings settings) {
		if (runtimeState == null || !settings.enabled() || settings.cooldownMillis() <= 0) {
			return;
		}

		long now = System.currentTimeMillis();
		if (now < runtimeState.getFirstStrikeCooldownExpiresAt()) {
			return;
		}

		runtimeState.setFirstStrikeCooldownExpiresAt(now + settings.cooldownMillis());
		runtimeState.setFirstStrikeReadyNotified(false);
	}

	private void sendPassiveMessage(PlayerRef playerRef, String text) {
		if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
			return;
		}
		playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
	}
}
