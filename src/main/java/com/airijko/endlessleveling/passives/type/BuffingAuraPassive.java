package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveScaling;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodically distributes a shared party damage bonus based on source stamina.
 */
public final class BuffingAuraPassive {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private static final long PULSE_INTERVAL_MILLIS = 1000L;
    private static final double DEFAULT_STAMINA_RATIO = 0.4D;
    private static final double DEFAULT_MAX_BUFFED_VALUE_PER_ALLY = 1.0D;
    private static final double DEFAULT_SELF_BUFF_EFFECTIVENESS = 0.25D;
    private static final double DEFAULT_BASE_RADIUS = PartyHealingDistributor.DEFAULT_BASE_RADIUS_BLOCKS;
    private static final double DEFAULT_MANA_PER_BLOCK = PartyHealingDistributor.DEFAULT_MANA_PER_RADIUS_BLOCK;
    private static final double DEFAULT_DURATION_SECONDS = 4.0D;
    private static final double DEFAULT_DAMAGE_PAUSE_SECONDS = 10.0D;
    private static final double EPSILON = 0.000001D;

    private BuffingAuraPassive() {
    }

    public static long pulseIntervalMillis() {
        return PULSE_INTERVAL_MILLIS;
    }

    public static void pulse(PlayerData sourcePlayerData,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            ArchetypePassiveSnapshot archetypeSnapshot,
            PassiveRuntimeState sourceRuntimeState) {
        if (sourcePlayerData == null || sourceRef == null || commandBuffer == null
                || sourceStats == null || archetypeSnapshot == null || sourceRuntimeState == null) {
            LOGGER.atFine().log("[BUFFING_AURA] Skip pulse: missing required runtime inputs.");
            return;
        }

        UUID sourceUuid = sourcePlayerData.getUuid();
        ArchetypePassiveScaling.AuraScales auraScales = ArchetypePassiveScaling.resolveAuraScales(
            archetypeSnapshot,
            ArchetypePassiveType.BUFFING_AURA,
            sourcePlayerData);
        if (auraScales.ratioScale() <= 0.0D) {
            LOGGER.atFine().log("[BUFFING_AURA] Skip pulse for %s: passive value %.3f is not active.",
                    sourceUuid,
                auraScales.ratioScale());
            return;
        }

        BuffingAuraConfig config = resolveConfig(archetypeSnapshot);
        long now = System.currentTimeMillis();

        if (isPausedByDamage(sourceRuntimeState, now, config.damagePauseMillis())) {
            long lastDamageTakenMillis = sourceRuntimeState.getLastDamageTakenMillis();
            long elapsedSinceDamage = Math.max(0L, now - lastDamageTakenMillis);
            long remainingPauseMillis = Math.max(0L, config.damagePauseMillis() - elapsedSinceDamage);
            if (elapsedSinceDamage <= 250L) {
                LOGGER.atInfo().log(
                        "[BUFFING_AURA] Paused for %s after taking damage (remaining %.2fs, lastHitAgo=%dms).",
                        sourceUuid,
                        remainingPauseMillis / 1000.0D,
                        elapsedSinceDamage);
            } else {
                LOGGER.atFiner().log("[BUFFING_AURA] Skip pulse for %s: paused by damage (remaining %.2fs).",
                        sourceUuid,
                        remainingPauseMillis / 1000.0D);
            }
            return;
        }

        long lastPulse = sourceRuntimeState.getBuffingAuraLastPulseMillis();
        if (lastPulse > 0L && now - lastPulse < PULSE_INTERVAL_MILLIS) {
            LOGGER.atFiner().log("[BUFFING_AURA] Skip pulse for %s: waiting for pulse interval (%dms/%dms).",
                    sourceUuid,
                    now - lastPulse,
                    PULSE_INTERVAL_MILLIS);
            return;
        }

        EntityStatValue sourceHealth = sourceStats.get(DefaultEntityStatTypes.getHealth());
        if (sourceHealth == null || sourceHealth.getMax() <= 0f || sourceHealth.get() <= 0f) {
            LOGGER.atFine().log("[BUFFING_AURA] Skip pulse for %s: source is not alive.", sourceUuid);
            return;
        }

        double totalStamina = resolveSourceStamina(sourcePlayerData, sourceStats);
        double pooledBuff = (totalStamina * config.staminaRatio()) * auraScales.ratioScale();
        if (pooledBuff <= 0.0D) {
            LOGGER.atFine().log(
                    "[BUFFING_AURA] Skip pulse for %s: pooled buff is zero (stamina=%.3f ratio=%.3f value=%.3f).",
                    sourceUuid,
                    totalStamina,
                    config.staminaRatio(),
                auraScales.ratioScale());
            return;
        }

        List<PartyBuffTarget> targets = resolvePartyTargets(sourcePlayerData,
                sourceRef,
                commandBuffer,
                sourceStats,
                config.baseRadius(),
                config.manaPerBlock());
        if (targets.isEmpty()) {
            LOGGER.atFine().log("[BUFFING_AURA] Skip pulse for %s: no valid party targets in radius %.2f.",
                    sourceUuid,
                    config.baseRadius());
            return;
        }

        long expiresAt = now + config.durationMillis();
        distributeBuffPool(targets,
                pooledBuff,
            config.maxBuffedValuePerAlly() * auraScales.ratioScale(),
                config.selfBuffEffectiveness(),
                expiresAt);

        LOGGER.atInfo().log(
                "[BUFFING_AURA] Activated for %s: targets=%d pooled=%.3f ratio=%.3f duration=%.2fs selfEffect=%.2f maxPerAlly=%.3f.",
                sourceUuid,
                targets.size(),
                pooledBuff,
                config.staminaRatio(),
                config.durationMillis() / 1000.0D,
                config.selfBuffEffectiveness(),
                config.maxBuffedValuePerAlly());

        sourceRuntimeState.setBuffingAuraLastPulseMillis(now);
    }

    public static double currentDamageBonus(PassiveRuntimeState runtimeState, long now) {
        if (runtimeState == null) {
            return 0.0D;
        }
        cleanupExpiredBonus(runtimeState, now);
        return Math.max(0.0D, runtimeState.getBuffingAuraDamageBonus());
    }

    public static void cleanupExpiredBonus(PassiveRuntimeState runtimeState, long now) {
        if (runtimeState == null) {
            return;
        }
        long expiresAt = runtimeState.getBuffingAuraBonusExpiresAt();
        if (expiresAt <= 0L || now < expiresAt) {
            return;
        }
        LOGGER.atFiner().log("[BUFFING_AURA] Expired active bonus (expiredAt=%d, now=%d).", expiresAt, now);
        runtimeState.clearBuffingAuraBonus();
    }

    private static boolean isPausedByDamage(PassiveRuntimeState runtimeState, long now, long pauseMillis) {
        if (runtimeState == null || pauseMillis <= 0L) {
            return false;
        }
        long lastDamageTakenMillis = runtimeState.getLastDamageTakenMillis();
        if (lastDamageTakenMillis <= 0L) {
            return false;
        }
        return now - lastDamageTakenMillis < pauseMillis;
    }

    private static void distributeBuffPool(List<PartyBuffTarget> targets,
            double pooledBuff,
            double maxBuffPerAlly,
            double selfBuffEffectiveness,
            long expiresAt) {
        if (targets == null || targets.isEmpty() || pooledBuff <= 0.0D || maxBuffPerAlly <= 0.0D) {
            return;
        }

        List<PendingTarget> allTargets = new ArrayList<>();
        for (PartyBuffTarget target : targets) {
            if (target == null || target.runtimeState() == null) {
                continue;
            }
            double multiplier = target.selfTarget()
                    ? Math.max(0.0D, selfBuffEffectiveness)
                    : 1.0D;
            if (multiplier <= 0.0D) {
                continue;
            }
            allTargets.add(new PendingTarget(target.runtimeState(), multiplier));
        }

        if (allTargets.isEmpty()) {
            return;
        }

        List<PendingTarget> pending = new ArrayList<>(allTargets);
        double remainingPool = pooledBuff;
        while (remainingPool > EPSILON && !pending.isEmpty()) {
            double share = remainingPool / pending.size();
            if (share <= EPSILON) {
                break;
            }

            List<PendingTarget> nextRound = new ArrayList<>();
            double consumedRaw = 0.0D;
            for (PendingTarget target : pending) {
                if (target == null || target.multiplier() <= 0.0D) {
                    continue;
                }

                double effectiveShare = share * target.multiplier();
                double remainingCap = Math.max(0.0D, maxBuffPerAlly - target.assignedBonus());
                if (remainingCap <= EPSILON) {
                    continue;
                }

                double appliedBonus = Math.min(remainingCap, effectiveShare);
                if (appliedBonus <= EPSILON) {
                    continue;
                }

                target.addBonus(appliedBonus);
                consumedRaw += appliedBonus / target.multiplier();

                if (target.assignedBonus() + EPSILON < maxBuffPerAlly) {
                    nextRound.add(target);
                }
            }

            if (consumedRaw <= EPSILON) {
                break;
            }
            remainingPool = Math.max(0.0D, remainingPool - consumedRaw);
            pending = nextRound;
        }

        for (PendingTarget target : allTargets) {
            if (target == null) {
                continue;
            }
            applyDamageBuff(target.runtimeState(), target.assignedBonus(), expiresAt);
        }
    }

    private static void applyDamageBuff(PassiveRuntimeState runtimeState, double damageBonus, long expiresAt) {
        if (runtimeState == null) {
            return;
        }

        double clampedBonus = Math.max(0.0D, damageBonus);
        if (clampedBonus <= EPSILON) {
            LOGGER.atFiner().log("[BUFFING_AURA] Clearing target bonus: assigned bonus <= epsilon (%.6f).",
                    clampedBonus);
            runtimeState.clearBuffingAuraBonus();
            return;
        }

        runtimeState.setBuffingAuraDamageBonus(clampedBonus);
        runtimeState.setBuffingAuraBonusExpiresAt(Math.max(0L, expiresAt));
        LOGGER.atFiner().log("[BUFFING_AURA] Applied target bonus %.3f until %d.", clampedBonus, expiresAt);
    }

    private static List<PartyBuffTarget> resolvePartyTargets(PlayerData sourcePlayerData,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            double baseRadius,
            double manaPerBlock) {
        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            return List.of();
        }

        EntityStatValue sourceMana = sourceStats.get(DefaultEntityStatTypes.getMana());
        double totalMana = sourceMana != null ? Math.max(0.0D, sourceMana.getMax()) : 0.0D;

        double radius = Math.max(0.0D, baseRadius);
        if (manaPerBlock > 0.0D) {
            radius += Math.floor(totalMana / manaPerBlock);
        }
        if (radius <= 0.0D) {
            return List.of();
        }

        UUID sourceUuid = sourcePlayerData.getUuid();
        PassiveManager passiveManager = resolvePassiveManager();
        if (passiveManager == null || sourceUuid == null) {
            return List.of();
        }

        PartyManager partyManager = resolvePartyManager();
        UUID sourcePartyLeader = resolvePartyLeader(partyManager, sourceUuid);

        List<PartyBuffTarget> targets = new ArrayList<>();
        HashSet<Integer> visitedEntityIds = new HashSet<>();

        if (sourceRef != null) {
            visitedEntityIds.add(sourceRef.getIndex());
        }
        if (isLivingTarget(sourceStats)) {
            PassiveRuntimeState sourceRuntimeState = passiveManager.getRuntimeState(sourceUuid);
            if (sourceRuntimeState != null) {
                targets.add(new PartyBuffTarget(sourceRuntimeState, true));
            }
        }

        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(
                sourceTransform.getPosition(),
                radius,
                commandBuffer)) {
            if (!EntityRefUtil.isUsable(targetRef)) {
                continue;
            }
            if (!visitedEntityIds.add(targetRef.getIndex())) {
                continue;
            }

            PlayerRef targetPlayer = EntityRefUtil.tryGetComponent(commandBuffer,
                    targetRef,
                    PlayerRef.getComponentType());
            if (targetPlayer == null || !targetPlayer.isValid() || targetPlayer.getUuid() == null) {
                continue;
            }

            UUID targetUuid = targetPlayer.getUuid();
            if (!isSamePartyTarget(sourceUuid, sourcePartyLeader, targetUuid, partyManager)) {
                continue;
            }

            EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer,
                    targetRef,
                    EntityStatMap.getComponentType());
            if (!isLivingTarget(targetStats)) {
                continue;
            }

            PassiveRuntimeState targetRuntimeState = passiveManager.getRuntimeState(targetUuid);
            if (targetRuntimeState == null) {
                continue;
            }

            boolean selfTarget = sourceUuid.equals(targetUuid);
            targets.add(new PartyBuffTarget(targetRuntimeState, selfTarget));
        }

        return targets;
    }

    private static double resolveSourceStamina(PlayerData sourcePlayerData, EntityStatMap sourceStats) {
        double staminaFromStat = 0.0D;
        if (sourceStats != null) {
            EntityStatValue sourceStamina = sourceStats.get(DefaultEntityStatTypes.getStamina());
            if (sourceStamina != null) {
                staminaFromStat = Math.max(0.0D, sourceStamina.getMax());
            }
        }
        if (staminaFromStat > 0.0D) {
            return staminaFromStat;
        }
        if (sourcePlayerData == null) {
            return 0.0D;
        }
        return Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.STAMINA));
    }

    private static boolean isLivingTarget(EntityStatMap statMap) {
        if (statMap == null) {
            return false;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null || healthStat.getMax() <= 0f || healthStat.get() <= 0f) {
            return false;
        }
        return true;
    }

    private static boolean isSamePartyTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            UUID targetUuid,
            PartyManager partyManager) {
        if (targetUuid == null) {
            return false;
        }
        if (sourceUuid.equals(targetUuid)) {
            return true;
        }
        if (partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        UUID effectiveSourceLeader = sourcePartyLeader != null
                ? sourcePartyLeader
                : resolvePartyLeader(partyManager, sourceUuid);
        if (effectiveSourceLeader == null) {
            return false;
        }

        UUID targetLeader = resolvePartyLeader(partyManager, targetUuid);
        return targetLeader != null && targetLeader.equals(effectiveSourceLeader);
    }

    private static UUID resolvePartyLeader(PartyManager partyManager, UUID playerUuid) {
        if (partyManager == null || !partyManager.isAvailable() || playerUuid == null) {
            return null;
        }
        if (!partyManager.isInParty(playerUuid)) {
            return null;
        }
        return partyManager.getPartyLeader(playerUuid);
    }

    private static PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    private static PassiveManager resolvePassiveManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPassiveManager() : null;
    }

    private static BuffingAuraConfig resolveConfig(ArchetypePassiveSnapshot snapshot) {
        double staminaRatio = DEFAULT_STAMINA_RATIO;
        double maxBuffedValuePerAlly = DEFAULT_MAX_BUFFED_VALUE_PER_ALLY;
        double selfBuffEffectiveness = DEFAULT_SELF_BUFF_EFFECTIVENESS;
        double baseRadius = DEFAULT_BASE_RADIUS;
        double manaPerBlock = DEFAULT_MANA_PER_BLOCK;
        long durationMillis = secondsToMillis(DEFAULT_DURATION_SECONDS);
        long damagePauseMillis = secondsToMillis(DEFAULT_DAMAGE_PAUSE_SECONDS);

        if (snapshot == null) {
            return new BuffingAuraConfig(staminaRatio,
                    maxBuffedValuePerAlly,
                    selfBuffEffectiveness,
                    baseRadius,
                    manaPerBlock,
                    durationMillis,
                    damagePauseMillis);
        }

        RacePassiveDefinition strongestDefinition = resolveStrongestDefinition(
                snapshot.getDefinitions(ArchetypePassiveType.BUFFING_AURA));
        if (strongestDefinition == null || strongestDefinition.properties() == null
                || strongestDefinition.properties().isEmpty()) {
            return new BuffingAuraConfig(staminaRatio,
                    maxBuffedValuePerAlly,
                    selfBuffEffectiveness,
                    baseRadius,
                    manaPerBlock,
                    durationMillis,
                    damagePauseMillis);
        }

        Map<String, Object> props = strongestDefinition.properties();
        staminaRatio = parseNonNegative(props.get("stamina_ratio"), staminaRatio);
        maxBuffedValuePerAlly = parseNonNegative(firstNonNull(props.get("max_buffed_value_per_ally"),
                props.get("max_buff_per_ally")), maxBuffedValuePerAlly);
        selfBuffEffectiveness = parseNonNegative(firstNonNull(props.get("self_buff_effectiveness"),
                props.get("self_buff_ratio")), selfBuffEffectiveness);

        baseRadius = parseNonNegative(props.get("radius"), baseRadius);
        Object radiusScalingRaw = props.get("radius_mana_scaling");
        if (radiusScalingRaw instanceof Map<?, ?> radiusScaling) {
            manaPerBlock = parsePositive(radiusScaling.get("mana_per_block"), manaPerBlock);
        }

        double durationSeconds = parseNonNegative(firstNonNull(props.get("duration"),
                firstNonNull(props.get("buff_duration_seconds"), props.get("bonus_duration_seconds"))),
                DEFAULT_DURATION_SECONDS);
        durationMillis = secondsToMillis(durationSeconds);

        double damagePauseSeconds = parseNonNegative(firstNonNull(props.get("damage_pause_seconds"),
                firstNonNull(props.get("pause_after_damage_seconds"), props.get("combat_pause_seconds"))),
                DEFAULT_DAMAGE_PAUSE_SECONDS);
        damagePauseMillis = secondsToMillis(damagePauseSeconds);

        return new BuffingAuraConfig(staminaRatio,
                maxBuffedValuePerAlly,
                selfBuffEffectiveness,
                baseRadius,
                manaPerBlock,
                durationMillis,
                damagePauseMillis);
    }

    private static RacePassiveDefinition resolveStrongestDefinition(List<RacePassiveDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        RacePassiveDefinition strongest = null;
        double strongestValue = Double.NEGATIVE_INFINITY;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            double value = definition.value();
            if (strongest == null || value > strongestValue) {
                strongest = definition;
                strongestValue = value;
            }
        }
        return strongest;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static double parseNonNegative(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return Math.max(0.0D, value);
    }

    private static double parsePositive(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return value > 0.0D ? value : fallback;
    }

    private static double parseRawDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long secondsToMillis(double seconds) {
        if (seconds <= 0.0D) {
            return 0L;
        }
        return Math.max(1L, Math.round(seconds * 1000.0D));
    }

    private record PartyBuffTarget(PassiveRuntimeState runtimeState, boolean selfTarget) {
    }

    private record BuffingAuraConfig(double staminaRatio,
            double maxBuffedValuePerAlly,
            double selfBuffEffectiveness,
            double baseRadius,
            double manaPerBlock,
            long durationMillis,
            long damagePauseMillis) {
    }

    private static final class PendingTarget {
        private final PassiveRuntimeState runtimeState;
        private final double multiplier;
        private double assignedBonus;

        private PendingTarget(PassiveRuntimeState runtimeState, double multiplier) {
            this.runtimeState = runtimeState;
            this.multiplier = multiplier;
            this.assignedBonus = 0.0D;
        }

        private PassiveRuntimeState runtimeState() {
            return runtimeState;
        }

        private double multiplier() {
            return multiplier;
        }

        private double assignedBonus() {
            return assignedBonus;
        }

        private void addBonus(double bonus) {
            assignedBonus = Math.max(0.0D, assignedBonus + Math.max(0.0D, bonus));
        }
    }
}
