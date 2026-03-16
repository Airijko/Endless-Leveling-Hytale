package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PartyManager;
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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

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
    private static final double DEFAULT_MANA_PER_SUMMON = 100.0D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 15.0D;
    private static final double DEFAULT_LIFETIME_SECONDS = 30.0D;
    private static final double DEFAULT_STAT_INHERITANCE = 0.10D;
    private static final int MAX_SUMMON_CAP = 64;
    private static final double TELEPORT_RANGE = 32.0D;
    private static final double TELEPORT_RANGE_SQ = TELEPORT_RANGE * TELEPORT_RANGE;

    private static final Map<UUID, OwnerSummonState> OWNER_STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, SummonBinding> SUMMON_BINDINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PENDING_ON_HIT_TRIGGERS = new ConcurrentHashMap<>();

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

    public static boolean shouldPreventFriendlyDamage(Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(attackerRef) || !EntityRefUtil.isUsable(targetRef)) {
            return false;
        }

        UUID attackerOwner = resolveSummonOwnerUuid(attackerRef, store, commandBuffer);
        UUID targetOwner = resolveSummonOwnerUuid(targetRef, store, commandBuffer);

        if (attackerOwner == null && targetOwner == null) {
            return false;
        }

        UUID attackerPlayerUuid = resolvePlayerUuid(attackerRef, store, commandBuffer);
        UUID targetPlayerUuid = resolvePlayerUuid(targetRef, store, commandBuffer);

        if (attackerOwner != null && targetPlayerUuid != null && isAlliedWithOwner(attackerOwner, targetPlayerUuid)) {
            return true;
        }

        if (targetOwner != null && attackerPlayerUuid != null && isAlliedWithOwner(targetOwner, attackerPlayerUuid)) {
            return true;
        }

        return attackerOwner != null
                && targetOwner != null
                && areAlliedOwners(attackerOwner, targetOwner);
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

                applySummonScaling(summonRef,
                        store,
                        request.source(),
                        request.config().statInheritance(),
                        request.slotIndex());

                long expiresAt = request.config().lifetimeMillis() > 0L
                        ? Math.max(now, request.triggeredAtMillis()) + request.config().lifetimeMillis()
                        : 0L;
                slot.activeRef = summonRef;
                slot.activeSummonUuid = summonUuidComponent.getUuid();
                slot.summonExpiresAt = expiresAt;
                slot.spawnPending = false;
                SUMMON_BINDINGS.put(summonUuidComponent.getUuid(),
                        new SummonBinding(request.ownerUuid(), request.slotIndex()));
                LOGGER.atFine().log(
                        "[ARMY_OF_THE_DEAD] Activated summon %s for owner %s slot %d type=%s.",
                        summonUuidComponent.getUuid(),
                        request.ownerUuid(),
                        request.slotIndex(),
                        spawnRoleType);
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
        if (summonRef == null || store == null || source == null || inheritance <= 0.0D) {
            return;
        }

        EntityStatMap summonStats = EntityRefUtil.tryGetComponent(store, summonRef, EntityStatMap.getComponentType());
        if (summonStats == null) {
            return;
        }

        double scalingPool = source.healthMax() + source.manaMax() + source.staminaMax();
        double healthBonus = Math.max(0.0D, scalingPool * inheritance);
        if (healthBonus <= 0.0D) {
            return;
        }

        String modifierKey = "EL_AOTD_HP_" + slotIndex;
        summonStats.removeModifier(DefaultEntityStatTypes.getHealth(), modifierKey);
        summonStats.putModifier(DefaultEntityStatTypes.getHealth(),
                modifierKey,
                new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, (float) healthBonus));
        summonStats.update();

        EntityStatValue updatedHp = summonStats.get(DefaultEntityStatTypes.getHealth());
        if (updatedHp != null) {
            summonStats.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1.0f, updatedHp.getMax()));
        }
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

        double ownerMana = 0.0D;
        if (sourceStats != null) {
            EntityStatValue mana = sourceStats.get(DefaultEntityStatTypes.getMana());
            ownerMana = mana != null ? Math.max(0.0D, mana.getMax()) : 0.0D;
        }
        if (ownerMana <= 0.0D && sourcePlayerData != null) {
            ownerMana = Math.max(0.0D, sourcePlayerData.getPlayerSkillAttributeLevel(SkillAttributeType.FLOW));
        }

        int extra = 0;
        if (config.manaPerSummon() > 0.0D) {
            extra = (int) Math.floor(ownerMana / config.manaPerSummon());
        }

        int total = base + Math.max(0, extra);
        if (config.maxSummons() > 0) {
            total = Math.min(total, config.maxSummons());
        }
        return Math.min(MAX_SUMMON_CAP, Math.max(0, total));
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
            return;
        }

        if (slot.summonExpiresAt > 0L && now >= slot.summonExpiresAt) {
            forceKillSummon(slot.activeRef, commandBuffer, sourceRef);
            SUMMON_BINDINGS.remove(slot.activeSummonUuid);
            slot.activeRef = null;
            slot.activeSummonUuid = null;
            slot.summonExpiresAt = 0L;
            slot.cooldownExpiresAt = now + Math.max(0L, slot.cooldownDurationMillis);
            slot.spawnPending = false;
            LOGGER.atFiner().log("[ARMY_OF_THE_DEAD] Expired summon for owner %s; cooldown until %d.",
                    ownerUuid,
                    slot.cooldownExpiresAt);
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
        synchronized (ownerState) {
            for (SummonSlot slot : ownerState.slots) {
                if (slot == null || slot.activeRef == null || !EntityRefUtil.isUsable(slot.activeRef)) {
                    continue;
                }

                Store<EntityStore> store = EntityRefUtil.getStore(slot.activeRef);
                if (store == null) {
                    continue;
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
        int baseSummonAmount = parseIntNonNegative(
                firstNonNull(props.get("base_summon_amount"), props.get("base_summons")),
                DEFAULT_BASE_SUMMON_AMOUNT);

        double manaPerSummon = DEFAULT_MANA_PER_SUMMON;
        Object scaling = props.get("summon_amount_scaling");
        if (scaling instanceof Map<?, ?> scalingMap) {
            manaPerSummon = parsePositiveDouble(firstNonNull(scalingMap.get("mana_per_summon"),
                    scalingMap.get("mana_ratio")), DEFAULT_MANA_PER_SUMMON);
        }
        manaPerSummon = parsePositiveDouble(firstNonNull(props.get("mana_per_summon"), manaPerSummon),
                manaPerSummon);

        double cooldownSeconds = parseNonNegativeDouble(props.get("cooldown_seconds"), DEFAULT_COOLDOWN_SECONDS);
        double lifetimeSeconds = parseNonNegativeDouble(firstNonNull(props.get("minion_lifetime_seconds"),
                props.get("lifetime_seconds")), DEFAULT_LIFETIME_SECONDS);
        double statInheritance = parseNonNegativeDouble(firstNonNull(props.get("summon_stat_inheritance"),
                props.get("stat_inheritance")), DEFAULT_STAT_INHERITANCE);

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
                secondsToMillis(cooldownSeconds),
                secondsToMillis(lifetimeSeconds),
                statInheritance,
                skeletonType,
                maxSummons);
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
            long cooldownMillis,
            long lifetimeMillis,
            double statInheritance,
            String skeletonType,
            int maxSummons) {

        private static ArmyOfTheDeadConfig disabled() {
            return new ArmyOfTheDeadConfig(false,
                    DEFAULT_BASE_SUMMON_AMOUNT,
                    DEFAULT_MANA_PER_SUMMON,
                    secondsToMillis(DEFAULT_COOLDOWN_SECONDS),
                    secondsToMillis(DEFAULT_LIFETIME_SECONDS),
                    DEFAULT_STAT_INHERITANCE,
                    DEFAULT_SKELETON_TYPE,
                    0);
        }
    }

    private record SummonBinding(UUID ownerUuid, int slotIndex) {
    }

    private record SpawnedSummon(Ref<EntityStore> ref, UUID uuid, long expiresAt) {
    }

    private record SummonSourceSnapshot(String worldId,
            Ref<EntityStore> ownerRef,
            Vector3d position,
            Vector3f rotation,
            double healthMax,
            double manaMax,
            double staminaMax) {
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
        private boolean spawnPending;
    }
}