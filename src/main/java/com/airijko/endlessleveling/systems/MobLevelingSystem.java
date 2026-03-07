package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies mob-level stat scaling. For now mobs are hard-coded to level 10.
 * Scales Health and Damage (if a "Damage" stat exists) using the multipliers
 * provided by `LevelingManager`.
 */
public class MobLevelingSystem extends DelayedSystem<EntityStore> {

    private static final Query<EntityStore> ENTITY_QUERY = Query.any();
    private static final String MOB_HEALTH_SCALE_MODIFIER_KEY = "EL_MOB_HEALTH_SCALE";
    private static final float SYSTEM_INTERVAL_SECONDS = 0.15f;
    private static final long STALE_ENTITY_TTL_MILLIS = 100_000L;
    private static final int CHUNK_BIT_SHIFT = 5;
    private static final int MIN_PLAYER_VIEW_RADIUS_CHUNKS = 1;
    private static final int PLAYER_VIEW_RADIUS_BUFFER_CHUNKS = 1;

    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
    private final Map<Long, Integer> healthAppliedLevel = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastSeenTimeByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> levelResolveAttemptCountByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> levelResolveAssignmentCountByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, String> lastResetReasonByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> settledHealthLevelByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> appliedNameplateLevelByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Long> nameplateSyncReadyAtTimeByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> trackedEntityIndexByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, Store<EntityStore>> trackedStoreByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, String> lastKnownEntitySignatureByEntityKey = new ConcurrentHashMap<>();
    private final Map<Long, String> lastHealthApplySkipReasonByEntityKey = new ConcurrentHashMap<>();
    private final Set<Long> deathHandledEntityKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> forcedDeathLoggedEntityKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fullMobRescaleRequested = new AtomicBoolean(false);
    private long systemStepCounter = 0L;
    private long systemTimeMillis = 0L;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public MobLevelingSystem() {
        super(SYSTEM_INTERVAL_SECONDS);
    }

    public void requestFullMobRescale() {
        fullMobRescaleRequested.set(true);
    }

    @Override
    public void delayedTick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown())
            return;

        if (mobLevelingManager == null || !mobLevelingManager.isMobLevelingEnabled())
            return;

        boolean showMobLevelUi = mobLevelingManager.shouldShowMobLevelUi();
        boolean includeLevelInName = mobLevelingManager.shouldIncludeLevelInNameplate();
        boolean shouldResetAllMobs = fullMobRescaleRequested.getAndSet(false);

        if (shouldResetAllMobs) {
            mobLevelingManager.clearAllEntityLevelOverrides();
            healthAppliedLevel.clear();
            lastSeenTimeByEntityKey.clear();
            levelResolveAttemptCountByEntityKey.clear();
            levelResolveAssignmentCountByEntityKey.clear();
            lastResetReasonByEntityKey.clear();
            settledHealthLevelByEntityKey.clear();
            appliedNameplateLevelByEntityKey.clear();
            nameplateSyncReadyAtTimeByEntityKey.clear();
            trackedEntityIndexByEntityKey.clear();
            trackedStoreByEntityKey.clear();
            lastKnownEntitySignatureByEntityKey.clear();
            lastHealthApplySkipReasonByEntityKey.clear();
            deathHandledEntityKeys.clear();
            forcedDeathLoggedEntityKeys.clear();
            logMobInfo("rescale-requested", -1, -1L, "scope=all-mobs");
        }

        long currentStep = ++systemStepCounter;
        long elapsedMillis = Math.max(1L, Math.round(Math.max(0.0f, deltaSeconds) * 1000.0f));
        long currentTimeMillis = (systemTimeMillis += elapsedMillis);
        Set<Long> processedEntityKeysThisStep = new HashSet<>();
        List<PlayerChunkViewport> playerChunkViewports = snapshotPlayerChunkViewports(store);

        store.forEachChunk(ENTITY_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        int entityId = ref.getIndex();
                        TrackingIdentity trackingIdentity = resolveTrackingIdentity(ref, commandBuffer);
                        long entityKey = trackingIdentity.key();
                        boolean uuidBacked = trackingIdentity.uuidBacked();

                        if (!processedEntityKeysThisStep.add(entityKey)) {
                            logMobWarn(
                                    "duplicate-processing",
                                    entityId,
                                    entityKey,
                                    "reason=entity-encountered-multiple-times-in-same-step step=%d",
                                    currentStep);
                            continue;
                        }

                        trackedEntityIndexByEntityKey.put(entityKey, entityId);

                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef != null && playerRef.isValid()) {
                            clearMobHealthScaleModifierForPlayer(ref, commandBuffer, entityId, entityKey);
                            healthAppliedLevel.remove(entityKey);
                            settledHealthLevelByEntityKey.remove(entityKey);
                            appliedNameplateLevelByEntityKey.remove(entityKey);
                            nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
                            levelResolveAttemptCountByEntityKey.remove(entityKey);
                            levelResolveAssignmentCountByEntityKey.remove(entityKey);
                            lastKnownEntitySignatureByEntityKey.remove(entityKey);
                            lastHealthApplySkipReasonByEntityKey.remove(entityKey);
                            clearOrRemoveNameplate(ref, commandBuffer);
                            continue;
                        }

                        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
                        if (npcEntity == null) {
                            settledHealthLevelByEntityKey.remove(entityKey);
                            appliedNameplateLevelByEntityKey.remove(entityKey);
                            nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
                            lastKnownEntitySignatureByEntityKey.remove(entityKey);
                            lastHealthApplySkipReasonByEntityKey.remove(entityKey);
                            continue;
                        }

                        if (shouldResetAllMobs) {
                            clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey, "reload-rescale");
                        }

                        boolean hasNearbyPlayerChunk = isWithinNearbyPlayerChunkViewport(
                                ref,
                                commandBuffer,
                                playerChunkViewports);
                        if (!hasNearbyPlayerChunk) {
                            boolean hasTrackedState = healthAppliedLevel.containsKey(entityKey)
                                    || settledHealthLevelByEntityKey.containsKey(entityKey)
                                    || appliedNameplateLevelByEntityKey.containsKey(entityKey)
                                    || levelResolveAttemptCountByEntityKey.containsKey(entityKey)
                                    || levelResolveAssignmentCountByEntityKey.containsKey(entityKey)
                                    || lastKnownEntitySignatureByEntityKey.containsKey(entityKey)
                                    || lastHealthApplySkipReasonByEntityKey.containsKey(entityKey)
                                    || forcedDeathLoggedEntityKeys.contains(entityKey)
                                    || deathHandledEntityKeys.contains(entityKey)
                                    || trackedStoreByEntityKey.containsKey(entityKey);
                            if (hasTrackedState) {
                                clearLevelingStateForEntity(
                                        ref,
                                        commandBuffer,
                                        entityId,
                                        entityKey,
                                        "no-nearby-player-chunk");
                                clearOrRemoveNameplate(ref, commandBuffer);
                            } else {
                                settledHealthLevelByEntityKey.remove(entityKey);
                                appliedNameplateLevelByEntityKey.remove(entityKey);
                                nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
                                lastKnownEntitySignatureByEntityKey.remove(entityKey);
                                lastHealthApplySkipReasonByEntityKey.remove(entityKey);
                                trackedEntityIndexByEntityKey.remove(entityKey);
                                trackedStoreByEntityKey.remove(entityKey);
                            }
                            continue;
                        }

                        trackedStoreByEntityKey.put(entityKey, ref.getStore());
                        lastSeenTimeByEntityKey.put(entityKey, currentTimeMillis);

                        ensureDeadComponentWhenZeroHp(ref, commandBuffer);

                        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
                            if (deathHandledEntityKeys.add(entityKey)) {
                                ensureDeathHealthApplied(ref, commandBuffer, entityKey);
                                handleDeadEntity(ref, commandBuffer);
                                clearLevelingStateOnDeath(ref, entityId, entityKey);
                                forcedDeathLoggedEntityKeys.remove(entityKey);
                                lastResetReasonByEntityKey.put(entityKey, "death-component");
                            }
                            continue;
                        }

                        if (mobLevelingManager.isEntityBlacklisted(ref, store, commandBuffer)) {
                            boolean hasTrackedState = healthAppliedLevel.containsKey(entityKey)
                                    || settledHealthLevelByEntityKey.containsKey(entityKey)
                                    || appliedNameplateLevelByEntityKey.containsKey(entityKey)
                                    || levelResolveAttemptCountByEntityKey.containsKey(entityKey)
                                    || levelResolveAssignmentCountByEntityKey.containsKey(entityKey)
                                    || lastKnownEntitySignatureByEntityKey.containsKey(entityKey)
                                    || lastHealthApplySkipReasonByEntityKey.containsKey(entityKey)
                                    || forcedDeathLoggedEntityKeys.contains(entityKey)
                                    || deathHandledEntityKeys.contains(entityKey);
                            if (hasTrackedState) {
                                clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey, "blacklisted");
                            } else {
                                settledHealthLevelByEntityKey.remove(entityKey);
                                appliedNameplateLevelByEntityKey.remove(entityKey);
                                nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
                                lastKnownEntitySignatureByEntityKey.remove(entityKey);
                                lastHealthApplySkipReasonByEntityKey.remove(entityKey);
                            }
                            clearOrRemoveNameplate(ref, commandBuffer);
                            continue;
                        }

                        String currentEntitySignature = buildEntitySignature(ref, commandBuffer, npcEntity);
                        String previousEntitySignature = lastKnownEntitySignatureByEntityKey.get(entityKey);
                        if (previousEntitySignature != null
                                && !previousEntitySignature.equals(currentEntitySignature)) {
                            boolean hasAppliedLevel = healthAppliedLevel.containsKey(entityKey);
                            if (hasAppliedLevel) {
                                if (uuidBacked) {
                                    logMobInfo(
                                            "entity-signature-changed-ignored",
                                            entityId,
                                            entityKey,
                                            "previous=%s current=%s reason=uuid-backed-identity",
                                            previousEntitySignature,
                                            currentEntitySignature);
                                } else {
                                    logMobInfo(
                                            "entity-signature-changed-reset",
                                            entityId,
                                            entityKey,
                                            "previous=%s current=%s reason=legacy-id-key",
                                            previousEntitySignature,
                                            currentEntitySignature);
                                    clearLevelingStateForEntity(
                                            ref,
                                            commandBuffer,
                                            entityId,
                                            entityKey,
                                            "entity-signature-changed");
                                }
                            }
                        }
                        lastKnownEntitySignatureByEntityKey.put(entityKey, currentEntitySignature);

                        Integer appliedLevel = healthAppliedLevel.get(entityKey);
                        if (appliedLevel == null || appliedLevel <= 0) {
                            appliedLevel = resolveAndAssignLevelOnce(
                                    ref,
                                    store,
                                    commandBuffer,
                                    entityId,
                                    entityKey,
                                    currentEntitySignature,
                                    currentStep);
                        }
                        if (appliedLevel == null || appliedLevel <= 0) {
                            if (isAtOrBelowZeroHealth(ref, commandBuffer)) {
                                clearOrRemoveNameplate(ref, commandBuffer);
                                healthAppliedLevel.remove(entityKey);
                                settledHealthLevelByEntityKey.remove(entityKey);
                                appliedNameplateLevelByEntityKey.remove(entityKey);
                                nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
                                lastResetReasonByEntityKey.put(entityKey, "zero-health-no-level");
                            }
                            continue;
                        }

                        Integer settledHealthLevel = settledHealthLevelByEntityKey.get(entityKey);
                        boolean shouldApplyHealthModifier = settledHealthLevel == null
                                || settledHealthLevel <= 0
                                || !settledHealthLevel.equals(appliedLevel);

                        if (shouldApplyHealthModifier) {
                            lastHealthApplySkipReasonByEntityKey.remove(entityKey);
                            boolean healthApplied = applyHealthModifier(ref, commandBuffer, appliedLevel, entityKey);
                            if (healthApplied) {
                                settledHealthLevelByEntityKey.put(entityKey, appliedLevel);
                            }
                        }

                        Integer currentSettledHealthLevel = settledHealthLevelByEntityKey.get(entityKey);
                        boolean healthSettled = currentSettledHealthLevel != null
                                && currentSettledHealthLevel.equals(appliedLevel);
                        int resolveAssignments = levelResolveAssignmentCountByEntityKey.getOrDefault(entityKey, 0);
                        boolean initializationSettled = healthSettled
                                && resolveAssignments > 0;

                        if (showMobLevelUi && initializationSettled) {
                            Integer nameplateAppliedLevel = appliedNameplateLevelByEntityKey.get(entityKey);
                            if (nameplateAppliedLevel == null || !nameplateAppliedLevel.equals(appliedLevel)) {
                                boolean nameplateApplied = applyNameplate(ref, commandBuffer, includeLevelInName,
                                        entityKey);
                                if (nameplateApplied) {
                                    appliedNameplateLevelByEntityKey.put(entityKey, appliedLevel);
                                }
                            }
                        } else {
                            appliedNameplateLevelByEntityKey.remove(entityKey);
                            clearOrRemoveNameplate(ref, commandBuffer);
                        }
                    }
                });

        pruneStaleEntities(currentTimeMillis);
    }

    private void pruneStaleEntities(long currentTimeMillis) {
        if (lastSeenTimeByEntityKey.isEmpty()) {
            return;
        }

        long expiryTimeMillis = currentTimeMillis - STALE_ENTITY_TTL_MILLIS;
        Iterator<Map.Entry<Long, Long>> iterator = lastSeenTimeByEntityKey.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            Long entityKey = entry.getKey();
            Long lastSeenTime = entry.getValue();
            if (entityKey == null || lastSeenTime == null || lastSeenTime >= expiryTimeMillis) {
                continue;
            }

            iterator.remove();
            healthAppliedLevel.remove(entityKey);
            settledHealthLevelByEntityKey.remove(entityKey);
            appliedNameplateLevelByEntityKey.remove(entityKey);
            nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
            lastKnownEntitySignatureByEntityKey.remove(entityKey);
            lastHealthApplySkipReasonByEntityKey.remove(entityKey);
            levelResolveAttemptCountByEntityKey.remove(entityKey);
            levelResolveAssignmentCountByEntityKey.remove(entityKey);
            Integer trackedEntityId = trackedEntityIndexByEntityKey.remove(entityKey);
            Store<EntityStore> trackedStore = trackedStoreByEntityKey.remove(entityKey);
            deathHandledEntityKeys.remove(entityKey);
            forcedDeathLoggedEntityKeys.remove(entityKey);
            lastResetReasonByEntityKey.put(entityKey, "stale-prune");

            if (trackedEntityId != null && trackedEntityId >= 0) {
                mobLevelingManager.clearEntityLevelOverride(trackedStore, trackedEntityId);
                mobLevelingManager.forgetEntity(trackedStore, trackedEntityId);
            } else {
                mobLevelingManager.forgetEntityByKey(entityKey);
            }
        }
    }

    private void ensureDeadComponentWhenZeroHp(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        long entityKey = resolveTrackingIdentity(ref, commandBuffer).key();

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            forcedDeathLoggedEntityKeys.remove(entityKey);
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || !Float.isFinite(hp.get()) || hp.get() > 0.0001f) {
            forcedDeathLoggedEntityKeys.remove(entityKey);
            return;
        }

        DeathComponent.tryAddComponent(
                commandBuffer,
                ref,
                new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, 0.0f));

        int entityId = ref.getIndex();
        if (forcedDeathLoggedEntityKeys.add(entityKey)) {
            logMobWarn(
                    "forced-death-component",
                    entityId,
                    entityKey,
                    "hp=%.3f max=%.3f",
                    hp.get(),
                    hp.getMax());
        }
    }

    private void handleDeadEntity(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        int entityId = ref.getIndex();
        long entityKey = resolveTrackingIdentity(ref, commandBuffer).key();
        Integer appliedLevel = healthAppliedLevel.get(entityKey);
        float hpBefore = Float.NaN;
        float maxBefore = Float.NaN;

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue before = statMap.get(healthIndex);
            if (before != null) {
                hpBefore = before.get();
                maxBefore = before.getMax();
            }
        }

        logMobInfo(
                "death-detected",
                entityId,
                entityKey,
                "level=%s hpBefore=%.3f maxBefore=%.3f",
                appliedLevel != null ? appliedLevel.toString() : "unknown",
                hpBefore,
                maxBefore);

        clearOrRemoveNameplate(ref, commandBuffer);
    }

    private void ensureDeathHealthApplied(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            long entityKey) {
        Integer appliedLevel = healthAppliedLevel.get(entityKey);
        if (appliedLevel == null || appliedLevel <= 0) {
            return;
        }

        Integer settledLevel = settledHealthLevelByEntityKey.get(entityKey);
        if (settledLevel != null && settledLevel.equals(appliedLevel)) {
            return;
        }

        boolean healthApplied = applyHealthModifier(ref, commandBuffer, appliedLevel, entityKey);
        if (healthApplied) {
            settledHealthLevelByEntityKey.put(entityKey, appliedLevel);
        }
    }

    private void clearLevelingStateOnDeath(Ref<EntityStore> ref, int entityId, long entityKey) {
        if (ref == null || entityId < 0) {
            return;
        }

        healthAppliedLevel.remove(entityKey);
        settledHealthLevelByEntityKey.remove(entityKey);
        appliedNameplateLevelByEntityKey.remove(entityKey);
        nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
        lastKnownEntitySignatureByEntityKey.remove(entityKey);
        lastHealthApplySkipReasonByEntityKey.remove(entityKey);
        levelResolveAttemptCountByEntityKey.remove(entityKey);
        levelResolveAssignmentCountByEntityKey.remove(entityKey);
        trackedEntityIndexByEntityKey.remove(entityKey);
        trackedStoreByEntityKey.remove(entityKey);

        mobLevelingManager.forgetEntity(ref.getStore(), entityId);
    }

    private boolean isAtOrBelowZeroHealth(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return false;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return false;
        }

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        return hp != null && hp.get() <= 0.0001f;
    }

    private boolean applyHealthModifier(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int appliedLevel,
            long entityKey) {
        if (ref == null || commandBuffer == null || mobLevelingManager == null) {
            return false;
        }

        int entityId = ref.getIndex();

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            clearMobHealthScaleModifierForPlayer(ref, commandBuffer, entityId, entityKey);
            return false;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            logHealthApplySkipIfChanged(entityKey, entityId, appliedLevel, "missing-stat-map", "");
            return false;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            logHealthApplySkipIfChanged(entityKey, entityId, appliedLevel, "missing-health-stat", "");
            return false;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();

        float currentValue = healthStat.get();
        float currentMax = healthStat.getMax();
        if (!Float.isFinite(currentValue) || !Float.isFinite(currentMax) || currentMax <= 0.0f) {
            logHealthApplySkipIfChanged(
                    entityKey,
                    entityId,
                    appliedLevel,
                    "invalid-current-health",
                    String.format("value=%.3f max=%.3f", currentValue, currentMax));
            return false;
        }

        statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
        EntityStatValue baselineHealth = statMap.get(healthIndex);
        float baseMax = baselineHealth != null ? baselineHealth.getMax() : currentMax;
        if (!Float.isFinite(baseMax) || baseMax <= 0.0f) {
            baseMax = Math.max(1.0f, currentMax);
        }

        if (!mobLevelingManager.isMobHealthScalingEnabled(ref.getStore())) {
            float ratio = currentMax > 0.0f ? currentValue / currentMax : 1.0f;
            float restoredValue = Math.max(0.0f, Math.min(baseMax, ratio * baseMax));
            if (currentValue <= 0.0f) {
                restoredValue = 0.0f;
            }
            statMap.setStatValue(healthIndex, restoredValue);
            statMap.update();
            EntityStatValue restoredHealth = statMap.get(healthIndex);
            if (restoredHealth != null && Float.isFinite(restoredHealth.getMax()) && restoredHealth.getMax() > 0.0f) {
                mobLevelingManager.recordEntityMaxHealth(entityId, restoredHealth.getMax());
            }
            lastHealthApplySkipReasonByEntityKey.remove(entityKey);
            return true;
        }

        MobLevelingManager.MobHealthScalingResult scaled = mobLevelingManager.computeMobHealthScaling(
                ref,
                ref.getStore(),
                commandBuffer,
                appliedLevel,
                baseMax,
                currentMax,
                currentValue);

        float targetMax = scaled.targetMax();
        float targetValue = scaled.newValue();
        if (!Float.isFinite(targetMax) || targetMax <= 0.0f || !Float.isFinite(targetValue)) {
            logHealthApplySkipIfChanged(
                    entityKey,
                    entityId,
                    appliedLevel,
                    "invalid-scaled-health",
                    String.format("targetMax=%.3f targetValue=%.3f baseMax=%.3f current=%.3f/%.3f",
                            targetMax,
                            targetValue,
                            baseMax,
                            currentValue,
                            currentMax));
            return false;
        }

        float additive = scaled.additive();
        if (Math.abs(additive) > 0.0001f) {
            try {
                StaticModifier modifier = new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, additive);
                statMap.putModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY, modifier);
            } catch (Exception e) {
                logMobWarn(
                        "health-modifier-apply-failed",
                        entityId,
                        entityKey,
                        "level=%d error=%s",
                        appliedLevel,
                        e.toString());
            }
        }

        statMap.setStatValue(healthIndex, targetValue);
        statMap.update();
        EntityStatValue updatedHealth = statMap.get(healthIndex);
        if (updatedHealth != null && Float.isFinite(updatedHealth.getMax()) && updatedHealth.getMax() > 0.0f) {
            mobLevelingManager.recordEntityMaxHealth(entityId, updatedHealth.getMax());
        }

        lastHealthApplySkipReasonByEntityKey.remove(entityKey);
        return true;
    }

    private void clearMobHealthScaleModifierForPlayer(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int entityId,
            long entityKey) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        EntityStatValue before = statMap.get(healthIndex);
        if (before == null) {
            return;
        }

        float previousValue = before.get();
        float previousMax = before.getMax();

        statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);

        EntityStatValue baseline = statMap.get(healthIndex);
        float baselineMax = baseline != null ? baseline.getMax() : previousMax;
        if (!Float.isFinite(previousMax) || previousMax <= 0.0f
                || !Float.isFinite(baselineMax) || baselineMax <= 0.0f) {
            statMap.update();
            return;
        }

        if (Math.abs(previousMax - baselineMax) <= 0.0001f) {
            return;
        }

        float ratio = previousValue / previousMax;
        float restoredValue = Math.max(0.0f, Math.min(baselineMax, ratio * baselineMax));
        if (!Float.isFinite(restoredValue)) {
            restoredValue = Math.max(0.0f, Math.min(baselineMax, previousValue));
        }
        if (previousValue <= 0.0f) {
            restoredValue = 0.0f;
        }

        statMap.setStatValue(healthIndex, restoredValue);
        statMap.update();

        logMobInfo(
                "cleared-player-health-modifier",
                entityId,
                entityKey,
                "prevMax=%.3f newMax=%.3f prevValue=%.3f newValue=%.3f",
                previousMax,
                baselineMax,
                previousValue,
                restoredValue);
    }

    private void clearLevelingStateForEntity(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int entityId,
            long entityKey,
            String reason) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
            statMap.update();
        }

        Integer previousLevel = healthAppliedLevel.get(entityKey);
        healthAppliedLevel.remove(entityKey);
        settledHealthLevelByEntityKey.remove(entityKey);
        appliedNameplateLevelByEntityKey.remove(entityKey);
        lastSeenTimeByEntityKey.remove(entityKey);
        nameplateSyncReadyAtTimeByEntityKey.remove(entityKey);
        lastKnownEntitySignatureByEntityKey.remove(entityKey);
        lastHealthApplySkipReasonByEntityKey.remove(entityKey);
        levelResolveAttemptCountByEntityKey.remove(entityKey);
        levelResolveAssignmentCountByEntityKey.remove(entityKey);
        trackedEntityIndexByEntityKey.remove(entityKey);
        trackedStoreByEntityKey.remove(entityKey);
        deathHandledEntityKeys.remove(entityKey);
        forcedDeathLoggedEntityKeys.remove(entityKey);
        lastResetReasonByEntityKey.put(entityKey, reason != null ? reason : "unspecified");
        logMobInfo(
                "state-reset",
                entityId,
                entityKey,
                "reason=%s previousLevel=%s",
                reason != null ? reason : "unspecified",
                previousLevel != null ? previousLevel.toString() : "none");
        mobLevelingManager.forgetEntity(ref.getStore(), entityId);
    }

    private boolean applyNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            boolean includeLevelInName,
            long entityKey) {
        if (ref == null || commandBuffer == null) {
            return false;
        }

        Integer appliedLevel = healthAppliedLevel.get(entityKey);

        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return false;
        }

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            return false;
        }

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return false;
        }

        if (appliedLevel == null || appliedLevel <= 0) {
            return false;
        }

        if (NameplateBuilderCompatibility.isAvailable()) {
            return NameplateBuilderCompatibility.registerMobLevel(ref.getStore(), ref, appliedLevel);
        }

        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (nameplate == null) {
            return false;
        }

        String baseName = "Mob";
        DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
        if (display != null && display.getDisplayName() != null
                && display.getDisplayName().getAnsiMessage() != null
                && !display.getDisplayName().getAnsiMessage().isBlank()) {
            baseName = display.getDisplayName().getAnsiMessage();
        }

        String label;
        if (includeLevelInName) {
            label = "[Lv." + appliedLevel + "] " + baseName;
        } else {
            label = baseName;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp != null && Float.isFinite(hp.get()) && Float.isFinite(hp.getMax()) && hp.getMax() > 0.0f) {
            int currentHp = Math.max(0, Math.round(hp.get()));
            int maxHp = Math.max(1, Math.round(hp.getMax()));
            label = label + " [HP " + currentHp + "/" + maxHp + "]";
        }

        nameplate.setText(label);
        return true;
    }

    private void clearOrRemoveNameplate(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        if (NameplateBuilderCompatibility.isAvailable()) {
            NameplateBuilderCompatibility.removeMobLevel(ref.getStore(), ref);
            return;
        }

        Nameplate nameplate = commandBuffer.getComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            nameplate.setText("");
        }
    }

    private long toEntityKey(Store<EntityStore> store, int entityId) {
        long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
        long entityPart = Integer.toUnsignedLong(entityId);
        return (storePart << 32) | entityPart;
    }

    private TrackingIdentity resolveTrackingIdentity(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return new TrackingIdentity(-1L, false);
        }

        Store<EntityStore> store = ref.getStore();
        int entityId = ref.getIndex();

        UUIDComponent uuidComponent = null;
        if (commandBuffer != null) {
            uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        }
        if (uuidComponent == null && store != null) {
            uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        }

        if (uuidComponent != null) {
            try {
                UUID uuid = uuidComponent.getUuid();
                if (uuid != null) {
                    long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
                    long uuidPart = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 1);
                    return new TrackingIdentity(uuidPart ^ (storePart << 32), true);
                }
            } catch (Throwable ignored) {
            }
        }

        return new TrackingIdentity(toEntityKey(store, entityId), false);
    }

    private record TrackingIdentity(long key, boolean uuidBacked) {
    }

    private record PlayerChunkViewport(int chunkX, int chunkZ, int radiusChunks) {
    }

    private List<PlayerChunkViewport> snapshotPlayerChunkViewports(Store<EntityStore> currentStore) {
        if (currentStore == null || currentStore.isShutdown()) {
            return List.of();
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return List.of();
        }

        List<PlayerChunkViewport> viewports = new ArrayList<>();
        String currentWorldId = mobLevelingManager != null
                ? mobLevelingManager.resolveWorldIdentifier(currentStore)
                : null;

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            if (!isSameStoreOrWorld(currentStore, currentWorldId, playerStore)) {
                continue;
            }

            TransformComponent transform = playerStore != null
                    ? playerStore.getComponent(playerEntityRef, TransformComponent.getComponentType())
                    : null;
            Vector3d playerPosition = transform != null ? transform.getPosition() : null;
            if (playerPosition == null) {
                continue;
            }

            int viewRadiusChunks = resolvePlayerViewRadiusChunks(playerStore, playerEntityRef);
            if (viewRadiusChunks <= 0) {
                continue;
            }

            int chunkX = blockToChunk(playerPosition.getX());
            int chunkZ = blockToChunk(playerPosition.getZ());
            int viewportRadius = viewRadiusChunks + PLAYER_VIEW_RADIUS_BUFFER_CHUNKS;
            viewports.add(new PlayerChunkViewport(chunkX, chunkZ, viewportRadius));
        }

        return viewports;
    }

    private boolean isSameStoreOrWorld(Store<EntityStore> currentStore,
            String currentWorldId,
            Store<EntityStore> candidateStore) {
        if (currentStore == null || candidateStore == null) {
            return false;
        }

        if (currentStore == candidateStore) {
            return true;
        }

        if (mobLevelingManager == null) {
            return false;
        }

        if (currentWorldId == null || currentWorldId.isBlank()) {
            return false;
        }

        String candidateWorldId = mobLevelingManager.resolveWorldIdentifier(candidateStore);
        if (candidateWorldId == null || candidateWorldId.isBlank()) {
            return false;
        }

        return currentWorldId.equalsIgnoreCase(candidateWorldId);
    }

    private int resolvePlayerViewRadiusChunks(Store<EntityStore> playerStore, Ref<EntityStore> playerRef) {
        if (playerStore == null || playerRef == null) {
            return MIN_PLAYER_VIEW_RADIUS_CHUNKS;
        }

        Player player = playerStore.getComponent(playerRef, Player.getComponentType());
        int configuredRadius = player != null ? player.getViewRadius() : 0;
        return Math.max(MIN_PLAYER_VIEW_RADIUS_CHUNKS, configuredRadius);
    }

    private boolean isWithinNearbyPlayerChunkViewport(Ref<EntityStore> entityRef,
            CommandBuffer<EntityStore> commandBuffer,
            List<PlayerChunkViewport> playerChunkViewports) {
        if (entityRef == null || playerChunkViewports == null || playerChunkViewports.isEmpty()) {
            return false;
        }

        Vector3d mobPosition = resolvePosition(entityRef, commandBuffer);
        if (mobPosition == null) {
            return false;
        }

        int mobChunkX = blockToChunk(mobPosition.getX());
        int mobChunkZ = blockToChunk(mobPosition.getZ());
        for (PlayerChunkViewport viewport : playerChunkViewports) {
            if (viewport == null) {
                continue;
            }

            int radiusChunks = Math.max(MIN_PLAYER_VIEW_RADIUS_CHUNKS, viewport.radiusChunks());
            int dx = Math.abs(mobChunkX - viewport.chunkX());
            int dz = Math.abs(mobChunkZ - viewport.chunkZ());
            if (dx <= radiusChunks && dz <= radiusChunks) {
                return true;
            }
        }

        return false;
    }

    private Vector3d resolvePosition(Ref<EntityStore> entityRef, CommandBuffer<EntityStore> commandBuffer) {
        if (entityRef == null) {
            return null;
        }

        TransformComponent transform = null;
        if (commandBuffer != null) {
            transform = commandBuffer.getComponent(entityRef, TransformComponent.getComponentType());
        }
        if (transform == null) {
            Store<EntityStore> store = entityRef.getStore();
            if (store != null) {
                transform = store.getComponent(entityRef, TransformComponent.getComponentType());
            }
        }

        return transform != null ? transform.getPosition() : null;
    }

    private int blockToChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> CHUNK_BIT_SHIFT;
    }

    private Integer resolveAndAssignLevelOnce(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            int entityId,
            long entityKey,
            String signature,
            long currentStep) {
        if (ref == null || commandBuffer == null || mobLevelingManager == null || entityId < 0) {
            return null;
        }

        int resolveAttempts = levelResolveAttemptCountByEntityKey.getOrDefault(entityKey, 0) + 1;
        levelResolveAttemptCountByEntityKey.put(entityKey, resolveAttempts);

        Integer resolvedLevel = mobLevelingManager.resolveMobLevelForEntity(
                ref,
                store,
                commandBuffer,
                resolveAttempts);
        String source = "resolveMobLevelForEntity";
        if (resolvedLevel == null || resolvedLevel <= 0) {
            int fallbackLevel = mobLevelingManager.resolveMobLevel(ref, commandBuffer);
            if (fallbackLevel > 0) {
                resolvedLevel = fallbackLevel;
                source = "fallback-resolveMobLevel";
            }
        }

        if (resolvedLevel == null || resolvedLevel <= 0) {
            logMobWarn(
                    "level-resolve-failed",
                    entityId,
                    entityKey,
                    "attempts=%d signature=%s",
                    resolveAttempts,
                    signature != null ? signature : "unknown");
            return null;
        }

        healthAppliedLevel.put(entityKey, resolvedLevel);
        int resolveAssignments = levelResolveAssignmentCountByEntityKey.getOrDefault(entityKey, 0);
        boolean levelSet = mobLevelingManager.setEntityLevelOverrideIfChanged(ref.getStore(), entityId, resolvedLevel);
        if (levelSet) {
            resolveAssignments = resolveAssignments + 1;
            levelResolveAssignmentCountByEntityKey.put(entityKey, resolveAssignments);
        } else if (resolveAssignments <= 0) {
            resolveAssignments = 1;
            levelResolveAssignmentCountByEntityKey.put(entityKey, resolveAssignments);
        }

        logMobFine(
                "level-assigned",
                entityId,
                entityKey,
                "level=%d source=%s attempts=%d assignments=%d signature=%s",
                resolvedLevel,
                source,
                resolveAttempts,
                resolveAssignments,
                signature != null ? signature : "unknown");

        if (resolveAssignments > 1) {
            logMobWarn(
                    "level-assignment-reentry",
                    entityId,
                    entityKey,
                    "attempts=%d assignments=%d level=%d source=%s lastResetReason=%s step=%d",
                    resolveAttempts,
                    resolveAssignments,
                    resolvedLevel,
                    source,
                    lastResetReasonByEntityKey.getOrDefault(entityKey, "unknown"),
                    currentStep);
        }

        return resolvedLevel;
    }

    private void logHealthApplySkipIfChanged(long entityKey,
            int entityId,
            int level,
            String reason,
            String details) {
        if (reason == null || reason.isBlank()) {
            return;
        }

        if ("cached-level-unchanged".equals(reason)) {
            return;
        }

        String normalizedDetails = details == null ? "" : details.trim();
        String marker = "cached-level-unchanged".equals(reason)
                ? reason
                : (normalizedDetails.isBlank() ? reason : reason + "|" + normalizedDetails);
        String previousMarker = lastHealthApplySkipReasonByEntityKey.get(entityKey);
        if (marker.equals(previousMarker)) {
            return;
        }

        lastHealthApplySkipReasonByEntityKey.put(entityKey, marker);
        if (normalizedDetails.isBlank()) {
            logMobInfo(
                    "health-apply-skipped",
                    entityId,
                    entityKey,
                    "level=%d reason=%s",
                    level,
                    reason);
        } else {
            logMobInfo(
                    "health-apply-skipped",
                    entityId,
                    entityKey,
                    "level=%d reason=%s details=%s",
                    level,
                    reason,
                    normalizedDetails);
        }
    }

    private void logMobFine(String event, int entityId, long entityKey, String detailFormat, Object... detailArgs) {
        String details = formatDetails(detailFormat, detailArgs);
        LOGGER.atFine().log(
                "MobLevelDiag event=%s target=%d key=%d step=%d %s",
                event,
                entityId,
                entityKey,
                systemStepCounter,
                details);
    }

    private void logMobInfo(String event, int entityId, long entityKey, String detailFormat, Object... detailArgs) {
        String details = formatDetails(detailFormat, detailArgs);
        LOGGER.atInfo().log(
                "MobLevelDiag event=%s target=%d key=%d step=%d %s",
                event,
                entityId,
                entityKey,
                systemStepCounter,
                details);
    }

    private void logMobWarn(String event, int entityId, long entityKey, String detailFormat, Object... detailArgs) {
        String details = formatDetails(detailFormat, detailArgs);
        LOGGER.atWarning().log(
                "MobLevelDiag event=%s target=%d key=%d step=%d %s",
                event,
                entityId,
                entityKey,
                systemStepCounter,
                details);
    }

    private String formatDetails(String detailFormat, Object... detailArgs) {
        if (detailFormat == null || detailFormat.isBlank()) {
            return "details=none";
        }
        try {
            return String.format(Locale.ROOT, detailFormat, detailArgs);
        } catch (Exception ex) {
            return "details-format-error=" + ex.getClass().getSimpleName();
        }
    }

    private String buildEntitySignature(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            NPCEntity npcEntity) {
        if (ref == null || commandBuffer == null || npcEntity == null) {
            return "unknown";
        }

        String npcType = "unknown";
        try {
            String rawType = npcEntity.getNPCTypeId();
            if (rawType != null && !rawType.isBlank()) {
                npcType = rawType.trim();
            }
        } catch (Throwable ignored) {
        }

        String worldId = "unknown-world";
        if (mobLevelingManager != null && ref != null) {
            try {
                String resolvedWorld = mobLevelingManager.resolveWorldIdentifier(ref.getStore());
                if (resolvedWorld != null && !resolvedWorld.isBlank()) {
                    worldId = resolvedWorld.trim();
                }
            } catch (Throwable ignored) {
            }
        }

        return npcType + "@" + worldId;
    }
}
