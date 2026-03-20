package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
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
 * Applies periodic party shielding around the source player.
 */
public final class ShieldingAuraPassive {

    private static final long PULSE_INTERVAL_MILLIS = 2000L;
    private static final double DEFAULT_FLAT_SHIELD = 0.0D;
    private static final double DEFAULT_MANA_RATIO = 0.0D;
    private static final double DEFAULT_STAMINA_RATIO = 0.2D;
    private static final double DEFAULT_BASE_RADIUS = PartyHealingDistributor.DEFAULT_BASE_RADIUS_BLOCKS;
    private static final double DEFAULT_MANA_PER_BLOCK = PartyHealingDistributor.DEFAULT_MANA_PER_RADIUS_BLOCK;
    private static final double DEFAULT_SELF_SHIELD_EFFECTIVENESS = 0.25D;
    private static final double DEFAULT_DURATION_SECONDS = 10.0D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 25.0D;
    private static final ActivationMode DEFAULT_ACTIVATION_MODE = ActivationMode.ALWAYS;
    private static final double SHIELD_EPSILON = 0.0001D;

    private ShieldingAuraPassive() {
    }

    public static void pulse(PlayerData sourcePlayerData,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            ArchetypePassiveSnapshot archetypeSnapshot,
            PassiveRuntimeState sourceRuntimeState) {
        if (sourcePlayerData == null || sourceRef == null || commandBuffer == null
                || sourceStats == null || archetypeSnapshot == null || sourceRuntimeState == null) {
            return;
        }

        double passiveValue = archetypeSnapshot.getValue(ArchetypePassiveType.SHIELDING_AURA);
        if (passiveValue <= 0.0D) {
            return;
        }

        ShieldingAuraConfig config = resolveConfig(archetypeSnapshot);
        long now = System.currentTimeMillis();

        cleanupCasterStateForCooldown(sourceRuntimeState, now, config.cooldownMillis());

        if (sourceRuntimeState.getShieldingAuraActiveUntil() > now) {
            return;
        }
        if (sourceRuntimeState.getShieldingAuraCooldownExpiresAt() > now) {
            return;
        }

        long lastPulse = sourceRuntimeState.getShieldingAuraLastPulseMillis();
        if (config.activationMode() == ActivationMode.ON_HIT) {
            long lastDamageDealtMillis = sourceRuntimeState.getLastDamageDealtMillis();
            if (lastDamageDealtMillis <= 0L || lastDamageDealtMillis <= lastPulse) {
                return;
            }
        } else {
            if (lastPulse > 0L && now - lastPulse < PULSE_INTERVAL_MILLIS) {
                return;
            }
        }

        EntityStatValue sourceHealth = sourceStats.get(DefaultEntityStatTypes.getHealth());
        if (sourceHealth == null || sourceHealth.getMax() <= 0f || sourceHealth.get() <= 0f) {
            return;
        }

        EntityStatValue sourceMana = sourceStats.get(DefaultEntityStatTypes.getMana());
        EntityStatValue sourceStamina = sourceStats.get(DefaultEntityStatTypes.getStamina());
        double totalMana = sourceMana != null ? Math.max(0.0D, sourceMana.getMax()) : 0.0D;
        double totalStamina = sourceStamina != null ? Math.max(0.0D, sourceStamina.getMax()) : 0.0D;

        double shieldAmount = (config.flatShieldValue()
                + (totalMana * config.manaRatio())
                + (totalStamina * config.staminaRatio())) * passiveValue;
        if (shieldAmount <= 0.0D) {
            return;
        }

        List<PartyShieldTarget> targets = resolvePartyTargets(sourcePlayerData,
                sourceRef,
                commandBuffer,
                sourceStats,
                config.baseRadius(),
                config.manaPerBlock());
        if (targets.isEmpty()) {
            return;
        }

        long shieldExpiresAt = now + config.durationMillis();
        int appliedTargets = 0;
        for (PartyShieldTarget target : targets) {
            if (target == null || target.runtimeState() == null) {
                continue;
            }

            double effectiveShield = target.selfTarget()
                    ? shieldAmount * config.selfShieldEffectiveness()
                    : shieldAmount;
            if (effectiveShield <= 0.0D) {
                continue;
            }

            applyShield(target.runtimeState(), effectiveShield, shieldExpiresAt);
            appliedTargets++;
        }

        if (appliedTargets <= 0) {
            return;
        }

        sourceRuntimeState.setShieldingAuraLastPulseMillis(now);
        sourceRuntimeState.setShieldingAuraActiveUntil(shieldExpiresAt);
    }

    public static float absorbIncomingDamage(PassiveRuntimeState runtimeState, float incomingDamage) {
        if (runtimeState == null || incomingDamage <= 0f) {
            return Math.max(0f, incomingDamage);
        }

        long now = System.currentTimeMillis();
        cleanupExpiredShield(runtimeState, now);

        double shield = runtimeState.getShieldingAuraShieldAmount();
        if (shield <= SHIELD_EPSILON) {
            return incomingDamage;
        }

        double absorbed = Math.min(shield, incomingDamage);
        double remainingShield = shield - absorbed;
        runtimeState.setShieldingAuraShieldAmount(Math.max(0.0D, remainingShield));

        if (runtimeState.getShieldingAuraShieldAmount() <= SHIELD_EPSILON) {
            runtimeState.clearShieldingAuraShield();
        }

        float remainingDamage = (float) Math.max(0.0D, incomingDamage - absorbed);
        return remainingDamage;
    }

    public static void cleanupExpiredShield(PassiveRuntimeState runtimeState, long now) {
        if (runtimeState == null) {
            return;
        }

        long expiresAt = runtimeState.getShieldingAuraShieldExpiresAt();
        if (expiresAt <= 0L || now < expiresAt) {
            return;
        }

        runtimeState.clearShieldingAuraShield();
    }

    private static void cleanupCasterStateForCooldown(PassiveRuntimeState runtimeState, long now, long cooldownMillis) {
        if (runtimeState == null) {
            return;
        }

        long activeUntil = runtimeState.getShieldingAuraActiveUntil();
        if (activeUntil <= 0L || now < activeUntil) {
            return;
        }

        runtimeState.setShieldingAuraActiveUntil(0L);
        if (cooldownMillis > 0L) {
            runtimeState.setShieldingAuraCooldownExpiresAt(now + cooldownMillis);
        } else {
            runtimeState.setShieldingAuraCooldownExpiresAt(0L);
        }
    }

    private static void applyShield(PassiveRuntimeState runtimeState, double shieldAmount, long expiresAt) {
        if (runtimeState == null || shieldAmount <= 0.0D || expiresAt <= 0L) {
            return;
        }

        long currentExpiresAt = runtimeState.getShieldingAuraShieldExpiresAt();
        if (currentExpiresAt > 0L && currentExpiresAt < System.currentTimeMillis()) {
            runtimeState.clearShieldingAuraShield();
        }

        double nextAmount = Math.max(runtimeState.getShieldingAuraShieldAmount(), shieldAmount);
        double nextMax = Math.max(runtimeState.getShieldingAuraShieldMaxAmount(), shieldAmount);
        long nextExpiresAt = Math.max(runtimeState.getShieldingAuraShieldExpiresAt(), expiresAt);

        runtimeState.setShieldingAuraShieldAmount(nextAmount);
        runtimeState.setShieldingAuraShieldMaxAmount(nextMax);
        runtimeState.setShieldingAuraShieldExpiresAt(nextExpiresAt);
    }

    private static List<PartyShieldTarget> resolvePartyTargets(PlayerData sourcePlayerData,
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

        List<PartyShieldTarget> targets = new ArrayList<>();
        HashSet<Integer> visitedEntityIds = new HashSet<>();
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
            targets.add(new PartyShieldTarget(targetRuntimeState, selfTarget));
        }

        return targets;
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

    private static ShieldingAuraConfig resolveConfig(ArchetypePassiveSnapshot snapshot) {
        double flatShieldValue = DEFAULT_FLAT_SHIELD;
        double manaRatio = DEFAULT_MANA_RATIO;
        double staminaRatio = DEFAULT_STAMINA_RATIO;
        double baseRadius = DEFAULT_BASE_RADIUS;
        double manaPerBlock = DEFAULT_MANA_PER_BLOCK;
        double selfShieldEffectiveness = DEFAULT_SELF_SHIELD_EFFECTIVENESS;
        long durationMillis = secondsToMillis(DEFAULT_DURATION_SECONDS);
        long cooldownMillis = secondsToMillis(DEFAULT_COOLDOWN_SECONDS);
        ActivationMode activationMode = DEFAULT_ACTIVATION_MODE;

        if (snapshot == null) {
            return new ShieldingAuraConfig(flatShieldValue,
                    manaRatio,
                    staminaRatio,
                    baseRadius,
                    manaPerBlock,
                    selfShieldEffectiveness,
                    durationMillis,
                    cooldownMillis,
                    activationMode);
        }

        RacePassiveDefinition strongestDefinition = resolveStrongestDefinition(
                snapshot.getDefinitions(ArchetypePassiveType.SHIELDING_AURA));
        if (strongestDefinition == null || strongestDefinition.properties() == null
                || strongestDefinition.properties().isEmpty()) {
            return new ShieldingAuraConfig(flatShieldValue,
                    manaRatio,
                    staminaRatio,
                    baseRadius,
                    manaPerBlock,
                    selfShieldEffectiveness,
                    durationMillis,
                    cooldownMillis,
                    activationMode);
        }

        Map<String, Object> props = strongestDefinition.properties();
        flatShieldValue = parseNonNegative(props.get("flat_shield_value"), flatShieldValue);
        manaRatio = parseNonNegative(props.get("mana_ratio"), manaRatio);
        staminaRatio = parseNonNegative(props.get("stamina_ratio"), staminaRatio);
        baseRadius = parseNonNegative(props.get("radius"), baseRadius);

        Object radiusScalingRaw = props.get("radius_mana_scaling");
        if (radiusScalingRaw instanceof Map<?, ?> radiusScaling) {
            manaPerBlock = parsePositive(radiusScaling.get("mana_per_block"), manaPerBlock);
        }

        Object selfEffectRaw = firstNonNull(props.get("self_shield_effectiveness"), props.get("self_shield_ratio"));
        selfShieldEffectiveness = parseBoundedRatio(selfEffectRaw, selfShieldEffectiveness);

        double durationSeconds = parseNonNegative(props.get("duration"), DEFAULT_DURATION_SECONDS);
        double cooldownSeconds = parseNonNegative(props.get("cooldown"), DEFAULT_COOLDOWN_SECONDS);
        durationMillis = secondsToMillis(durationSeconds);
        cooldownMillis = secondsToMillis(cooldownSeconds);
        activationMode = parseActivationMode(firstNonNull(props.get("activation"), props.get("activation_mode")),
                activationMode);

        return new ShieldingAuraConfig(flatShieldValue,
                manaRatio,
                staminaRatio,
                baseRadius,
                manaPerBlock,
                selfShieldEffectiveness,
                durationMillis,
                cooldownMillis,
                activationMode);
    }

    private static ActivationMode parseActivationMode(Object raw, ActivationMode fallback) {
        if (raw instanceof String mode) {
            if ("on_hit".equalsIgnoreCase(mode.trim())) {
                return ActivationMode.ON_HIT;
            }
            if ("always".equalsIgnoreCase(mode.trim())) {
                return ActivationMode.ALWAYS;
            }
        }
        return fallback == null ? DEFAULT_ACTIVATION_MODE : fallback;
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

    private static double parseBoundedRatio(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return Math.max(0.0D, Math.min(1.0D, value));
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

    private record ShieldingAuraConfig(double flatShieldValue,
            double manaRatio,
            double staminaRatio,
            double baseRadius,
            double manaPerBlock,
            double selfShieldEffectiveness,
            long durationMillis,
            long cooldownMillis,
            ActivationMode activationMode) {
    }

    private enum ActivationMode {
        ALWAYS,
        ON_HIT
    }

    private record PartyShieldTarget(PassiveRuntimeState runtimeState, boolean selfTarget) {
    }
}
