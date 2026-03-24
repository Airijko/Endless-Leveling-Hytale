package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Necromancer summon behavior for ARMY_OF_THE_DEAD.
 */
public final class ArmyOfTheDeadPassive {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private static final String DEFAULT_SKELETON_TYPE = "EndlessLeveling_ArmyOfTheDead_Pet_Copper";
    private static final int DEFAULT_BASE_SUMMON_AMOUNT = 2;
    private static final double DEFAULT_BASE_SUMMON_DAMAGE = 5.0D;
    private static final double DEFAULT_MANA_PER_SUMMON = 100.0D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 15.0D;
    private static final double DEFAULT_LIFETIME_SECONDS = 30.0D;
    private static final double DEFAULT_STAT_INHERITANCE = 0.10D;
    private static final int MAX_SUMMON_CAP = 64;
    private static final double TELEPORT_RANGE = 32.0D;
    private static final double TELEPORT_RANGE_SQ = TELEPORT_RANGE * TELEPORT_RANGE;
    private static final float MIN_RESOURCE_VALUE = 1.0f;
    private static final boolean DEBUG_SUMMON_INHERITANCE = false;
    private static final boolean DEBUG_SUMMON_HEALTH = false;
    private static final boolean DEBUG_SUMMON_NAMEPLATE = true;
    private static final long INHERITANCE_DEBUG_LOG_COOLDOWN_MS = 5000L;
    private static final long NAMEPLATE_REFRESH_INTERVAL_MS = 2000L;
    private static final long NAMEPLATE_FAILURE_LOG_COOLDOWN_MS = 5000L;
    private static final double AUTO_AGGRO_SCAN_RANGE = 24.0D;

    private static final Map<UUID, OwnerSummonState> OWNER_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, SummonBinding> SUMMON_BINDINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PENDING_ON_HIT_TRIGGERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> INHERITANCE_DEBUG_LAST_LOG = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NAMEPLATE_FAILURE_LAST_LOG = new ConcurrentHashMap<>();

    private ArmyOfTheDeadPassive() {
    }

    public static void markOnHitTrigger(PlayerData sourcePlayerData,
            ArchetypePassiveSnapshot archetypeSnapshot) {
        if (sourcePlayerData == null || archetypeSnapshot == null) {
            return;
        }

        double passiveValue = archetypeSnapshot.getValue(ArchetypePassiveType.ARMY_OF_THE_DEAD);
        if (passiveValue <= 0.0D) {
            return;
        }

        ArmyOfTheDeadConfig config = resolveConfig(archetypeSnapshot);
        if (!config.onHitActivation()) {
            return;
        }

        UUID ownerUuid = sourcePlayerData.getUuid();
        if (ownerUuid == null) {
            return;
        }
        PENDING_ON_HIT_TRIGGERS.put(ownerUuid, System.currentTimeMillis());
    }

    public static void processPendingOnHit(PlayerData sourcePlayerData,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            ArchetypePassiveSnapshot archetypeSnapshot) {
        if (sourcePlayerData == null) {
            return;
        }
        UUID ownerUuid = sourcePlayerData.getUuid();
        if (ownerUuid == null) {
            return;
        }
        if (PENDING_ON_HIT_TRIGGERS.remove(ownerUuid) == null) {
            return;
        }
        try {
            triggerOnHitNow(sourcePlayerData, sourceRef, commandBuffer, sourceStats, archetypeSnapshot);
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable)
                    .log("[ARMY_OF_THE_DEAD] Failed to process pending summon trigger for %s", ownerUuid);
        }
    }

    public static void focusCurrentTarget(PlayerData sourcePlayerData,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer) {
        if (sourcePlayerData == null || targetRef == null || commandBuffer == null
                || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        UUID ownerUuid = sourcePlayerData.getUuid();
        if (ownerUuid == null) {
            return;
        }

        focusOwnerSummonsOnThreat(ownerUuid, targetRef, EntityRefUtil.getStore(targetRef), commandBuffer);
    }

    public static void focusSummonsOnSummonAttacker(Ref<EntityStore> attackedSummonRef,
            Ref<EntityStore> attackerRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(attackedSummonRef) || !EntityRefUtil.isUsable(attackerRef)
                || commandBuffer == null) {
            return;
        }

        UUID ownerUuid = resolveSummonOwnerUuid(attackedSummonRef, store, commandBuffer);
        if (ownerUuid == null) {
            return;
        }

        focusOwnerSummonsOnThreat(ownerUuid, attackerRef, store, commandBuffer);
    }

    private static void focusOwnerSummonsOnThreat(UUID ownerUuid,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ownerUuid == null || !EntityRefUtil.isUsable(targetRef) || commandBuffer == null) {
            return;
        }

        if (!isValidAggroTargetForOwner(ownerUuid, targetRef, store, commandBuffer)) {
            return;
        }

        OwnerSummonState ownerState = OWNER_STATES.get(ownerUuid);
        if (ownerState == null) {
            return;
        }

        synchronized (ownerState) {
            for (SummonSlot slot : ownerState.slots) {
                if (slot == null || !EntityRefUtil.isUsable(slot.activeRef)) {
                    continue;
                }

                NPCEntity summonNpc = EntityRefUtil.tryGetComponent(commandBuffer,
                        slot.activeRef,
                        NPCEntity.getComponentType());
                if (summonNpc == null || summonNpc.getRole() == null) {
                    continue;
                }

                try {
                    summonNpc.getRole().setMarkedTarget("LockedTarget", targetRef);
                } catch (Throwable throwable) {
                    LOGGER.atFiner().withCause(throwable)
                            .log("[ARMY_OF_THE_DEAD] Failed to retarget summon for owner %s.", ownerUuid);
                }
            }
        }
    }

    private static boolean isValidAggroTargetForOwner(UUID ownerUuid,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ownerUuid == null || !EntityRefUtil.isUsable(targetRef)) {
            return false;
        }

        Store<EntityStore> effectiveStore = store != null ? store : EntityRefUtil.getStore(targetRef);
        UUID targetPlayerUuid = resolvePlayerUuid(targetRef, effectiveStore, commandBuffer);
        if (targetPlayerUuid != null && isAlliedWithOwner(ownerUuid, targetPlayerUuid)) {
            return false;
        }

        UUID targetOwner = resolveSummonOwnerUuid(targetRef, effectiveStore, commandBuffer);
        if (targetOwner != null && areAlliedOwners(ownerUuid, targetOwner)) {
            return false;
        }

        return true;
    }

    public static boolean isManagedSummon(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        return resolveSummonOwnerUuid(ref, store, commandBuffer) != null;
    }

    public static UUID getManagedSummonOwnerUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        return resolveSummonOwnerUuid(ref, store, commandBuffer);
    }

    public static SummonHudState resolveHudState(PlayerData sourcePlayerData,
            EntityStatMap sourceStats,
            ArchetypePassiveSnapshot archetypeSnapshot) {
        if (sourcePlayerData == null || archetypeSnapshot == null) {
            return SummonHudState.none();
        }

        double passiveValue = archetypeSnapshot.getValue(ArchetypePassiveType.ARMY_OF_THE_DEAD);
        if (passiveValue <= 0.0D && archetypeSnapshot.getDefinitions(ArchetypePassiveType.ARMY_OF_THE_DEAD).isEmpty()) {
            return SummonHudState.none();
        }

        ArmyOfTheDeadConfig config = resolveConfig(archetypeSnapshot);
        int maxSummons = resolveMaxSummons(config, sourcePlayerData, sourceStats);
        if (maxSummons <= 0) {
            return SummonHudState.none();
        }

        UUID ownerUuid = sourcePlayerData.getUuid();
        if (ownerUuid == null) {
            return new SummonHudState(0, maxSummons, maxSummons);
        }

        OwnerSummonState ownerState = OWNER_STATES.get(ownerUuid);
        if (ownerState == null) {
            return new SummonHudState(0, maxSummons, maxSummons);
        }

        long now = System.currentTimeMillis();
        synchronized (ownerState) {
            ownerState.ensureSlots(maxSummons);

            int deployedCount = 0;
            int availableCount = 0;
            for (int slotIndex = 0; slotIndex < maxSummons; slotIndex++) {
                SummonSlot slot = ownerState.getSlot(slotIndex);
                if (slot == null) {
                    availableCount++;
                    continue;
                }

                if (isSummonSlotDeployed(slot, now)) {
                    deployedCount++;
                    continue;
                }

                if (!slot.spawnPending && slot.cooldownExpiresAt <= now) {
                    availableCount++;
                }
            }

            return new SummonHudState(deployedCount, availableCount, maxSummons);
        }
    }

    public static double getManagedSummonStatInheritance(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return 0.0D;
        }

        UUID summonUuid = resolveEntityUuid(ref, store, commandBuffer);
        if (summonUuid == null) {
            return 0.0D;
        }

        SummonBinding binding = SUMMON_BINDINGS.get(summonUuid);
        if (binding == null) {
            return 0.0D;
        }

        OwnerSummonState ownerState = OWNER_STATES.get(binding.ownerUuid());
        if (ownerState == null) {
            return 0.0D;
        }

        synchronized (ownerState) {
            SummonSlot slot = ownerState.getSlot(binding.slotIndex());
            if (slot == null) {
                return 0.0D;
            }
            return Math.max(0.0D, slot.statInheritance);
        }
    }

    public static double getManagedSummonBaseDamage(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return 0.0D;
        }

        UUID summonUuid = resolveEntityUuid(ref, store, commandBuffer);
        if (summonUuid == null) {
            return 0.0D;
        }

        SummonBinding binding = SUMMON_BINDINGS.get(summonUuid);
        if (binding == null) {
            return 0.0D;
        }

        OwnerSummonState ownerState = OWNER_STATES.get(binding.ownerUuid());
        if (ownerState == null) {
            return 0.0D;
        }

        synchronized (ownerState) {
            SummonSlot slot = ownerState.getSlot(binding.slotIndex());
            if (slot == null) {
                return 0.0D;
            }
            return Math.max(0.0D, slot.baseDamage);
        }
    }

    public static SummonInheritedStats resolveManagedSummonInheritedStats(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(summonRef)) {
            return SummonInheritedStats.none();
        }

        UUID ownerUuid = resolveSummonOwnerUuid(summonRef, store, commandBuffer);
        if (ownerUuid == null) {
            return SummonInheritedStats.none();
        }

        double inheritance = getManagedSummonStatInheritance(summonRef, store, commandBuffer);
        return resolveOwnerSummonInheritedStats(ownerUuid, inheritance);
    }

    public static SummonInheritedStats resolveOwnerSummonInheritedStats(UUID ownerUuid,
            double inheritanceRaw) {
        if (ownerUuid == null) {
            return SummonInheritedStats.none();
        }

        double inheritance = Math.max(0.0D, inheritanceRaw);
        if (inheritance <= 0.0D) {
            return SummonInheritedStats.none();
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return SummonInheritedStats.none();
        }

        PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
        SkillManager skillManager = plugin.getSkillManager();
        if (playerDataManager == null || skillManager == null) {
            return SummonInheritedStats.none();
        }

        PlayerData ownerData = playerDataManager.get(ownerUuid);
        if (ownerData == null) {
            return SummonInheritedStats.none();
        }

        double strength = Math.max(0.0D, skillManager.calculatePlayerStrength(ownerData));
        double sorcery = Math.max(0.0D, skillManager.calculatePlayerSorcery(ownerData));
        double combinedDamagePercent = strength + sorcery;

        SkillManager.PrecisionBreakdown precision = skillManager.getPrecisionBreakdown(ownerData);
        double precisionPercent = precision != null ? Math.max(0.0D, precision.totalPercent()) : 0.0D;

        SkillManager.FerocityBreakdown ferocity = skillManager.getFerocityBreakdown(ownerData);
        double ferocityPercent = ferocity != null ? Math.max(0.0D, ferocity.totalValue()) : 0.0D;

        SkillManager.DefenseBreakdown defense = skillManager.getDefenseBreakdown(ownerData);
        double defenseResistance = defense != null ? Math.max(0.0D, defense.resistance()) : 0.0D;

        SkillManager.HasteBreakdown haste = skillManager.getHasteBreakdown(ownerData);
        double hasteMultiplier = haste != null ? Math.max(0.0D, haste.totalMultiplier()) : 1.0D;

        double lifeForceFlat = Math.max(0.0D,
                skillManager.calculateSkillAttributeTotalBonus(ownerData, SkillAttributeType.LIFE_FORCE, -1));

        float damageMultiplier = (float) Math.max(0.0D, 1.0D + ((combinedDamagePercent * inheritance) / 100.0D));
        float critChance = (float) Math.max(0.0D,
                Math.min(1.0D, ((precisionPercent * inheritance) / 100.0D)));
        float critDamageMultiplier = (float) Math.max(1.0D, 1.0D + ((ferocityPercent * inheritance) / 100.0D));
        float defenseReduction = (float) Math.max(0.0D,
                Math.min(0.95D, defenseResistance * inheritance));
        float movementMultiplier = (float) Math.max(0.0D,
                1.0D + ((Math.max(0.0D, hasteMultiplier - 1.0D) * inheritance)));
        float lifeForceFlatHealthBonus = (float) Math.max(0.0D, lifeForceFlat * inheritance);

        if (DEBUG_SUMMON_INHERITANCE) {
            logInheritanceComputation(ownerUuid,
                    inheritance,
                    strength,
                    sorcery,
                    precisionPercent,
                    ferocityPercent,
                    defenseResistance,
                    hasteMultiplier,
                    lifeForceFlat,
                    damageMultiplier,
                    critChance,
                    critDamageMultiplier,
                    defenseReduction,
                    movementMultiplier,
                    lifeForceFlatHealthBonus);
        }

        return new SummonInheritedStats(damageMultiplier,
                critChance,
                critDamageMultiplier,
                defenseReduction,
                movementMultiplier,
                lifeForceFlatHealthBonus);
    }

    public static boolean shouldPreventFriendlyDamage(Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        return false;
    }

    public static boolean isFriendlyToOwner(UUID ownerUuid,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ownerUuid == null || !EntityRefUtil.isUsable(targetRef)) {
            return false;
        }

        UUID targetPlayerUuid = resolvePlayerUuid(targetRef, store, commandBuffer);
        if (targetPlayerUuid != null && isAlliedWithOwner(ownerUuid, targetPlayerUuid)) {
            return true;
        }

        UUID targetOwner = resolveSummonOwnerUuid(targetRef, store, commandBuffer);
        return targetOwner != null && areAlliedOwners(ownerUuid, targetOwner);
    }

    private static void triggerOnHitNow(PlayerData sourcePlayerData,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            ArchetypePassiveSnapshot archetypeSnapshot) {
        if (sourcePlayerData == null
                || sourceRef == null
                || commandBuffer == null
                || sourceStats == null
                || archetypeSnapshot == null
                || !EntityRefUtil.isUsable(sourceRef)) {
            return;
        }

        ArmyOfTheDeadConfig config = resolveConfig(archetypeSnapshot);
        if (!config.onHitActivation()) {
            return;
        }

        UUID ownerUuid = sourcePlayerData.getUuid();
        if (ownerUuid == null) {
            return;
        }

        int maxSummons = resolveMaxSummons(config, sourcePlayerData, sourceStats);
        if (maxSummons <= 0) {
            return;
        }

        OwnerSummonState ownerState = OWNER_STATES.computeIfAbsent(ownerUuid, ignored -> new OwnerSummonState());
        long now = System.currentTimeMillis();

        SummonSourceSnapshot sourceSnapshot = captureSourceSnapshot(sourceRef, commandBuffer, sourceStats,
                sourcePlayerData);
        if (sourceSnapshot == null) {
            return;
        }

        List<QueuedSpawnRequest> queuedRequests = new ArrayList<>();
        synchronized (ownerState) {
            ownerState.ensureSlots(maxSummons);

            for (int slotIndex = 0; slotIndex < maxSummons; slotIndex++) {
                SummonSlot slot = ownerState.getSlot(slotIndex);
                if (slot == null) {
                    continue;
                }
                cleanupSlot(ownerUuid, slot, now, commandBuffer, sourceRef);
            }

            int queuedSpawns = 0;
            for (int slotIndex = 0; slotIndex < maxSummons; slotIndex++) {
                SummonSlot slot = ownerState.getSlot(slotIndex);
                if (slot == null || slot.spawnPending || slot.activeSummonUuid != null
                        || now < slot.cooldownExpiresAt) {
                    continue;
                }

                slot.cooldownDurationMillis = config.cooldownMillis();
                slot.spawnPending = true;
                queuedRequests.add(new QueuedSpawnRequest(ownerUuid, slotIndex, now, config, sourceSnapshot));
                queuedSpawns++;
            }

            if (queuedSpawns > 0) {
                LOGGER.atFine().log(
                        "[ARMY_OF_THE_DEAD] Queued %d summon spawns for %s (max=%d, base=%d, manaPerSummon=%.2f, type=%s).",
                        queuedSpawns,
                        ownerUuid,
                        maxSummons,
                        config.baseSummonAmount(),
                        config.manaPerSummon(),
                        config.skeletonType());
            }
        }

        if (!queuedRequests.isEmpty()) {
            queueWorldSpawnRequests(queuedRequests);
        }
    }

    public static void handleDeath(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || store == null) {
            return;
        }

        try {

            UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer,
                    ref,
                    UUIDComponent.getComponentType());
            if (uuidComponent == null) {
                uuidComponent = EntityRefUtil.tryGetComponent(store,
                        ref,
                        UUIDComponent.getComponentType());
            }
            if (uuidComponent == null || uuidComponent.getUuid() == null) {
                return;
            }

            UUID summonUuid = uuidComponent.getUuid();
            SummonBinding binding = SUMMON_BINDINGS.remove(summonUuid);
            if (binding == null) {
                return;
            }

            OwnerSummonState ownerState = OWNER_STATES.get(binding.ownerUuid());
            if (ownerState == null) {
                return;
            }

            long now = System.currentTimeMillis();
            synchronized (ownerState) {
                SummonSlot slot = ownerState.getSlot(binding.slotIndex());
                if (slot == null) {
                    return;
                }

                if (slot.activeSummonUuid != null && slot.activeSummonUuid.equals(summonUuid)) {
                    long cooldownUntil = now + Math.max(0L, slot.cooldownDurationMillis);
                    slot.activeRef = null;
                    slot.activeSummonUuid = null;
                    slot.summonExpiresAt = 0L;
                    slot.postSpawnHealthNormalizePending = false;
                    slot.nextNameplateRefreshAt = 0L;
                    slot.cooldownExpiresAt = cooldownUntil;
                    LOGGER.atFine().log(
                            "[ARMY_OF_THE_DEAD] Summon %s died. Cooldown set for owner %s slot %d until %d.",
                            summonUuid,
                            binding.ownerUuid(),
                            binding.slotIndex(),
                            cooldownUntil);
                }
            }
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable)
                    .log("[ARMY_OF_THE_DEAD] Failed to handle summon death event.");
        }
    }

    public static void cleanupOwnerSummonsOnDisconnect(UUID ownerUuid, Store<EntityStore> ownerStore) {
        cleanupOwnerSummons(ownerUuid, ownerStore, true, System.currentTimeMillis());
    }

    public static void cleanupPersistentSummons(Store<EntityStore> store) {
        if (store == null || store.isShutdown() || OWNER_STATES.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, OwnerSummonState> entry : OWNER_STATES.entrySet()) {
            UUID ownerUuid = entry.getKey();
            OwnerSummonState ownerState = entry.getValue();
            if (ownerUuid == null || ownerState == null) {
                continue;
            }

            PlayerRef ownerPlayerRef = Universe.get().getPlayer(ownerUuid);
            Ref<EntityStore> ownerRef = ownerPlayerRef != null && ownerPlayerRef.isValid()
                    ? ownerPlayerRef.getReference()
                    : null;
            Store<EntityStore> ownerStore = ownerRef != null ? ownerRef.getStore() : null;
            boolean ownerDisconnected = ownerPlayerRef == null || !ownerPlayerRef.isValid() || ownerRef == null;
            cleanupOwnerSummons(ownerUuid,
                    store,
                    ownerDisconnected,
                    now,
                    ownerStore,
                    ownerState);

            if (!ownerDisconnected) {
                autoRetargetIdleSummons(ownerUuid, ownerState, store);
            }
        }
    }

    private static void autoRetargetIdleSummons(UUID ownerUuid,
            OwnerSummonState ownerState,
            Store<EntityStore> store) {
        if (ownerUuid == null || ownerState == null || store == null) {
            return;
        }

        synchronized (ownerState) {
            for (SummonSlot slot : ownerState.slots) {
                if (slot == null || !EntityRefUtil.isUsable(slot.activeRef)) {
                    continue;
                }

                Store<EntityStore> summonStore = EntityRefUtil.getStore(slot.activeRef);
                if (summonStore != store) {
                    continue;
                }

                NPCEntity summonNpc = EntityRefUtil.tryGetComponent(store,
                        slot.activeRef,
                        NPCEntity.getComponentType());
                if (summonNpc == null || summonNpc.getRole() == null) {
                    continue;
                }

                Ref<EntityStore> currentTarget = summonNpc.getRole()
                        .getMarkedEntitySupport()
                        .getMarkedEntityRef("LockedTarget");
                if (EntityRefUtil.isUsable(currentTarget)) {
                    continue;
                }

                TransformComponent transform = EntityRefUtil.tryGetComponent(store,
                        slot.activeRef,
                        TransformComponent.getComponentType());
                if (transform == null || transform.getPosition() == null) {
                    continue;
                }

                for (Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInSphere(
                        transform.getPosition(), AUTO_AGGRO_SCAN_RANGE, store)) {
                    if (!EntityRefUtil.isUsable(candidate)) {
                        continue;
                    }
                    if (candidate.equals(slot.activeRef)) {
                        continue;
                    }
                    if (!isValidAggroTargetForOwner(ownerUuid, candidate, store, null)) {
                        continue;
                    }

                    try {
                        summonNpc.getRole().setMarkedTarget("LockedTarget", candidate);
                    } catch (Throwable throwable) {
                        LOGGER.atFiner().withCause(throwable)
                                .log("[ARMY_OF_THE_DEAD] Auto-retarget failed for summon of owner %s.", ownerUuid);
                    }
                    break;
                }
            }
        }
    }

    private static void queueWorldSpawnRequests(List<QueuedSpawnRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }

        for (QueuedSpawnRequest request : requests) {
            if (request == null || request.source() == null || request.source().worldId() == null) {
                continue;
            }

            World world = Universe.get().getWorld(request.source().worldId());
            if (world == null) {
                clearPendingSlot(request.ownerUuid(), request.slotIndex());
                continue;
            }

            try {
                world.execute(() -> spawnQueuedRequest(world, request));
            } catch (Exception exception) {
                clearPendingSlot(request.ownerUuid(), request.slotIndex());
                LOGGER.atWarning().withCause(exception)
                        .log("[ARMY_OF_THE_DEAD] Failed to enqueue world spawn for %s slot %d.",
                                request.ownerUuid(),
                                request.slotIndex());
            }
        }
    }

    private static void spawnQueuedRequest(World world, QueuedSpawnRequest request) {
        if (world == null || request == null || request.source() == null || NPCPlugin.get() == null) {
            if (request != null) {
                clearPendingSlot(request.ownerUuid(), request.slotIndex());
            }
            return;
        }

        OwnerSummonState ownerState = OWNER_STATES.get(request.ownerUuid());
        if (ownerState == null) {
            return;
        }

        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            clearPendingSlot(request.ownerUuid(), request.slotIndex());
            return;
        }

        synchronized (ownerState) {
            SummonSlot slot = ownerState.getSlot(request.slotIndex());
            if (slot == null || !slot.spawnPending || slot.activeSummonUuid != null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (slot.cooldownExpiresAt > now) {
                slot.spawnPending = false;
                return;
            }

            try {
                String spawnRoleType = resolveSpawnRoleType(request.config().skeletonType());
                if (spawnRoleType == null || spawnRoleType.isBlank()) {
                    slot.spawnPending = false;
                    return;
                }

                Vector3d spawnPosition = resolveSummonSpawnPosition(
                        request.source().position(),
                        request.ownerUuid(),
                        request.slotIndex());

                var spawned = NPCPlugin.get().spawnNPC(store,
                        spawnRoleType,
                        null,
                        spawnPosition,
                        request.source().rotation());
                if (spawned == null || !EntityRefUtil.isUsable(spawned.first())) {
                    slot.spawnPending = false;
                    LOGGER.atWarning().log(
                            "[ARMY_OF_THE_DEAD] SpawnNPC returned no active entity for owner %s slot %d type=%s.",
                            request.ownerUuid(),
                            request.slotIndex(),
                            spawnRoleType);
                    return;
                }

                Ref<EntityStore> summonRef = spawned.first();
                UUIDComponent summonUuidComponent = EntityRefUtil.tryGetComponent(store,
                        summonRef,
                        UUIDComponent.getComponentType());
                if (summonUuidComponent == null || summonUuidComponent.getUuid() == null) {
                    slot.spawnPending = false;
                    LOGGER.atWarning().log(
                            "[ARMY_OF_THE_DEAD] Spawned summon missing UUID for owner %s slot %d type=%s.",
                            request.ownerUuid(),
                            request.slotIndex(),
                            spawnRoleType);
                    return;
                }

                attachSummonToOwnerFlock(request.source().ownerRef(), summonRef, store,
                        spawnRoleType);

                long expiresAt = request.config().lifetimeMillis() > 0L
                        ? Math.max(now, request.triggeredAtMillis()) + request.config().lifetimeMillis()
                        : 0L;
                slot.activeRef = summonRef;
                slot.activeSummonUuid = summonUuidComponent.getUuid();
                slot.summonExpiresAt = expiresAt;
                slot.statInheritance = Math.max(0.0D, request.config().statInheritance());
                slot.baseDamage = Math.max(0.0D, request.config().baseDamage());
                slot.spawnPending = false;
                slot.postSpawnHealthNormalizePending = true;
                slot.nextNameplateRefreshAt = now + NAMEPLATE_REFRESH_INTERVAL_MS;
                SUMMON_BINDINGS.put(summonUuidComponent.getUuid(),
                        new SummonBinding(request.ownerUuid(), request.slotIndex()));

                applySummonScaling(summonRef,
                        store,
                        request.source(),
                        request.config().statInheritance(),
                        request.slotIndex());

                forceCurrentHealthToMax(summonRef, store);
                applySummonNameplate(summonRef, store, request.ownerUuid(), true);
                logSummonHealthState(summonRef,
                        store,
                        request.ownerUuid(),
                        request.slotIndex(),
                        slot.statInheritance,
                        "POST_SPAWN_NORMALIZE");
                LOGGER.atFine().log(
                        "[ARMY_OF_THE_DEAD] Activated summon %s for owner %s slot %d type=%s.",
                        summonUuidComponent.getUuid(),
                        request.ownerUuid(),
                        request.slotIndex(),
                        spawnRoleType);
                if (DEBUG_SUMMON_INHERITANCE) {
                    SummonInheritedStats inherited = resolveOwnerSummonInheritedStats(request.ownerUuid(),
                            slot.statInheritance);
                    LOGGER.atInfo().log(
                            "[ARMY_OF_THE_DEAD][DEBUG] Summon activated summon=%s owner=%s slot=%d inheritance=%.3f dmgMult=%.3f critChance=%.3f critDmgMult=%.3f defenseReduction=%.3f moveMult=%.3f lifeForceHp=%.3f",
                            summonUuidComponent.getUuid(),
                            request.ownerUuid(),
                            request.slotIndex(),
                            slot.statInheritance,
                            inherited.damageMultiplier(),
                            inherited.critChance(),
                            inherited.critDamageMultiplier(),
                            inherited.defenseReduction(),
                            inherited.movementMultiplier(),
                            inherited.lifeForceFlatHealthBonus());
                }
            } catch (Throwable throwable) {
                slot.spawnPending = false;
                LOGGER.atWarning().withCause(throwable)
                        .log("[ARMY_OF_THE_DEAD] Failed queued spawn for %s slot %d.",
                                request.ownerUuid(),
                                request.slotIndex());
            }
        }
    }

    private static String resolveSpawnRoleType(String preferredRoleType) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return preferredRoleType;
        }

        String roleType = preferredRoleType == null || preferredRoleType.isBlank()
                ? DEFAULT_SKELETON_TYPE
                : preferredRoleType.trim();
        try {
            npcPlugin.validateSpawnableRole(roleType);
            return roleType;
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable)
                    .log("[ARMY_OF_THE_DEAD] Spawn role '%s' failed validation before spawn.", roleType);
            return null;
        }
    }

    private static void applySummonScaling(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            SummonSourceSnapshot source,
            double inheritance,
            int slotIndex) {
        if (summonRef == null || store == null || source == null) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(store, summonRef, EntityStatMap.getComponentType());
        if (summonStats == null) {
            return;
        }

        SummonInheritedStats inheritedStats = resolveOwnerSummonInheritedStats(source.ownerUuid(), inheritance);
        double healthBonus = Math.max(0.0D, inheritedStats.lifeForceFlatHealthBonus());
        String modifierKey = "EL_AOTD_HP_" + slotIndex;
        summonStats.removeModifier(DefaultEntityStatTypes.getHealth(), modifierKey);
        if (healthBonus > 0.0D) {
            summonStats.putModifier(DefaultEntityStatTypes.getHealth(),
                    modifierKey,
                    new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, (float) healthBonus));
        }
        summonStats.update();

        EntityStatValue updatedHp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (updatedHp != null) {
            summonStats.setStatValue(DefaultEntityStatTypes.getHealth(),
                    Math.max(MIN_RESOURCE_VALUE, updatedHp.getMax()));
            summonStats.update();
            if (DEBUG_SUMMON_HEALTH) {
                UUID summonUuid = resolveEntityUuid(summonRef, store, null);
                LOGGER.atInfo().log(
                        "[ARMY_OF_THE_DEAD][DEBUG-HP][SCALE] summon=%s owner=%s slot=%d inheritance=%.3f healthBonus=%.3f afterScale=%.3f/%.3f",
                        summonUuid,
                        source.ownerUuid(),
                        slotIndex,
                        inheritance,
                        healthBonus,
                        updatedHp.get(),
                        updatedHp.getMax());
            }
        }


        // Apply player augments to the summon
        UUID summonUuid = resolveEntityUuid(summonRef, store, null);
        if (summonUuid != null) {
            applyPlayerAugmentsToSummon(summonRef, store, source.ownerUuid(), summonUuid, slotIndex);
        }
        applySummonMovementSpeedBonus(summonRef, store, inheritedStats.movementMultiplier());
    }

    private static void forceCurrentHealthToMax(Ref<EntityStore> summonRef,
            Store<EntityStore> store) {
        if (!EntityRefUtil.isUsable(summonRef) || store == null) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(store, summonRef, EntityStatMap.getComponentType());
        if (summonStats == null) {
            return;
        }

        EntityStatValue hp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || !Float.isFinite(hp.getMax()) || hp.getMax() <= 0.0f) {
            return;
        }

        float beforeCurrent = hp.get();
        float beforeMax = hp.getMax();
        summonStats.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(MIN_RESOURCE_VALUE, hp.getMax()));
        summonStats.update();

        EntityStatValue after = summonStats.get(DefaultEntityStatTypes.getHealth());
        UUID summonUuid = resolveEntityUuid(summonRef, store, null);
        if (after != null && DEBUG_SUMMON_HEALTH) {
            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-HP][FORCE_MAX] summon=%s before=%.3f/%.3f after=%.3f/%.3f",
                    summonUuid,
                    beforeCurrent,
                    beforeMax,
                    after.get(),
                    after.getMax());
        }
    }

    private static void logSummonHealthState(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            int slotIndex,
            double inheritance,
            String phase) {
        if (!DEBUG_SUMMON_HEALTH) {
            return;
        }
        if (!EntityRefUtil.isUsable(summonRef) || store == null) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(store, summonRef, EntityStatMap.getComponentType());
        if (summonStats == null) {
            return;
        }

        EntityStatValue hp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null) {
            return;
        }

        UUID summonUuid = resolveEntityUuid(summonRef, store, null);
        LOGGER.atInfo().log(
                "[ARMY_OF_THE_DEAD][DEBUG-HP][%s] summon=%s owner=%s slot=%d inheritance=%.3f current=%.3f max=%.3f",
                phase,
                summonUuid,
                ownerUuid,
                slotIndex,
                inheritance,
                hp.get(),
                hp.getMax());
    }

    private static void applyPlayerAugmentsToSummon(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            UUID summonUuid,
            int slotIndex) {
        if (summonRef == null || store == null || ownerUuid == null || summonUuid == null) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] Cannot apply augments: missing required parameters for slot %d owner=%s summon=%s",
                    slotIndex,
                    ownerUuid,
                    summonUuid);
            return;
        }

        // Get player data
        PlayerDataManager playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        if (playerDataManager == null) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] PlayerDataManager not available for slot %d owner=%s",
                    slotIndex,
                    ownerUuid);
            return;
        }

        PlayerData playerData = playerDataManager.get(ownerUuid);
        if (playerData == null) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] No player data found for owner=%s slot=%d",
                    ownerUuid,
                    slotIndex);
            return;
        }

        // Get selected augments
        Map<String, String> selectedAugmentsMap = playerData.getSelectedAugmentsSnapshot();
        if (selectedAugmentsMap == null || selectedAugmentsMap.isEmpty()) {
            LOGGER.atFine().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] No augments selected for owner=%s slot=%d summon=%s",
                    ownerUuid,
                    slotIndex,
                    summonUuid);
            return;
        }

        List<String> augmentIds = new ArrayList<>(selectedAugmentsMap.values());

        // Get augment managers
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] EndlessLeveling plugin not available for slot %d owner=%s",
                    slotIndex,
                    ownerUuid);
            return;
        }

        MobAugmentExecutor mobAugmentExecutor = plugin.getMobAugmentExecutor();
        var augmentManager = plugin.getAugmentManager();
        var augmentRuntimeManager = plugin.getAugmentRuntimeManager();

        if (mobAugmentExecutor == null || augmentManager == null || augmentRuntimeManager == null) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] Missing augment executor/manager for slot %d owner=%s summon=%s",
                    slotIndex,
                    ownerUuid,
                    summonUuid);
            return;
        }

        try {
            LOGGER.atFine().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS][DEBUG] Registering %d augments to summon at slot %d: owner=%s summon=%s augments=%s",
                    augmentIds.size(),
                    slotIndex,
                    ownerUuid,
                    summonUuid,
                    augmentIds);

            mobAugmentExecutor.registerMobAugments(summonUuid, augmentIds, augmentManager, augmentRuntimeManager);

            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] ✓ Successfully registered %d augments to summon at slot %d owner=%s summon=%s",
                    augmentIds.size(),
                    slotIndex,
                    ownerUuid,
                    summonUuid);
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log(
                    "[ARMY_OF_THE_DEAD][AUGMENTS] Failed to register augments for slot %d owner=%s summon=%s: %s",
                    slotIndex,
                    ownerUuid,
                    summonUuid,
                    e.getMessage());
        }
    }

    private static void applySummonMovementSpeedBonus(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            float requestedMultiplier) {
        if (!EntityRefUtil.isUsable(summonRef) || store == null || requestedMultiplier <= 0.0f) {
            return;
        }

        MovementManager movementManager = EntityRefUtil.tryGetComponent(store, summonRef,
                MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }

        MovementSettings defaults = movementManager.getDefaultSettings();
        MovementSettings settings = movementManager.getSettings();
        if (defaults == null || settings == null) {
            return;
        }

        float clampedMultiplier = requestedMultiplier;
        if (settings.maxSpeedMultiplier > 0.0F) {
            clampedMultiplier = Math.min(clampedMultiplier, settings.maxSpeedMultiplier);
        }
        if (settings.minSpeedMultiplier > 0.0F) {
            clampedMultiplier = Math.max(clampedMultiplier, settings.minSpeedMultiplier);
        }

        settings.forwardWalkSpeedMultiplier = defaults.forwardWalkSpeedMultiplier * clampedMultiplier;
        settings.backwardWalkSpeedMultiplier = defaults.backwardWalkSpeedMultiplier * clampedMultiplier;
        settings.strafeWalkSpeedMultiplier = defaults.strafeWalkSpeedMultiplier * clampedMultiplier;
        settings.forwardRunSpeedMultiplier = defaults.forwardRunSpeedMultiplier * clampedMultiplier;
        settings.backwardRunSpeedMultiplier = defaults.backwardRunSpeedMultiplier * clampedMultiplier;
        settings.strafeRunSpeedMultiplier = defaults.strafeRunSpeedMultiplier * clampedMultiplier;
        settings.forwardCrouchSpeedMultiplier = defaults.forwardCrouchSpeedMultiplier * clampedMultiplier;
        settings.backwardCrouchSpeedMultiplier = defaults.backwardCrouchSpeedMultiplier * clampedMultiplier;
        settings.strafeCrouchSpeedMultiplier = defaults.strafeCrouchSpeedMultiplier * clampedMultiplier;
        settings.forwardSprintSpeedMultiplier = defaults.forwardSprintSpeedMultiplier * clampedMultiplier;
    }

    private static int resolveMaxSummons(ArmyOfTheDeadConfig config,
            PlayerData sourcePlayerData,
            EntityStatMap sourceStats) {
        if (config == null) {
            return 0;
        }

        int base = Math.max(0, config.baseSummonAmount());
        if (base <= 0) {
            return 0;
        }

        int extra = 0;
        
        // Check for strength and sorcery scaling
        if (config.strengthPerSummon() > 0.0D || config.sorceryPerSummon() > 0.0D) {
            double strength = 0.0D;
            double sorcery = 0.0D;
            
            if (sourcePlayerData != null) {
                strength = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.STRENGTH));
                sorcery = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.SORCERY));
            }
            
            int strengthExtra = 0;
            int sorceryExtra = 0;
            
            if (config.strengthPerSummon() > 0.0D) {
                strengthExtra = (int) Math.floor(strength / config.strengthPerSummon());
            }
            if (config.sorceryPerSummon() > 0.0D) {
                sorceryExtra = (int) Math.floor(sorcery / config.sorceryPerSummon());
            }

            extra = strengthExtra + sorceryExtra;
        } else {
            // Fallback to mana-based scaling for backwards compatibility
            double ownerMana = 0.0D;
            if (sourceStats != null) {
                EntityStatValue mana = sourceStats.get(DefaultEntityStatTypes.getMana());
                ownerMana = mana != null ? Math.max(0.0D, mana.getMax()) : 0.0D;
            }
            if (ownerMana <= 0.0D && sourcePlayerData != null) {
                ownerMana = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.FLOW));
            }

            if (config.manaPerSummon() > 0.0D) {
                extra = (int) Math.floor(ownerMana / config.manaPerSummon());
            }
        }

        int total = base + Math.max(0, extra);
        if (config.maxSummons() > 0) {
            total = Math.min(total, config.maxSummons());
        }
        return Math.min(MAX_SUMMON_CAP, Math.max(0, total));
    }

    private static boolean isSummonSlotDeployed(SummonSlot slot, long now) {
        if (slot == null || slot.activeSummonUuid == null || slot.activeRef == null) {
            return false;
        }
        if (!SUMMON_BINDINGS.containsKey(slot.activeSummonUuid)) {
            return false;
        }
        if (!EntityRefUtil.isUsable(slot.activeRef)) {
            return false;
        }
        return slot.summonExpiresAt <= 0L || slot.summonExpiresAt > now;
    }

    private static void cleanupSlot(UUID ownerUuid,
            SummonSlot slot,
            long now,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> sourceRef) {
        if (slot == null || slot.activeSummonUuid == null) {
            return;
        }

        if (!SUMMON_BINDINGS.containsKey(slot.activeSummonUuid)) {
            slot.activeRef = null;
            slot.activeSummonUuid = null;
            slot.summonExpiresAt = 0L;
            slot.spawnPending = false;
            slot.postSpawnHealthNormalizePending = false;
            slot.nextNameplateRefreshAt = 0L;
            return;
        }

        if (slot.spawnPending) {
            return;
        }

        if (slot.activeRef == null || !EntityRefUtil.isUsable(slot.activeRef)) {
            SUMMON_BINDINGS.remove(slot.activeSummonUuid);
            slot.activeRef = null;
            slot.activeSummonUuid = null;
            slot.summonExpiresAt = 0L;
            slot.cooldownExpiresAt = now + Math.max(0L, slot.cooldownDurationMillis);
            slot.spawnPending = false;
            slot.postSpawnHealthNormalizePending = false;
            slot.nextNameplateRefreshAt = 0L;
            return;
        }

        if (slot.summonExpiresAt > 0L && now >= slot.summonExpiresAt) {
            MobAugmentExecutor mobAugmentExecutor = EndlessLeveling.getInstance().getMobAugmentExecutor();
            if (mobAugmentExecutor != null) {
                EntityStatMap summonStats = EntityRefUtil.tryGetComponent(commandBuffer,
                        slot.activeRef,
                        EntityStatMap.getComponentType());
                float currentHp = 1.0f;
                if (summonStats != null) {
                    EntityStatValue health = summonStats.get(DefaultEntityStatTypes.getHealth());
                    if (health != null) {
                        currentHp = (float) Math.max(1.0D, health.get());
                    }
                }
                mobAugmentExecutor.applyOnDamageTaken(slot.activeSummonUuid,
                        slot.activeRef,
                        sourceRef,
                        commandBuffer,
                        summonStats,
                        currentHp + 1.0f);
            }

            forceKillSummon(slot.activeRef, commandBuffer, sourceRef);
            SUMMON_BINDINGS.remove(slot.activeSummonUuid);
            slot.activeRef = null;
            slot.activeSummonUuid = null;
            slot.summonExpiresAt = 0L;
            slot.cooldownExpiresAt = now + Math.max(0L, slot.cooldownDurationMillis);
            slot.spawnPending = false;
            slot.postSpawnHealthNormalizePending = false;
            slot.nextNameplateRefreshAt = 0L;
            LOGGER.atFiner().log("[ARMY_OF_THE_DEAD] Expired summon for owner %s; cooldown until %d.",
                    ownerUuid,
                    slot.cooldownExpiresAt);
        }
    }

    private static void cleanupOwnerSummons(UUID ownerUuid,
            Store<EntityStore> targetStore,
            boolean forceAllInStore,
            long now) {
        OwnerSummonState ownerState = OWNER_STATES.get(ownerUuid);
        cleanupOwnerSummons(ownerUuid, targetStore, forceAllInStore, now, targetStore, ownerState);
    }

    private static void cleanupOwnerSummons(UUID ownerUuid,
            Store<EntityStore> targetStore,
            boolean ownerDisconnected,
            long now,
            Store<EntityStore> ownerStore,
            OwnerSummonState ownerState) {
        if (ownerUuid == null || targetStore == null || ownerState == null) {
            return;
        }

        boolean anyTracked = false;
        synchronized (ownerState) {
            for (SummonSlot slot : ownerState.slots) {
                if (slot == null || slot.activeSummonUuid == null) {
                    continue;
                }
                anyTracked = true;

                Ref<EntityStore> summonRef = slot.activeRef;
                Store<EntityStore> summonStore = summonRef != null ? summonRef.getStore() : null;
                if (summonStore != targetStore) {
                    continue;
                }

                boolean invalidBinding = !SUMMON_BINDINGS.containsKey(slot.activeSummonUuid);
                boolean invalidRef = summonRef == null || !EntityRefUtil.isUsable(summonRef);
                boolean expired = slot.summonExpiresAt > 0L && now >= slot.summonExpiresAt;
                boolean ownerLeftWorld = !ownerDisconnected && ownerStore != null && ownerStore != summonStore;
                boolean shouldDespawn = ownerDisconnected || invalidBinding || invalidRef || expired || ownerLeftWorld;

                if (!shouldDespawn) {
                    continue;
                }

                if (!invalidRef) {
                    forceKillSummon(summonRef, summonStore);
                }

                SUMMON_BINDINGS.remove(slot.activeSummonUuid);
                slot.activeRef = null;
                slot.activeSummonUuid = null;
                slot.summonExpiresAt = 0L;
                slot.cooldownExpiresAt = now + Math.max(0L, slot.cooldownDurationMillis);
                slot.spawnPending = false;
                slot.postSpawnHealthNormalizePending = false;
                slot.nextNameplateRefreshAt = 0L;

                LOGGER.atFine().log(
                        "[ARMY_OF_THE_DEAD] Cleaned summon for owner %s (disconnected=%s, invalidBinding=%s, invalidRef=%s, expired=%s, ownerLeftWorld=%s).",
                        ownerUuid,
                        ownerDisconnected,
                        invalidBinding,
                        invalidRef,
                        expired,
                        ownerLeftWorld);
            }
        }

        if (!anyTracked) {
            OWNER_STATES.remove(ownerUuid, ownerState);
        }
    }

    private static SummonSourceSnapshot captureSourceSnapshot(Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap sourceStats,
            PlayerData sourcePlayerData) {
        if (!EntityRefUtil.isUsable(sourceRef) || commandBuffer == null || sourcePlayerData == null) {
            return null;
        }

        Store<EntityStore> store = EntityRefUtil.getStore(sourceRef);
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }

        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null || sourceTransform.getRotation() == null) {
            return null;
        }

        double healthMax = 0.0D;
        double manaMax = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.FLOW));
        double staminaMax = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.STAMINA));
        if (sourceStats != null) {
            EntityStatValue ownerHealth = sourceStats.get(DefaultEntityStatTypes.getHealth());
            EntityStatValue ownerMana = sourceStats.get(DefaultEntityStatTypes.getMana());
            EntityStatValue ownerStamina = sourceStats.get(DefaultEntityStatTypes.getStamina());
            healthMax = ownerHealth != null ? Math.max(0.0D, ownerHealth.getMax()) : 0.0D;
            manaMax = ownerMana != null ? Math.max(0.0D, ownerMana.getMax()) : manaMax;
            staminaMax = ownerStamina != null ? Math.max(0.0D, ownerStamina.getMax()) : staminaMax;
        }

        Vector3d position = sourceTransform.getPosition();
        Vector3f rotation = sourceTransform.getRotation();
        return new SummonSourceSnapshot(store.getExternalData().getWorld().getName(),
                sourceRef,
                sourcePlayerData.getUuid(),
                new Vector3d(position.getX(), position.getY(), position.getZ()),
                new Vector3f(rotation.getX(), rotation.getY(), rotation.getZ()),
                healthMax,
                manaMax,
                staminaMax);
    }

    private static Vector3d resolveSummonSpawnPosition(Vector3d ownerPosition,
            UUID ownerUuid,
            int slotIndex) {
        if (ownerPosition == null || ownerUuid == null) {
            return ownerPosition;
        }

        long seed = mixSpawnSeed(ownerUuid, slotIndex);
        double angle = unitFromSeed(seed) * (Math.PI * 2.0D);
        double radius = 1.75D + (unitFromSeed(Long.rotateLeft(seed, 21)) * 1.45D);

        // Keep additional summons from stacking on top of each other.
        radius += Math.min(1.15D, Math.max(0, slotIndex) * 0.12D);

        double x = ownerPosition.getX() + (Math.cos(angle) * radius);
        double z = ownerPosition.getZ() + (Math.sin(angle) * radius);
        return new Vector3d(x, ownerPosition.getY(), z);
    }

    private static long mixSpawnSeed(UUID ownerUuid, int slotIndex) {
        long mixed = ownerUuid.getMostSignificantBits()
                ^ Long.rotateLeft(ownerUuid.getLeastSignificantBits(), 17)
                ^ (0x9E3779B97F4A7C15L * (slotIndex + 1L));

        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= (mixed >>> 33);
        return mixed;
    }

    private static double unitFromSeed(long seed) {
        long bits = (seed >>> 11) & ((1L << 53) - 1L);
        return bits / (double) (1L << 53);
    }

    private static void logInheritanceComputation(UUID ownerUuid,
            double inheritance,
            double strength,
            double sorcery,
            double precisionPercent,
            double ferocityPercent,
            double defenseResistance,
            double hasteMultiplier,
            double lifeForceFlat,
            float damageMultiplier,
            float critChance,
            float critDamageMultiplier,
            float defenseReduction,
            float movementMultiplier,
            float lifeForceFlatHealthBonus) {
        if (!DEBUG_SUMMON_INHERITANCE) {
            return;
        }
        if (ownerUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = INHERITANCE_DEBUG_LAST_LOG.get(ownerUuid);
        if (last != null && now - last < INHERITANCE_DEBUG_LOG_COOLDOWN_MS) {
            return;
        }
        INHERITANCE_DEBUG_LAST_LOG.put(ownerUuid, now);
        LOGGER.atInfo().log(
                "[ARMY_OF_THE_DEAD][DEBUG] owner=%s inheritance=%.3f strength=%.3f sorcery=%.3f precision=%.3f ferocity=%.3f defenseRes=%.3f hasteMult=%.3f lifeForce=%.3f -> dmgMult=%.3f critChance=%.3f critDmgMult=%.3f defenseReduction=%.3f moveMult=%.3f lifeForceHp=%.3f",
                ownerUuid,
                inheritance,
                strength,
                sorcery,
                precisionPercent,
                ferocityPercent,
                defenseResistance,
                hasteMultiplier,
                lifeForceFlat,
                damageMultiplier,
                critChance,
                critDamageMultiplier,
                defenseReduction,
                movementMultiplier,
                lifeForceFlatHealthBonus);
    }

    private static void attachSummonToOwnerFlock(Ref<EntityStore> ownerRef,
            Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            String allowedRole) {
        if (!EntityRefUtil.isUsable(ownerRef) || !EntityRefUtil.isUsable(summonRef) || store == null) {
            return;
        }

        try {
            Ref<EntityStore> flockRef = FlockPlugin.getFlockReference(ownerRef, store);
            if (flockRef == null) {
                flockRef = FlockPlugin.createFlock(store,
                        null,
                        allowedRole == null || allowedRole.isBlank() ? new String[0] : new String[] { allowedRole });
                FlockMembershipSystems.join(ownerRef, flockRef, store);
            }

            Ref<EntityStore> summonFlockRef = FlockPlugin.getFlockReference(summonRef, store);
            if (summonFlockRef == null || !summonFlockRef.equals(flockRef)) {
                FlockMembershipSystems.join(summonRef, flockRef, store);
            }
        } catch (Throwable throwable) {
            LOGGER.atFiner().withCause(throwable)
                    .log("[ARMY_OF_THE_DEAD] Failed to join summon to owner flock.");
        }
    }

    private static UUID resolveSummonOwnerUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        UUID summonUuid = resolveEntityUuid(ref, store, commandBuffer);
        if (summonUuid == null) {
            return null;
        }

        SummonBinding binding = SUMMON_BINDINGS.get(summonUuid);
        return binding != null ? binding.ownerUuid() : null;
    }

    private static UUID resolvePlayerUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return null;
        }

        PlayerRef playerRef = EntityRefUtil.tryGetComponent(commandBuffer, ref, PlayerRef.getComponentType());
        if (playerRef == null && store != null) {
            playerRef = EntityRefUtil.tryGetComponent(store, ref, PlayerRef.getComponentType());
        }
        return playerRef != null && playerRef.isValid() ? playerRef.getUuid() : null;
    }

    private static UUID resolveEntityUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return null;
        }

        UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer, ref,
                UUIDComponent.getComponentType());
        if (uuidComponent == null && store != null) {
            uuidComponent = EntityRefUtil.tryGetComponent(store, ref, UUIDComponent.getComponentType());
        }
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    private static void applySummonNameplate(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            boolean logSuccess) {
        if (!EntityRefUtil.isUsable(summonRef) || store == null) {
            return;
        }

        String ownerName = resolveOwnerName(ownerUuid);
        String label = ownerName != null && !ownerName.isBlank()
                ? ownerName + "'s Undead Summon"
                : "Undead Summon";

        boolean builderAvailable = NameplateBuilderCompatibility.isAvailable();
        boolean summonSegmentApplied = false;
        boolean mobFallbackApplied = false;
        if (builderAvailable) {
            summonSegmentApplied = NameplateBuilderCompatibility.registerSummonText(store, summonRef, label);
            if (!summonSegmentApplied) {
                mobFallbackApplied = NameplateBuilderCompatibility.registerMobText(store, summonRef, label);
            }
        }

        boolean entitySupportApplied = false;
        try {
            EntitySupport.setDisplayName(summonRef, label, store);
            entitySupportApplied = true;
        } catch (Throwable throwable) {
            if (DEBUG_SUMMON_NAMEPLATE && logSuccess) {
                LOGGER.atWarning().withCause(throwable)
                        .log("[ARMY_OF_THE_DEAD][DEBUG-NAMEPLATE-SPAWN] EntitySupport setDisplayName failed for summon=%s owner=%s",
                                resolveEntityUuid(summonRef, store, null),
                                ownerUuid);
            }
        }

        Nameplate nameplate = EntityRefUtil.tryGetComponent(store, summonRef, Nameplate.getComponentType());
        boolean vanillaApplied = false;
        if (nameplate != null) {
            nameplate.setText(label);
            vanillaApplied = true;
        }

        UUID summonUuid = resolveEntityUuid(summonRef, store, null);
        if (logSuccess && DEBUG_SUMMON_NAMEPLATE) {
            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-NAMEPLATE-SPAWN] summon=%s owner=%s builderAvailable=%s summonSegment=%s mobFallback=%s entitySupportApplied=%s vanillaApplied=%s hasVanillaComponent=%s text=%s",
                    summonUuid,
                    ownerUuid,
                    builderAvailable,
                    summonSegmentApplied,
                    mobFallbackApplied,
                    entitySupportApplied,
                    vanillaApplied,
                    nameplate != null,
                    label);
        }

        if (summonSegmentApplied || mobFallbackApplied || vanillaApplied || entitySupportApplied) {
            if (summonUuid != null) {
                NAMEPLATE_FAILURE_LAST_LOG.remove(summonUuid);
            }
            return;
        }

        UUID nameplateFailureKey = ownerUuid != null ? ownerUuid : summonUuid;
        if (shouldLogNameplateFailure(nameplateFailureKey)) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-NAMEPLATE-SPAWN] Failed to apply summon nameplate for summon=%s owner=%s",
                    summonUuid,
                    ownerUuid);
        }
    }

    private static boolean shouldLogNameplateFailure(UUID summonUuid) {
        if (summonUuid == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = NAMEPLATE_FAILURE_LAST_LOG.get(summonUuid);
        if (last != null && now - last < NAMEPLATE_FAILURE_LOG_COOLDOWN_MS) {
            return false;
        }
        NAMEPLATE_FAILURE_LAST_LOG.put(summonUuid, now);
        return true;
    }

    private static String resolveOwnerName(UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin != null) {
            PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
            if (playerDataManager != null) {
                PlayerData ownerData = playerDataManager.get(ownerUuid);
                if (ownerData != null && ownerData.getPlayerName() != null && !ownerData.getPlayerName().isBlank()) {
                    return ownerData.getPlayerName();
                }
            }
        }

        PlayerRef ownerRef = Universe.get().getPlayer(ownerUuid);
        if (ownerRef != null && ownerRef.getUsername() != null && !ownerRef.getUsername().isBlank()) {
            return ownerRef.getUsername();
        }

        return null;
    }

    private static boolean isAlliedWithOwner(UUID ownerUuid, UUID candidateUuid) {
        if (ownerUuid == null || candidateUuid == null) {
            return false;
        }
        if (ownerUuid.equals(candidateUuid)) {
            return true;
        }

        PartyManager partyManager = resolvePartyManager();
        if (partyManager == null || !partyManager.isAvailable()) {
            return false;
        }
        if (!partyManager.isInParty(ownerUuid) || !partyManager.isInParty(candidateUuid)) {
            return false;
        }

        UUID ownerLeader = partyManager.getPartyLeader(ownerUuid);
        UUID candidateLeader = partyManager.getPartyLeader(candidateUuid);
        return ownerLeader != null && ownerLeader.equals(candidateLeader);
    }

    private static boolean areAlliedOwners(UUID firstOwnerUuid, UUID secondOwnerUuid) {
        return isAlliedWithOwner(firstOwnerUuid, secondOwnerUuid);
    }

    private static PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    public static void updateSummonLeashPositions(PlayerData playerData,
            Ref<EntityStore> ownerRef,
            CommandBuffer<EntityStore> commandBuffer) {
        if (playerData == null || !EntityRefUtil.isUsable(ownerRef) || commandBuffer == null) {
            return;
        }

        UUID ownerUuid = playerData.getUuid();
        if (ownerUuid == null) {
            return;
        }

        OwnerSummonState ownerState = OWNER_STATES.get(ownerUuid);
        if (ownerState == null) {
            return;
        }

        TransformComponent ownerTransform = EntityRefUtil.tryGetComponent(commandBuffer, ownerRef,
                TransformComponent.getComponentType());
        if (ownerTransform == null || ownerTransform.getPosition() == null) {
            return;
        }

        Vector3d ownerPosition = ownerTransform.getPosition();
        long now = System.currentTimeMillis();
        synchronized (ownerState) {
            for (SummonSlot slot : ownerState.slots) {
                if (slot == null || slot.activeRef == null || !EntityRefUtil.isUsable(slot.activeRef)) {
                    continue;
                }

                // Enforce lifetime expiry on a per-tick basis so summons always
                // despawn at 30 s regardless of whether a new summon is triggered.
                if (slot.summonExpiresAt > 0L && now >= slot.summonExpiresAt) {
                    forceKillSummon(slot.activeRef, commandBuffer, ownerRef);
                    SUMMON_BINDINGS.remove(slot.activeSummonUuid);
                    slot.activeRef = null;
                    slot.activeSummonUuid = null;
                    slot.summonExpiresAt = 0L;
                    slot.postSpawnHealthNormalizePending = false;
                    slot.nextNameplateRefreshAt = 0L;
                    slot.cooldownExpiresAt = now + Math.max(0L, slot.cooldownDurationMillis);
                    slot.spawnPending = false;
                    LOGGER.atFiner().log(
                            "[ARMY_OF_THE_DEAD] Lifetime expired for slot of owner %s; cooldown until %d.",
                            ownerUuid, slot.cooldownExpiresAt);
                    continue;
                }

                Store<EntityStore> store = EntityRefUtil.getStore(slot.activeRef);
                if (store == null) {
                    continue;
                }

                if (slot.postSpawnHealthNormalizePending) {
                    forceCurrentHealthToMax(slot.activeRef, store);
                    logSummonHealthState(slot.activeRef,
                            store,
                            ownerUuid,
                            -1,
                            slot.statInheritance,
                            "POST_SPAWN_TICK_NORMALIZE");
                    slot.postSpawnHealthNormalizePending = false;
                }

                if (now >= slot.nextNameplateRefreshAt) {
                    applySummonNameplate(slot.activeRef, store, ownerUuid, false);
                    slot.nextNameplateRefreshAt = now + NAMEPLATE_REFRESH_INTERVAL_MS;
                }

                NPCEntity summonNpc = EntityRefUtil.tryGetComponent(store, slot.activeRef,
                        NPCEntity.getComponentType());
                if (summonNpc == null) {
                    continue;
                }

                summonNpc.setLeashPoint(ownerPosition);

                TransformComponent summonTransform = EntityRefUtil.tryGetComponent(store, slot.activeRef,
                        TransformComponent.getComponentType());
                if (summonTransform != null && summonTransform.getPosition() != null
                        && summonTransform.getPosition().distanceSquaredTo(ownerPosition) > TELEPORT_RANGE_SQ) {
                    summonTransform.teleportPosition(ownerPosition);
                }

                if (slot.statInheritance > 0.0D) {
                    SummonInheritedStats inheritedStats = resolveOwnerSummonInheritedStats(ownerUuid,
                            slot.statInheritance);
                    applySummonMovementSpeedBonus(slot.activeRef, store, inheritedStats.movementMultiplier());
                }
            }
        }
    }

    private static void clearPendingSlot(UUID ownerUuid, int slotIndex) {
        if (ownerUuid == null) {
            return;
        }
        OwnerSummonState ownerState = OWNER_STATES.get(ownerUuid);
        if (ownerState == null) {
            return;
        }
        synchronized (ownerState) {
            SummonSlot slot = ownerState.getSlot(slotIndex);
            if (slot != null) {
                slot.spawnPending = false;
            }
        }
    }

    private static void forceKillSummon(Ref<EntityStore> summonRef,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> sourceRef) {
        if (!EntityRefUtil.isUsable(summonRef)) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(commandBuffer, summonRef,
                EntityStatMap.getComponentType());
        if (summonStats == null && sourceRef != null) {
            Store<EntityStore> store = EntityRefUtil.getStore(sourceRef);
            summonStats = EntityRefUtil.tryGetComponent(store, summonRef, EntityStatMap.getComponentType());
        }
        if (summonStats == null) {
            return;
        }

        EntityStatValue hp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (hp != null && hp.get() > 0f) {
            summonStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
        }
    }

    private static void forceKillSummon(Ref<EntityStore> summonRef,
            Store<EntityStore> store) {
        if (!EntityRefUtil.isUsable(summonRef) || store == null) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(store, summonRef,
                EntityStatMap.getComponentType());
        if (summonStats == null) {
            return;
        }

        EntityStatValue hp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (hp != null && hp.get() > 0f) {
            summonStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
        }
    }

    private static ArmyOfTheDeadConfig resolveConfig(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return ArmyOfTheDeadConfig.disabled();
        }

        RacePassiveDefinition strongest = resolveStrongestDefinition(
                snapshot.getDefinitions(ArchetypePassiveType.ARMY_OF_THE_DEAD));
        if (strongest == null) {
            return ArmyOfTheDeadConfig.disabled();
        }

        Map<String, Object> props = strongest.properties();
        double effectivenessScale = Math.max(0.0D, strongest.value());
        int baseSummonAmount = parseIntNonNegative(
                firstNonNull(props.get("base_summon_amount"), props.get("base_summons")),
                DEFAULT_BASE_SUMMON_AMOUNT);

        double manaPerSummon = DEFAULT_MANA_PER_SUMMON;
        double strengthPerSummon = 0.0D;
        double sorceryPerSummon = 0.0D;
        
        Object scaling = props.get("summon_amount_scaling");
        if (scaling instanceof Map<?, ?> scalingMap) {
            manaPerSummon = parsePositiveDouble(firstNonNull(scalingMap.get("mana_per_summon"),
                    scalingMap.get("mana_ratio")), DEFAULT_MANA_PER_SUMMON);
            strengthPerSummon = parsePositiveDouble(scalingMap.get("strength_per_summon"), 0.0D);
            sorceryPerSummon = parsePositiveDouble(scalingMap.get("sorcery_per_summon"), 0.0D);
        }
        manaPerSummon = parsePositiveDouble(firstNonNull(props.get("mana_per_summon"), manaPerSummon),
                manaPerSummon);

        double cooldownSeconds = parseNonNegativeDouble(props.get("cooldown_seconds"), DEFAULT_COOLDOWN_SECONDS);
        double lifetimeSeconds = parseNonNegativeDouble(firstNonNull(props.get("minion_lifetime_seconds"),
                props.get("lifetime_seconds")), DEFAULT_LIFETIME_SECONDS);
        double baseDamage = parseNonNegativeDouble(firstNonNull(props.get("base_summon_damage"),
            props.get("summon_base_damage")), DEFAULT_BASE_SUMMON_DAMAGE);
        double statInheritance = parseNonNegativeDouble(firstNonNull(props.get("summon_stat_inheritance"),
                props.get("stat_inheritance")), DEFAULT_STAT_INHERITANCE);

        statInheritance *= effectivenessScale;

        boolean onHitActivation = true;
        Object activationRaw = props.get("activation");
        if (activationRaw instanceof String activation) {
            onHitActivation = "on_hit".equalsIgnoreCase(activation.trim());
        }

        String skeletonType = DEFAULT_SKELETON_TYPE;
        Object skeletonRaw = firstNonNull(props.get("skeleton_type"), props.get("summon_npc_type"));
        if (skeletonRaw instanceof String skeleton && !skeleton.isBlank()) {
            skeletonType = skeleton.trim();
        }

        int maxSummons = parseIntNonNegative(firstNonNull(props.get("max_summons"), props.get("summon_cap")), 0);

        return new ArmyOfTheDeadConfig(onHitActivation,
                baseSummonAmount,
                manaPerSummon,
                strengthPerSummon,
                sorceryPerSummon,
                secondsToMillis(cooldownSeconds),
                secondsToMillis(lifetimeSeconds),
                baseDamage,
                statInheritance,
                skeletonType,
            maxSummons,
            effectivenessScale);
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

    private static int parseIntNonNegative(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String string) {
            try {
                return Math.max(0, Integer.parseInt(string.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(0, fallback);
    }

    private static double parsePositiveDouble(Object raw, double fallback) {
        double parsed = parseRawDouble(raw, fallback);
        return parsed > 0.0D ? parsed : fallback;
    }

    private static double parseNonNegativeDouble(Object raw, double fallback) {
        return Math.max(0.0D, parseRawDouble(raw, fallback));
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

    private record ArmyOfTheDeadConfig(boolean onHitActivation,
            int baseSummonAmount,
            double manaPerSummon,
            double strengthPerSummon,
            double sorceryPerSummon,
            long cooldownMillis,
            long lifetimeMillis,
            double baseDamage,
            double statInheritance,
            String skeletonType,
            int maxSummons,
            double effectivenessScale) {

        private static ArmyOfTheDeadConfig disabled() {
            return new ArmyOfTheDeadConfig(false,
                    DEFAULT_BASE_SUMMON_AMOUNT,
                    DEFAULT_MANA_PER_SUMMON,
                    0.0D,
                    0.0D,
                    secondsToMillis(DEFAULT_COOLDOWN_SECONDS),
                    secondsToMillis(DEFAULT_LIFETIME_SECONDS),
                DEFAULT_BASE_SUMMON_DAMAGE,
                    DEFAULT_STAT_INHERITANCE,
                    DEFAULT_SKELETON_TYPE,
                    0,
                    0.0D);
        }
    }

    private record SummonBinding(UUID ownerUuid, int slotIndex) {
    }

    private record SpawnedSummon(Ref<EntityStore> ref, UUID uuid, long expiresAt) {
    }

    private record SummonSourceSnapshot(String worldId,
            Ref<EntityStore> ownerRef,
            UUID ownerUuid,
            Vector3d position,
            Vector3f rotation,
            double healthMax,
            double manaMax,
            double staminaMax) {
    }

    public record SummonInheritedStats(float damageMultiplier,
            float critChance,
            float critDamageMultiplier,
            float defenseReduction,
            float movementMultiplier,
            float lifeForceFlatHealthBonus) {
        public static SummonInheritedStats none() {
            return new SummonInheritedStats(1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f);
        }
    }

    public record SummonHudState(int deployedCount, int availableCount, int maxSummons) {
        public static SummonHudState none() {
            return new SummonHudState(0, 0, 0);
        }
    }

    private record QueuedSpawnRequest(UUID ownerUuid,
            int slotIndex,
            long triggeredAtMillis,
            ArmyOfTheDeadConfig config,
            SummonSourceSnapshot source) {
    }

    private static final class OwnerSummonState {
        private final List<SummonSlot> slots = new ArrayList<>();

        private void ensureSlots(int required) {
            while (slots.size() < required) {
                slots.add(new SummonSlot());
            }
        }

        private SummonSlot getSlot(int slotIndex) {
            if (slotIndex < 0 || slotIndex >= slots.size()) {
                return null;
            }
            return slots.get(slotIndex);
        }
    }

    private static final class SummonSlot {
        private Ref<EntityStore> activeRef;
        private UUID activeSummonUuid;
        private long summonExpiresAt;
        private long cooldownExpiresAt;
        private long cooldownDurationMillis;
        private double baseDamage = DEFAULT_BASE_SUMMON_DAMAGE;
        private double statInheritance = DEFAULT_STAT_INHERITANCE;
        private boolean spawnPending;
        private boolean postSpawnHealthNormalizePending;
        private long nextNameplateRefreshAt;
    }
}
