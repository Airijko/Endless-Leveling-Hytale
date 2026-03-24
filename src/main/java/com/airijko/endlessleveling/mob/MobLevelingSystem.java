package com.airijko.endlessleveling.mob;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies mob-level stat scaling. For now mobs are hard-coded to level 10.
 * Scales Health and Damage (if a "Damage" stat exists) using the multipliers
 * provided by `LevelingManager`.
 */
public class MobLevelingSystem extends DelayedSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Query<EntityStore> ENTITY_QUERY = Query.any();
    private static final String MOB_HEALTH_SCALE_MODIFIER_KEY = "EL_MOB_HEALTH_SCALE";
    private static final String MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY = "EL_MOB_AUGMENT_LIFE_FORCE";
    private static final float SYSTEM_INTERVAL_SECONDS = 0.75f;
    private static final long STALE_ENTITY_TTL_MILLIS = 100_000L;
    private static final long PASSIVE_STAT_TICK_INTERVAL_MILLIS = 1000L;
    private static final long FLOW_HEALTH_RETRY_INTERVAL_MILLIS = 3000L;
    private static final long FLOW_HEALTH_LOG_COOLDOWN_MILLIS = 5000L;
    private static final int CHUNK_BIT_SHIFT = 5;
    private static final int MIN_PLAYER_VIEW_RADIUS_CHUNKS = 1;
    private static final int PLAYER_VIEW_RADIUS_BUFFER_CHUNKS = 1;
    private static final long SUMMON_HEALTH_ANOMALY_LOG_COOLDOWN_MS = 3000L;
    private static final String DEBUG_SECTION_MOB_LEVEL_FLOW = "mob_level_flow";
    private static final String DEBUG_SECTION_MOB_COMMON_DEFENSE = "mob_common_defense";

    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();

    private final AtomicBoolean fullMobRescaleRequested = new AtomicBoolean(false);
    private long systemTimeMillis = 0L;
    private final Map<Long, EntityRuntimeState> entityStates = new ConcurrentHashMap<>();
    private final Map<Integer, Long> summonHealthAnomalyLogTimes = new ConcurrentHashMap<>();

    public MobLevelingSystem() {
        super(SYSTEM_INTERVAL_SECONDS);
    }

    public void shutdownRuntimeState() {
        cleanupStoreRuntimeState(null);
        entityStates.clear();
        summonHealthAnomalyLogTimes.clear();
        if (mobLevelingManager != null) {
            mobLevelingManager.shutdownRuntimeState();
        }
    }

    public void requestFullMobRescale() {
        fullMobRescaleRequested.set(true);
    }

    @Override
    public void delayedTick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            cleanupStoreRuntimeState(store);
            return;
        }

        if (mobLevelingManager == null || !mobLevelingManager.isMobLevelingEnabled())
            return;

        boolean showMobLevelUi = mobLevelingManager.shouldRenderMobNameplate();
        boolean showLevelInNameplate = mobLevelingManager.shouldShowMobNameplateLevel();
        boolean showNameInNameplate = mobLevelingManager.shouldShowMobNameplateName();
        boolean showHealthInNameplate = mobLevelingManager.shouldShowMobNameplateHealth();
        boolean renderAnyNameplateText = showMobLevelUi
            && (showLevelInNameplate || showNameInNameplate || showHealthInNameplate);
        boolean shouldResetAllMobs = fullMobRescaleRequested.getAndSet(false);

        if (shouldResetAllMobs) {
            mobLevelingManager.clearAllEntityLevelOverrides();
            entityStates.clear();
        }

        long elapsedMillis = Math.max(1L, Math.round(Math.max(0.0f, deltaSeconds) * 1000.0f));
        long currentTimeMillis = (systemTimeMillis += elapsedMillis);
        List<PlayerChunkViewport> playerChunkViewports = snapshotPlayerChunkViewports(store);

        if (playerChunkViewports.isEmpty() && !shouldResetAllMobs) {
            pruneStaleEntities(currentTimeMillis);
            return;
        }

        store.forEachChunk(ENTITY_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        processEntity(
                                chunk.getReferenceTo(i),
                                commandBuffer,
                                store,
                                renderAnyNameplateText,
                                showLevelInNameplate,
                                showNameInNameplate,
                                showHealthInNameplate,
                                currentTimeMillis,
                            playerChunkViewports);
                    }
                });
    }

    private void cleanupStoreRuntimeState(Store<EntityStore> store) {
        if (entityStates.isEmpty()) {
            if (store != null && mobLevelingManager != null) {
                mobLevelingManager.clearTierLockForStore(store);
            }
            return;
        }

        Iterator<Map.Entry<Long, EntityRuntimeState>> iterator = entityStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, EntityRuntimeState> entry = iterator.next();
            Long entityKey = entry.getKey();
            EntityRuntimeState state = entry.getValue();
            if (state == null) {
                iterator.remove();
                continue;
            }

            if (store != null && state.trackedStore != store) {
                continue;
            }

            iterator.remove();

            if (state.trackedEntityId >= 0) {
                summonHealthAnomalyLogTimes.remove(state.trackedEntityId);
                if (mobLevelingManager != null) {
                    mobLevelingManager.clearEntityLevelOverride(state.trackedStore, state.trackedEntityId);
                }
            }
            if (mobLevelingManager != null && entityKey != null) {
                mobLevelingManager.forgetEntityByKey(entityKey);
            }
        }

        if (store != null && mobLevelingManager != null) {
            mobLevelingManager.clearTierLockForStore(store);
        }
    }

    private void processEntity(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            Store<EntityStore> store,
            boolean showMobLevelUi,
            boolean showLevelInNameplate,
            boolean showNameInNameplate,
            boolean showHealthInNameplate,
            long currentTimeMillis,
                    List<PlayerChunkViewport> playerChunkViewports) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        TrackingIdentity trackingIdentity = resolveTrackingIdentity(ref, commandBuffer);
        long entityKey = trackingIdentity.key();
        EntityRuntimeState state = getOrCreateEntityState(entityKey, ref.getIndex(), store);
        state.lastSeenTimeMillis = currentTimeMillis;

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            return;
        }

        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        if (mobLevelingManager.isEntityBlacklisted(ref, store, commandBuffer)) {
            clearTrackedNameplateIfNeeded(ref, commandBuffer, state);
            return;
        }

        if (!isWithinActivePlayerChunk(ref, commandBuffer, playerChunkViewports)) {
            clearTrackedNameplateIfNeeded(ref, commandBuffer, state);
            return;
        }

        ensureDeadComponentWhenZeroHp(ref, commandBuffer);
        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            clearTrackedNameplateIfNeeded(ref, commandBuffer, state);
            return;
        }

        Integer mobLevel;
        if (state.appliedLevel > 0) {
            // Hot path optimization: once assigned, reuse the cached level and skip expensive
            // world/viewport resolution every tick.
            mobLevel = state.appliedLevel;
        } else {
            mobLevel = resolveAndAssignLevelOnce(ref, store, commandBuffer, ref.getIndex(), state);
        }

        if (mobLevel == null || mobLevel <= 0) {
            clearTrackedNameplateIfNeeded(ref, commandBuffer, state);
            return;
        }

        // Keep the manager-side level snapshot fresh so other systems (death XP, combat probes,
        // announcers) read the same assigned level that mob flow is using, even if they execute
        // outside this system's runtime-state map.
        mobLevelingManager.recordEntityResolvedLevel(entityKey, mobLevel);

        if (state.appliedLevel <= 0) {
            state.appliedLevel = mobLevel;
        }

        // Apply/retry mob augment registration and passive-stat ticks before health scaling.
        // This guarantees LIFE_FORCE and other health-related passive bonuses are available
        // when applyHealthModifier snapshots and applies max-health layers.
        applyMobAugments(ref, store, commandBuffer, currentTimeMillis);

        if (showHealthInNameplate) {
            if (state.augmentHealthReconcilePending && state.settledHealthLevel == mobLevel) {
                // Augments became available after initial settle; do exactly one reconcile apply
                // so augmented health layers are included without recurring per-tick reapplication.
                state.settledHealthLevel = 0;
                state.nextHealthApplyAttemptMillis = currentTimeMillis;
            }

            if (state.settledHealthLevel != mobLevel) {
                boolean attemptedHealthApply = false;
                boolean healthApplied = false;
                if (currentTimeMillis >= state.nextHealthApplyAttemptMillis) {
                    attemptedHealthApply = true;
                    healthApplied = applyHealthModifier(ref, commandBuffer, mobLevel, entityKey);
                    if (healthApplied) {
                        state.settledHealthLevel = mobLevel;
                        state.nextHealthApplyAttemptMillis = currentTimeMillis;
                        state.augmentHealthReconcilePending = false;
                    } else {
                        state.nextHealthApplyAttemptMillis = currentTimeMillis + FLOW_HEALTH_RETRY_INTERVAL_MILLIS;
                    }
                }

                if (isDebugSectionEnabled(DEBUG_SECTION_MOB_LEVEL_FLOW)
                        && (healthApplied || (attemptedHealthApply
                                && currentTimeMillis - state.lastHealthFlowLogMillis >= FLOW_HEALTH_LOG_COOLDOWN_MILLIS))) {
                    LOGGER.atInfo().log(
                            "[MOB_LEVEL_FLOW] entity=%d uuidBacked=%s phase=health level=%d applied=%s settled=%d nextRetryInMs=%d",
                            ref.getIndex(),
                            trackingIdentity.uuidBacked(),
                            mobLevel,
                            healthApplied,
                            state.settledHealthLevel,
                            Math.max(0L, state.nextHealthApplyAttemptMillis - currentTimeMillis));
                    state.lastHealthFlowLogMillis = currentTimeMillis;
                }
            }
        } else {
            // When health display is disabled, skip all health scaling work and clear scheduling
            // state so no health retries/ticks are attempted until re-enabled.
            state.settledHealthLevel = 0;
            state.nextHealthApplyAttemptMillis = 0L;
            state.augmentHealthReconcilePending = false;
        }

        if (showMobLevelUi) {
                EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                EntityStatValue hp = (showHealthInNameplate && statMap != null)
                    ? statMap.get(DefaultEntityStatTypes.getHealth())
                    : null;
            float currentHpForNameplate = hp != null ? hp.get() : Float.NaN;
            float currentMaxHpForNameplate = hp != null ? hp.getMax() : Float.NaN;
            boolean hasFiniteHealthForNameplate = Float.isFinite(currentHpForNameplate)
                && Float.isFinite(currentMaxHpForNameplate)
                && currentMaxHpForNameplate > 0.0f;

            boolean healthChangedForNameplate =
                showHealthInNameplate
                    && hasFiniteHealthForNameplate
                    && (Math.abs(currentHpForNameplate - state.lastNameplateHealthValue) > 0.0001f
                        || Math.abs(currentMaxHpForNameplate - state.lastNameplateMaxHealthValue) > 0.0001f);

            if (state.lastAppliedNameplateLevel != mobLevel
                    || state.lastAppliedShowLevelInNameplate != showLevelInNameplate
                    || state.lastAppliedShowNameInNameplate != showNameInNameplate
                    || state.lastAppliedShowHealthInNameplate != showHealthInNameplate
                    || state.lastAppliedNameplateText == null
                || healthChangedForNameplate) {
                boolean applied = applyNameplate(
                        ref,
                        commandBuffer,
                        showLevelInNameplate,
                        showNameInNameplate,
                        showHealthInNameplate,
                        mobLevel);
                state.lastAppliedNameplateLevel = mobLevel;
                state.lastAppliedShowLevelInNameplate = showLevelInNameplate;
                state.lastAppliedShowNameInNameplate = showNameInNameplate;
                state.lastAppliedShowHealthInNameplate = showHealthInNameplate;
                state.lastNameplateHealthValue = hasFiniteHealthForNameplate ? currentHpForNameplate : Float.NaN;
                state.lastNameplateMaxHealthValue = hasFiniteHealthForNameplate ? currentMaxHpForNameplate : Float.NaN;
                if (isDebugSectionEnabled(DEBUG_SECTION_MOB_LEVEL_FLOW)) {
                    LOGGER.atInfo().log(
                            "[MOB_LEVEL_FLOW] entity=%d uuidBacked=%s phase=nameplate level=%d applied=%s hp=%.3f max=%.3f refreshedByHealthChange=%s finiteHealth=%s",
                            ref.getIndex(),
                            trackingIdentity.uuidBacked(),
                            mobLevel,
                            applied,
                            currentHpForNameplate,
                            currentMaxHpForNameplate,
                            healthChangedForNameplate,
                            hasFiniteHealthForNameplate);
                }
            }
        } else {
            clearTrackedNameplateIfNeeded(ref, commandBuffer, state);
        }

        if (isDebugSectionEnabled(DEBUG_SECTION_MOB_LEVEL_FLOW) && !state.flowInitializedLogged) {
            state.flowInitializedLogged = true;
            LOGGER.atInfo().log(
                    "[MOB_LEVEL_FLOW] entity=%d uuidBacked=%s initialized=true level=%d",
                    ref.getIndex(),
                    trackingIdentity.uuidBacked(),
                    mobLevel);
        }
    }

    private void pruneStaleEntities(long currentTimeMillis) {
        if (entityStates.isEmpty()) {
            return;
        }

        long expiryTimeMillis = currentTimeMillis - STALE_ENTITY_TTL_MILLIS;
        Iterator<Map.Entry<Long, EntityRuntimeState>> iterator = entityStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, EntityRuntimeState> entry = iterator.next();
            Long entityKey = entry.getKey();
            EntityRuntimeState state = entry.getValue();
            if (entityKey == null || state == null || state.lastSeenTimeMillis >= expiryTimeMillis) {
                continue;
            }

            iterator.remove();

            if (state.trackedEntityId >= 0) {
                summonHealthAnomalyLogTimes.remove(state.trackedEntityId);
                mobLevelingManager.clearEntityLevelOverride(state.trackedStore, state.trackedEntityId);
            }
            mobLevelingManager.forgetEntityByKey(entityKey);
        }
    }

    private void ensureDeadComponentWhenZeroHp(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || !Float.isFinite(hp.get()) || hp.get() > 0.0001f) {
            return;
        }

        DeathComponent.tryAddComponent(
                commandBuffer,
                ref,
                new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, 0.0f));
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
            clearMobHealthScaleModifierForPlayer(ref, commandBuffer);
            return false;
        }

        if (ArmyOfTheDeadPassive.isManagedSummon(ref, ref.getStore(), commandBuffer)) {
            EntityStatMap summonStatMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
            if (summonStatMap == null) {
                return false;
            }

            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue before = summonStatMap.get(healthIndex);
            float beforeCurrent = before != null ? before.get() : -1.0f;
            float beforeMax = before != null ? before.getMax() : -1.0f;

            summonStatMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
            summonStatMap.removeModifier(healthIndex, MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY);
            EntityStatValue summonHp = summonStatMap.get(healthIndex);
            if (summonHp != null && Float.isFinite(summonHp.getMax()) && summonHp.getMax() > 0.0f) {
                summonStatMap.setStatValue(healthIndex, summonHp.getMax());
            }
            summonStatMap.update();

            EntityStatValue updatedSummonHp = summonStatMap.get(healthIndex);
            if (updatedSummonHp != null && Float.isFinite(updatedSummonHp.getMax())
                    && updatedSummonHp.getMax() > 0.0f) {
                mobLevelingManager.recordEntityMaxHealth(entityKey, updatedSummonHp.getMax());

                float afterCurrent = updatedSummonHp.get();
                float afterMax = updatedSummonHp.getMax();
                if (Float.isFinite(afterCurrent)
                        && Float.isFinite(afterMax)
                        && afterMax > 0.0f
                        && afterCurrent < afterMax - 0.5f
                        && shouldLogSummonHealthAnomaly(entityId)) {
                    LOGGER.atWarning().log(
                            "[ARMY_OF_THE_DEAD][DEBUG-HP][LEVELING] Managed summon still below max after normalize entity=%d before=%.3f/%.3f after=%.3f/%.3f",
                            entityId,
                            beforeCurrent,
                            beforeMax,
                            afterCurrent,
                            afterMax);
                }
            }
            return true;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return false;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return false;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();

        float currentValue = healthStat.get();
        float currentMax = healthStat.getMax();
        if (!Float.isFinite(currentValue) || !Float.isFinite(currentMax) || currentMax <= 0.0f) {
            return false;
        }

        statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
        statMap.removeModifier(healthIndex, MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY);
        EntityStatValue baselineHealth = statMap.get(healthIndex);
        float baselineRead = baselineHealth != null ? baselineHealth.getMax() : currentMax;
        if (!Float.isFinite(baselineRead) || baselineRead <= 0.0f) {
            baselineRead = Math.max(1.0f, currentMax);
        }
        // Anchor baseMax to the value captured on first application (before any augment percent-health
        // passives have run). Augment passives such as goliath (+20%) and raid_boss (+50%) add their own
        // modifier keys that we intentionally leave in the map so they remain effective. Without this
        // anchor, those augment modifiers would be included in baseMax on every subsequent tick, causing
        // the level-scaling multiplier to compound on an ever-growing base and produce billions of HP.
        mobLevelingManager.cacheTrueBaseHealth(entityKey, baselineRead);
        Float cachedBase = mobLevelingManager.getTrueBaseHealth(entityKey);
        float baseMax = (cachedBase != null && Float.isFinite(cachedBase) && cachedBase > 0.0f)
                ? cachedBase
                : baselineRead;

        float lifeForceBonus = resolveMobLifeForceHealthBonus(ref, commandBuffer);

        if (!mobLevelingManager.isMobHealthScalingEnabled(ref.getStore())) {
            float targetMax = Math.max(1.0f, baseMax + lifeForceBonus);
            if (lifeForceBonus > 0.0001f) {
                try {
                    StaticModifier lifeForceModifier = new StaticModifier(
                            ModifierTarget.MAX,
                            CalculationType.ADDITIVE,
                            lifeForceBonus);
                    statMap.putModifier(healthIndex, MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY, lifeForceModifier);
                } catch (Exception e) {
                    return false;
                }
            }

            float ratio = currentMax > 0.0f ? currentValue / currentMax : 1.0f;
            float restoredValue = Math.max(0.0f, Math.min(targetMax, ratio * targetMax));
            if (currentValue <= 0.0f) {
                restoredValue = 0.0f;
            }
            statMap.setStatValue(healthIndex, restoredValue);
            statMap.update();
            EntityStatValue restoredHealth = statMap.get(healthIndex);
            if (restoredHealth != null && Float.isFinite(restoredHealth.getMax()) && restoredHealth.getMax() > 0.0f) {
                mobLevelingManager.recordEntityMaxHealth(entityKey, restoredHealth.getMax());
                mobLevelingManager.recordEntityHealthComposition(
                    entityKey,
                        baseMax,
                        baseMax,
                        lifeForceBonus,
                        restoredHealth.getMax());
                if (isDebugSectionEnabled(DEBUG_SECTION_MOB_COMMON_DEFENSE)) {
                    LOGGER.atInfo().log(
                            "[MOB_COMMON_DEFENSE][HEALTH_AUDIT] entity=%d level=%d scalingEnabled=false baseMax=%.3f scaledMax=%.3f lifeForceBonus=%.3f expectedCombinedMax=%.3f current=%.3f actualMax=%.3f",
                            entityId,
                            appliedLevel,
                            baseMax,
                            baseMax,
                            lifeForceBonus,
                            targetMax,
                            restoredHealth.get(),
                            restoredHealth.getMax());
                }
            }
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
        float targetMaxWithAugments = Math.max(1.0f, targetMax + lifeForceBonus);
        float ratio = currentMax > 0.0f ? currentValue / currentMax : 1.0f;
        float targetValue = Math.max(0.0f, Math.min(targetMaxWithAugments, ratio * targetMaxWithAugments));
        if (currentValue <= 0.0f) {
            targetValue = 0.0f;
        }
        if (!Float.isFinite(targetMax) || targetMax <= 0.0f || !Float.isFinite(targetValue)) {
            return false;
        }

        float additive = scaled.additive();
        if (Math.abs(additive) > 0.0001f) {
            try {
                StaticModifier modifier = new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, additive);
                statMap.putModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY, modifier);
            } catch (Exception e) {
                return false;
            }
        }

        if (lifeForceBonus > 0.0001f) {
            try {
                StaticModifier lifeForceModifier = new StaticModifier(
                        ModifierTarget.MAX,
                        CalculationType.ADDITIVE,
                        lifeForceBonus);
                statMap.putModifier(healthIndex, MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY, lifeForceModifier);
            } catch (Exception e) {
                return false;
            }
        }

        statMap.setStatValue(healthIndex, targetValue);
        statMap.update();
        EntityStatValue updatedHealth = statMap.get(healthIndex);
        if (updatedHealth != null && Float.isFinite(updatedHealth.getMax()) && updatedHealth.getMax() > 0.0f) {
            mobLevelingManager.recordEntityMaxHealth(entityKey, updatedHealth.getMax());
            mobLevelingManager.recordEntityHealthComposition(
                entityKey,
                baseMax,
                targetMax,
                lifeForceBonus,
                updatedHealth.getMax());
            LOGGER.atInfo().log(
                    "[MOB_HEALTH_LAYER_DEBUG] entity=%d level=%d baseMax=%.3f scaledMax=%.3f lifeForceBonus=%.3f finalMax=%.3f current=%.3f ratio=%.4f",
                    entityId,
                    appliedLevel,
                    baseMax,
                    targetMax,
                    lifeForceBonus,
                    updatedHealth.getMax(),
                    updatedHealth.get(),
                    ratio);
            if (isDebugSectionEnabled(DEBUG_SECTION_MOB_COMMON_DEFENSE)) {
                LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE][HEALTH_AUDIT] entity=%d level=%d scalingEnabled=true baseMax=%.3f scaledMax=%.3f lifeForceBonus=%.3f expectedCombinedMax=%.3f current=%.3f actualMax=%.3f",
                        entityId,
                        appliedLevel,
                        baseMax,
                        targetMax,
                        lifeForceBonus,
                        targetMaxWithAugments,
                        updatedHealth.get(),
                        updatedHealth.getMax());
            }
        }
        return true;
    }

    private void clearMobHealthScaleModifierForPlayer(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
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
        statMap.removeModifier(healthIndex, MOB_AUGMENT_LIFE_FORCE_MODIFIER_KEY);

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
    }

    private float resolveMobLifeForceHealthBonus(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return 0.0f;
        }

        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null || uuidComponent.getUuid() == null) {
            return 0.0f;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return 0.0f;
        }

        MobAugmentExecutor mobAugmentExecutor = plugin.getMobAugmentExecutor();
        if (mobAugmentExecutor == null) {
            return 0.0f;
        }

        double bonus = mobAugmentExecutor.getAttributeBonus(uuidComponent.getUuid(), SkillAttributeType.LIFE_FORCE);
        if (!Double.isFinite(bonus) || bonus <= 0.0D) {
            return 0.0f;
        }
        return (float) bonus;
    }

    private void tickMobPassiveAugmentStats(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || store == null || commandBuffer == null) {
            return;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return;
        }

        MobAugmentExecutor executor = plugin.getMobAugmentExecutor();
        if (executor == null) {
            return;
        }

        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null || uuidComponent.getUuid() == null) {
            return;
        }

        UUID entityUuid = uuidComponent.getUuid();
        if (!executor.hasMobAugments(entityUuid)) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        executor.tickPassiveStats(entityUuid, ref, commandBuffer, statMap);
    }

    private boolean ensureMobAugmentsRegistered(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || store == null || commandBuffer == null || mobLevelingManager == null) {
            return false;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getAugmentManager() == null || plugin.getAugmentRuntimeManager() == null) {
            return false;
        }

        MobAugmentExecutor executor = plugin.getMobAugmentExecutor();
        if (executor == null) {
            return false;
        }

        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null || uuidComponent.getUuid() == null) {
            return false;
        }

        UUID mobUuid = uuidComponent.getUuid();
        if (executor.hasMobAugments(mobUuid)) {
            return false;
        }

        List<String> augmentIds = mobLevelingManager.getMobOverrideAugmentIds(ref, store, commandBuffer);
        if (augmentIds == null || augmentIds.isEmpty()) {
            return false;
        }

        executor.registerMobAugments(
                mobUuid,
                augmentIds,
                plugin.getAugmentManager(),
                plugin.getAugmentRuntimeManager());
        return true;
    }

    private void applyMobAugments(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            long currentTimeMillis) {
        if (ref == null || store == null || commandBuffer == null) {
            return;
        }

        TrackingIdentity trackingIdentity = resolveTrackingIdentity(ref, commandBuffer);
        long entityKey = trackingIdentity.key();
        EntityRuntimeState state = getOrCreateEntityState(entityKey, ref.getIndex(), store);
        state.lastSeenTimeMillis = currentTimeMillis;

        if (state.nextMobAugmentRegistrationCheckMillis <= 0L
                || currentTimeMillis >= state.nextMobAugmentRegistrationCheckMillis) {
            boolean alreadyInitialized = state.mobAugmentsInitialized;
            boolean registeredNow = ensureMobAugmentsRegistered(ref, store, commandBuffer);
            if (registeredNow) {
                state.mobAugmentsInitialized = true;
                if (!alreadyInitialized) {
                    state.augmentHealthReconcilePending = true;
                }
                state.nextMobAugmentRegistrationCheckMillis = 0L;
                if (isDebugSectionEnabled(DEBUG_SECTION_MOB_LEVEL_FLOW)) {
                    LOGGER.atInfo().log(
                            "[MOB_LEVEL_FLOW] entity=%d uuidBacked=%s phase=augments registered=%s",
                            ref.getIndex(),
                            trackingIdentity.uuidBacked(),
                            true);
                }
            } else {
                state.nextMobAugmentRegistrationCheckMillis = currentTimeMillis + 3000L;
            }
        }

        if (state.lastPassiveStatTickMillis > 0L
                && currentTimeMillis - state.lastPassiveStatTickMillis < PASSIVE_STAT_TICK_INTERVAL_MILLIS) {
            return;
        }

        tickMobPassiveAugmentStats(ref, store, commandBuffer);
        state.lastPassiveStatTickMillis = currentTimeMillis;
    }

        private boolean applyNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            boolean showLevelInNameplate,
            boolean showNameInNameplate,
            boolean showHealthInNameplate,
            int mobLevel) {
        if (ref == null || commandBuffer == null) {
            return false;
        }

        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return false;
        }

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return false;
        }

        String baseName = "Mob";
        DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
        if (display != null && display.getDisplayName() != null
                && display.getDisplayName().getAnsiMessage() != null
                && !display.getDisplayName().getAnsiMessage().isBlank()) {
            baseName = display.getDisplayName().getAnsiMessage();
        }

        StringBuilder labelBuilder = new StringBuilder();
        if (showLevelInNameplate) {
            labelBuilder.append("[Lv.").append(mobLevel).append("] ");
        }
        if (showNameInNameplate) {
            labelBuilder.append(baseName);
        }
        
        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (showHealthInNameplate && statMap != null) {
            EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
            float hpValue = hp != null ? hp.get() : Float.NaN;
            float hpMax = hp != null ? hp.getMax() : Float.NaN;
            if (Float.isFinite(hpValue) && Float.isFinite(hpMax) && hpMax > 0.0f) {
                if (labelBuilder.length() > 0) {
                    labelBuilder.append(" ");
                }
                labelBuilder.append(" [").append(Math.round(hpValue)).append("/").append(Math.round(hpMax)).append("]");
            }
        }

        String label = labelBuilder.toString();
        if (label.isBlank()) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return false;
        }

        TrackingIdentity identity = resolveTrackingIdentity(ref, commandBuffer);
        EntityRuntimeState state = getOrCreateEntityState(identity.key(), ref.getIndex(), ref.getStore());
        state.lastAppliedNameplateText = label;

        if (NameplateBuilderCompatibility.isAvailable()) {
            boolean registeredText = NameplateBuilderCompatibility.registerMobText(ref.getStore(), ref, label);
            if (!registeredText && showLevelInNameplate) {
                NameplateBuilderCompatibility.registerMobLevel(ref.getStore(), ref, mobLevel);
            }
        }

        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            nameplate.setText(label);
            return true;
        }

        return NameplateBuilderCompatibility.isAvailable();
    }

    private boolean shouldLogSummonHealthAnomaly(int entityId) {
        long now = System.currentTimeMillis();
        Long last = summonHealthAnomalyLogTimes.get(entityId);
        if (last != null && now - last < SUMMON_HEALTH_ANOMALY_LOG_COOLDOWN_MS) {
            return false;
        }
        summonHealthAnomalyLogTimes.put(entityId, now);
        return true;
    }

    private void clearOrRemoveNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        if (NameplateBuilderCompatibility.isAvailable()) {
            NameplateBuilderCompatibility.removeMobLevel(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeSummonText(ref.getStore(), ref);
            return;
        }

        Nameplate nameplate = commandBuffer.getComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
            if (display != null && display.getDisplayName() != null && !display.getDisplayName().getAnsiMessage().isBlank()) {
                nameplate.setText(display.getDisplayName().getAnsiMessage());
            } else {
                nameplate.setText("");
            }
        }
    }

    private void clearTrackedNameplateIfNeeded(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            EntityRuntimeState state) {
        if (state == null || !state.hasNameplateState()) {
            return;
        }
        clearOrRemoveNameplate(ref, commandBuffer);
        resetNameplateState(state);
    }

    private void resetNameplateState(EntityRuntimeState state) {
        if (state == null) {
            return;
        }
        state.lastAppliedNameplateLevel = Integer.MIN_VALUE;
        state.lastAppliedShowLevelInNameplate = false;
        state.lastAppliedShowNameInNameplate = false;
        state.lastAppliedShowHealthInNameplate = false;
        state.lastAppliedNameplateText = null;
        state.lastNameplateHealthValue = Float.NaN;
        state.lastNameplateMaxHealthValue = Float.NaN;
    }

    private EntityRuntimeState getOrCreateEntityState(long entityKey, int entityId, Store<EntityStore> store) {
        EntityRuntimeState state = entityStates.computeIfAbsent(entityKey, ignored -> new EntityRuntimeState());
        if (entityId >= 0) {
            state.trackedEntityId = entityId;
        }
        if (store != null) {
            state.trackedStore = store;
        }
        return state;
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

    private boolean isDebugSectionEnabled(String sectionKey) {
        if (sectionKey == null || sectionKey.isBlank()) {
            return false;
        }
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) {
            return false;
        }

        Object raw = plugin.getConfigManager().get("logging.debug_sections", List.of(), false);
        if (raw == null) {
            raw = plugin.getConfigManager().get("debug_sections", List.of(), false);
        }

        Collection<?> sections = null;
        if (raw instanceof Collection<?> collection) {
            sections = collection;
        } else if (raw instanceof String str) {
            String trimmed = str.trim();
            if (!trimmed.isEmpty()) {
                sections = List.of(trimmed.split(","));
            }
        }

        if (sections == null || sections.isEmpty()) {
            return false;
        }

        String normalizedKey = sectionKey.trim().toLowerCase(Locale.ROOT);
        String mobKey = "mob." + normalizedKey;
        String fqMobKey = "com.airijko.endlessleveling.mob." + normalizedKey;
        for (Object section : sections) {
            if (section == null) {
                continue;
            }
            String normalizedSection = String.valueOf(section).trim().toLowerCase(Locale.ROOT);
            if (normalizedSection.equals(normalizedKey)
                    || normalizedSection.equals(mobKey)
                    || normalizedSection.equals(fqMobKey)
                    || normalizedSection.equals("mob.moblevelingsystem")
                    || normalizedSection.equals("com.airijko.endlessleveling.mob.moblevelingsystem")) {
                return true;
            }
        }
        return false;
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

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            // Only read components from the store currently ticking on this thread.
            if (playerStore != currentStore) {
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

    private int resolvePlayerViewRadiusChunks(Store<EntityStore> playerStore, Ref<EntityStore> playerRef) {
        if (playerStore == null || playerRef == null) {
            return MIN_PLAYER_VIEW_RADIUS_CHUNKS;
        }

        Player player = playerStore.getComponent(playerRef, Player.getComponentType());
        int configuredRadius = player != null ? player.getViewRadius() : 0;
        return Math.max(MIN_PLAYER_VIEW_RADIUS_CHUNKS, configuredRadius);
    }

    private boolean isWithinActivePlayerChunk(Ref<EntityStore> entityRef,
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
            EntityRuntimeState state) {
        if (ref == null || commandBuffer == null || mobLevelingManager == null || entityId < 0 || state == null) {
            return null;
        }

        int resolveAttempts = state.resolveAttempts + 1;
        state.resolveAttempts = resolveAttempts;

        Integer resolvedLevel = mobLevelingManager.resolveMobLevelForEntity(
                ref,
                store,
                commandBuffer,
                resolveAttempts);
        if (resolvedLevel == null || resolvedLevel <= 0) {
            return null;
        }

        state.appliedLevel = resolvedLevel;
        int resolveAssignments = state.resolveAssignments;
        boolean levelSet = mobLevelingManager.setEntityLevelOverrideIfChanged(ref.getStore(), entityId, resolvedLevel);
        if (levelSet) {
            resolveAssignments = resolveAssignments + 1;
            state.resolveAssignments = resolveAssignments;
        } else if (resolveAssignments <= 0) {
            state.resolveAssignments = 1;
        }

        return resolvedLevel;
    }

    private static final class EntityRuntimeState {
        private int appliedLevel;
        private int settledHealthLevel;
        private long lastSeenTimeMillis;
        private int resolveAttempts;
        private int resolveAssignments;
        private int trackedEntityId = -1;
        private Store<EntityStore> trackedStore;
        private long lastPassiveStatTickMillis;
        private long nextMobAugmentRegistrationCheckMillis;
        private long nextHealthApplyAttemptMillis;
        private long lastHealthFlowLogMillis;
        private float lastNameplateHealthValue = Float.NaN;
        private float lastNameplateMaxHealthValue = Float.NaN;
        private int lastAppliedNameplateLevel = Integer.MIN_VALUE;
        private boolean lastAppliedShowLevelInNameplate;
        private boolean lastAppliedShowNameInNameplate;
        private boolean lastAppliedShowHealthInNameplate;
        private String lastAppliedNameplateText;
        private boolean augmentHealthReconcilePending;
        private boolean mobAugmentsInitialized;
        private boolean flowInitializedLogged;

        private boolean hasNameplateState() {
            return lastAppliedNameplateText != null
                    || lastAppliedNameplateLevel != Integer.MIN_VALUE
                    || lastAppliedShowLevelInNameplate
                    || lastAppliedShowNameInNameplate
                    || lastAppliedShowHealthInNameplate
                    || Float.isFinite(lastNameplateHealthValue)
                    || Float.isFinite(lastNameplateMaxHealthValue);
        }

    }
}

