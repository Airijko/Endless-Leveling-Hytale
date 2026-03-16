package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final float SYSTEM_INTERVAL_SECONDS = 0.15f;
    private static final long STALE_ENTITY_TTL_MILLIS = 100_000L;
    private static final int CHUNK_BIT_SHIFT = 5;
    private static final int MIN_PLAYER_VIEW_RADIUS_CHUNKS = 1;
    private static final int PLAYER_VIEW_RADIUS_BUFFER_CHUNKS = 1;
    private static final long SUMMON_HEALTH_ANOMALY_LOG_COOLDOWN_MS = 3000L;
    private static final long SUMMON_NAMEPLATE_LOG_COOLDOWN_MS = 3000L;

    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
    private final PlayerDataManager playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
    private final Map<Long, EntityRuntimeState> entityStates = new ConcurrentHashMap<>();
    private final Map<Integer, Long> summonHealthAnomalyLogTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> summonNameplateLogTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean fullMobRescaleRequested = new AtomicBoolean(false);
    private long systemTimeMillis = 0L;

    private static final class EntityRuntimeState {
        private int appliedLevel;
        private int settledHealthLevel;
        private long lastSeenTimeMillis;
        private int resolveAttempts;
        private int resolveAssignments;
        private boolean managedNameplate;
        private String previousNameplateText;
        private int trackedEntityId = -1;
        private Store<EntityStore> trackedStore;
        private String lastKnownEntitySignature;
        private boolean deathHandled;

        private boolean hasTrackedState() {
            return appliedLevel > 0
                    || settledHealthLevel > 0
                    || managedNameplate
                    || previousNameplateText != null
                    || resolveAttempts > 0
                    || resolveAssignments > 0
                    || lastKnownEntitySignature != null
                    || deathHandled
                    || trackedStore != null
                    || trackedEntityId >= 0;
        }
    }

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
            entityStates.clear();
        }

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

                        if (!processedEntityKeysThisStep.add(entityKey)) {
                            continue;
                        }

                        EntityRuntimeState existingState = entityStates.get(entityKey);
                        boolean hasLockedLevel = hasAnyLevelLock(ref, entityId, existingState);

                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef != null && playerRef.isValid()) {
                            if (hasLockedLevel || (existingState != null && existingState.hasTrackedState())) {
                                clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey);
                            } else {
                                clearOrRemoveNameplate(ref, commandBuffer, entityKey, existingState);
                                clearMobHealthScaleModifierForPlayer(ref, commandBuffer);
                                entityStates.remove(entityKey);
                            }
                            continue;
                        }

                        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
                        if (npcEntity == null) {
                            if (hasLockedLevel || (existingState != null && existingState.hasTrackedState())) {
                                clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey);
                            } else {
                                clearOrRemoveNameplate(ref, commandBuffer, entityKey, existingState);
                                entityStates.remove(entityKey);
                            }
                            continue;
                        }

                        if (shouldResetAllMobs) {
                            clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey);
                            existingState = null;
                        }

                        boolean hasNearbyPlayerChunk = isWithinNearbyPlayerChunkViewport(
                                ref,
                                commandBuffer,
                                playerChunkViewports);
                        if (!hasNearbyPlayerChunk) {
                            boolean hasTrackedState = existingState != null && existingState.hasTrackedState();
                            if (hasTrackedState || hasLockedLevel) {
                                clearLevelingStateForEntity(
                                        ref,
                                        commandBuffer,
                                        entityId,
                                        entityKey);
                            } else {
                                entityStates.remove(entityKey);
                            }
                            continue;
                        }

                        EntityRuntimeState state = getOrCreateEntityState(entityKey, entityId, ref.getStore());
                        state.trackedEntityId = entityId;
                        state.trackedStore = ref.getStore();
                        state.lastSeenTimeMillis = currentTimeMillis;

                        ensureDeadComponentWhenZeroHp(ref, commandBuffer);

                        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
                            if (!state.deathHandled) {
                                state.deathHandled = true;
                                ensureDeathHealthApplied(ref, commandBuffer, entityKey, state);
                                handleDeadEntity(ref, commandBuffer, state);
                                clearLevelingStateOnDeath(ref, entityId, entityKey);
                            }
                            continue;
                        }

                        if (mobLevelingManager.isEntityBlacklisted(ref, store, commandBuffer)) {
                            if (state.hasTrackedState()) {
                                clearLevelingStateForEntity(ref, commandBuffer, entityId, entityKey);
                            } else {
                                entityStates.remove(entityKey);
                            }
                            continue;
                        }

                        String currentEntitySignature = buildEntitySignature(ref, commandBuffer, npcEntity);
                        String previousEntitySignature = state.lastKnownEntitySignature;
                        if (previousEntitySignature != null
                                && !previousEntitySignature.equals(currentEntitySignature)) {
                            if (state.appliedLevel > 0 && !trackingIdentity.uuidBacked()) {
                                clearLevelingStateForEntity(
                                        ref,
                                        commandBuffer,
                                        entityId,
                                        entityKey);
                                state = getOrCreateEntityState(entityKey, entityId, ref.getStore());
                            }
                        }
                        state.lastKnownEntitySignature = currentEntitySignature;

                        boolean managedSummon = ArmyOfTheDeadPassive.isManagedSummon(ref, store, commandBuffer);

                        Integer appliedLevel = state.appliedLevel > 0 ? state.appliedLevel : null;
                        if (appliedLevel == null && !managedSummon) {
                            appliedLevel = resolveAndAssignLevelOnce(
                                    ref,
                                    store,
                                    commandBuffer,
                                    entityId,
                                    state);
                        }
                        if (!managedSummon && (appliedLevel == null || appliedLevel <= 0)) {
                            if (isAtOrBelowZeroHealth(ref, commandBuffer)) {
                                clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
                                state.appliedLevel = 0;
                                state.settledHealthLevel = 0;
                            }
                            continue;
                        }

                        boolean initializationSettled = false;
                        if (managedSummon) {
                            applyHealthModifier(ref, commandBuffer, 0, entityKey);
                            initializationSettled = true;
                        } else {
                            boolean shouldApplyHealthModifier = state.settledHealthLevel <= 0
                                    || state.settledHealthLevel != appliedLevel;

                            if (shouldApplyHealthModifier) {
                                boolean healthApplied = applyHealthModifier(ref, commandBuffer, appliedLevel,
                                        entityKey);
                                if (healthApplied) {
                                    state.settledHealthLevel = appliedLevel;
                                }
                            }

                            boolean healthSettled = state.settledHealthLevel > 0
                                    && state.settledHealthLevel == appliedLevel;
                            int resolveAssignments = state.resolveAssignments;
                            initializationSettled = healthSettled
                                    && resolveAssignments > 0;
                        }

                        if (managedSummon || (showMobLevelUi && initializationSettled)) {
                            // Re-apply regularly so HP text stays in sync with live combat damage.
                            boolean nameplateApplied = applyNameplate(ref, commandBuffer, includeLevelInName,
                                    entityKey, state);
                            if (nameplateApplied) {
                                state.managedNameplate = true;
                            }
                        } else {
                            clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
                        }
                    }
                });

        pruneStaleEntities(currentTimeMillis);
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
                summonNameplateLogTimes.remove(state.trackedEntityId);
                mobLevelingManager.clearEntityLevelOverride(state.trackedStore, state.trackedEntityId);
                mobLevelingManager.forgetEntity(state.trackedStore, state.trackedEntityId);
            } else {
                mobLevelingManager.forgetEntityByKey(entityKey);
            }
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

    private void handleDeadEntity(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            EntityRuntimeState state) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        long entityKey = resolveTrackingIdentity(ref, commandBuffer).key();
        clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
    }

    private void ensureDeathHealthApplied(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            long entityKey,
            EntityRuntimeState state) {
        Integer appliedLevel = state != null && state.appliedLevel > 0 ? state.appliedLevel : null;
        if (appliedLevel == null || appliedLevel <= 0) {
            return;
        }

        if (state != null && state.settledHealthLevel == appliedLevel) {
            return;
        }

        boolean healthApplied = applyHealthModifier(ref, commandBuffer, appliedLevel, entityKey);
        if (healthApplied && state != null) {
            state.settledHealthLevel = appliedLevel;
        }
    }

    private void clearLevelingStateOnDeath(Ref<EntityStore> ref,
            int entityId,
            long entityKey) {
        if (ref == null || entityId < 0) {
            return;
        }

        entityStates.remove(entityKey);

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
            EntityStatValue summonHp = summonStatMap.get(healthIndex);
            if (summonHp != null && Float.isFinite(summonHp.getMax()) && summonHp.getMax() > 0.0f) {
                summonStatMap.setStatValue(healthIndex, summonHp.getMax());
            }
            summonStatMap.update();

            EntityStatValue updatedSummonHp = summonStatMap.get(healthIndex);
            if (updatedSummonHp != null && Float.isFinite(updatedSummonHp.getMax())
                    && updatedSummonHp.getMax() > 0.0f) {
                mobLevelingManager.recordEntityMaxHealth(entityId, updatedSummonHp.getMax());

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

        statMap.setStatValue(healthIndex, targetValue);
        statMap.update();
        EntityStatValue updatedHealth = statMap.get(healthIndex);
        if (updatedHealth != null && Float.isFinite(updatedHealth.getMax()) && updatedHealth.getMax() > 0.0f) {
            mobLevelingManager.recordEntityMaxHealth(entityId, updatedHealth.getMax());
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

    private void clearLevelingStateForEntity(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int entityId,
            long entityKey) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
            statMap.update();
        }

        EntityRuntimeState state = entityStates.get(entityKey);
        clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
        entityStates.remove(entityKey);
        mobLevelingManager.forgetEntity(ref.getStore(), entityId);
    }

    private boolean applyNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            boolean includeLevelInName,
            long entityKey,
            EntityRuntimeState state) {
        if (ref == null || commandBuffer == null) {
            return false;
        }

        Integer appliedLevel = state != null && state.appliedLevel > 0 ? state.appliedLevel : null;

        NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
            return false;
        }

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            return false;
        }

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            clearOrRemoveNameplate(ref, commandBuffer, entityKey, state);
            return false;
        }

        boolean managedSummon = ArmyOfTheDeadPassive.isManagedSummon(ref, ref.getStore(), commandBuffer);

        Integer nameplateLevel = appliedLevel;
        if (managedSummon) {
            Integer ownerLevel = resolveManagedSummonOwnerLevel(ref, commandBuffer);
            if (ownerLevel != null && ownerLevel > 0) {
                nameplateLevel = ownerLevel;
            }

            // Do not keep mob_level segments for managed summons.
            if (NameplateBuilderCompatibility.isAvailable()) {
                NameplateBuilderCompatibility.removeMobLevel(ref.getStore(), ref);
            }
        }

        if (!managedSummon && (nameplateLevel == null || nameplateLevel <= 0)) {
            return false;
        }

        if (NameplateBuilderCompatibility.isAvailable() && !managedSummon) {
            boolean applied = NameplateBuilderCompatibility.registerMobLevel(ref.getStore(), ref, nameplateLevel);
            if (applied && state != null) {
                state.managedNameplate = true;
            }
            return applied;
        }

        String baseName = "Mob";
        if (managedSummon) {
            String ownerName = resolveManagedSummonOwnerName(ref, commandBuffer);
            if (ownerName != null && !ownerName.isBlank()) {
                baseName = ownerName + "'s Undead Summon";
            } else {
                baseName = "Undead Summon";
            }
        } else {
            DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
            if (display != null && display.getDisplayName() != null
                    && display.getDisplayName().getAnsiMessage() != null
                    && !display.getDisplayName().getAnsiMessage().isBlank()) {
                baseName = display.getDisplayName().getAnsiMessage();
            }
        }

        String label;
        if (managedSummon) {
            label = baseName;
        } else if (includeLevelInName) {
            label = "[Lv." + nameplateLevel + "] " + baseName;
        } else {
            label = baseName;
        }

        if (!managedSummon) {
            EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
            EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
            if (hp != null && Float.isFinite(hp.get()) && Float.isFinite(hp.getMax()) && hp.getMax() > 0.0f) {
                int currentHp = Math.max(0, Math.round(hp.get()));
                int maxHp = Math.max(1, Math.round(hp.getMax()));
                label = label + " [HP " + currentHp + "/" + maxHp + "]";
            }
        }

        boolean builderApplied = false;
        if (managedSummon && NameplateBuilderCompatibility.isAvailable()) {
            builderApplied = NameplateBuilderCompatibility.registerSummonText(ref.getStore(), ref, label);
            if (!builderApplied) {
                // Fallback for installations where summon_label segment is unavailable.
                builderApplied = NameplateBuilderCompatibility.registerMobText(ref.getStore(), ref, label);
            }
            // Keep old mob_level segment cleared so summon text is the only NPC label
            // segment.
            if (builderApplied) {
                NameplateBuilderCompatibility.removeMobLevel(ref.getStore(), ref);
            }
        }

        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            if (state != null && state.previousNameplateText == null) {
                state.previousNameplateText = readNameplateText(nameplate);
            }
            nameplate.setText(label);
        }

        if (state != null) {
            state.managedNameplate = true;
        }

        boolean vanillaApplied = nameplate != null;
        if (managedSummon && shouldLogSummonNameplateDebug(ref.getIndex())) {
            UUID ownerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(ref, ref.getStore(), commandBuffer);
            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-NAMEPLATE] summonRef=%d owner=%s builderApplied=%s vanillaApplied=%s text=%s",
                    ref.getIndex(),
                    ownerUuid,
                    builderApplied,
                    vanillaApplied,
                    label);
        }

        if (managedSummon && !builderApplied && !vanillaApplied) {
            LOGGER.atWarning().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-NAMEPLATE] Failed to apply summon label via NameplateBuilder and vanilla fallback.");
        }
        return builderApplied || vanillaApplied;
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

    private boolean shouldLogSummonNameplateDebug(int entityId) {
        long now = System.currentTimeMillis();
        Long last = summonNameplateLogTimes.get(entityId);
        if (last != null && now - last < SUMMON_NAMEPLATE_LOG_COOLDOWN_MS) {
            return false;
        }
        summonNameplateLogTimes.put(entityId, now);
        return true;
    }

    private Integer resolveManagedSummonOwnerLevel(Ref<EntityStore> summonRef,
            CommandBuffer<EntityStore> commandBuffer) {
        if (summonRef == null || commandBuffer == null || playerDataManager == null) {
            return null;
        }

        UUID ownerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(
                summonRef,
                summonRef.getStore(),
                commandBuffer);
        if (ownerUuid == null) {
            return null;
        }

        var ownerData = playerDataManager.get(ownerUuid);
        if (ownerData == null) {
            return null;
        }

        int ownerLevel = ownerData.getLevel();
        return ownerLevel > 0 ? ownerLevel : null;
    }

    private String resolveManagedSummonOwnerName(Ref<EntityStore> summonRef,
            CommandBuffer<EntityStore> commandBuffer) {
        if (summonRef == null || commandBuffer == null) {
            return null;
        }

        UUID ownerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(
                summonRef,
                summonRef.getStore(),
                commandBuffer);
        if (ownerUuid == null) {
            return null;
        }

        if (playerDataManager != null) {
            var ownerData = playerDataManager.get(ownerUuid);
            if (ownerData != null && ownerData.getPlayerName() != null && !ownerData.getPlayerName().isBlank()) {
                return ownerData.getPlayerName();
            }
        }

        PlayerRef ownerRef = Universe.get().getPlayer(ownerUuid);
        if (ownerRef != null && ownerRef.getUsername() != null && !ownerRef.getUsername().isBlank()) {
            return ownerRef.getUsername();
        }

        return null;
    }

    private void clearOrRemoveNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            long entityKey) {
        clearOrRemoveNameplate(ref, commandBuffer, entityKey, entityStates.get(entityKey));
    }

    private void clearOrRemoveNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            long entityKey,
            EntityRuntimeState state) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        // Only clear/restore nameplates that this system applied.
        if (state == null || (!state.managedNameplate && state.previousNameplateText == null)) {
            return;
        }

        if (NameplateBuilderCompatibility.isAvailable()) {
            NameplateBuilderCompatibility.removeMobLevel(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeSummonText(ref.getStore(), ref);
            state.managedNameplate = false;
            state.previousNameplateText = null;
            return;
        }

        Nameplate nameplate = commandBuffer.getComponent(ref, Nameplate.getComponentType());
        String previousText = state.previousNameplateText;
        if (nameplate != null && previousText != null) {
            nameplate.setText(previousText);
        }
        state.previousNameplateText = null;
        state.managedNameplate = false;
    }

    private String readNameplateText(Nameplate nameplate) {
        if (nameplate == null) {
            return "";
        }
        try {
            Object raw = nameplate.getClass().getMethod("getText").invoke(nameplate);
            return raw == null ? "" : raw.toString();
        } catch (Throwable ignored) {
            return "";
        }
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

    private boolean hasAnyLevelLock(Ref<EntityStore> ref, int entityId, EntityRuntimeState state) {
        if (state != null && state.appliedLevel > 0) {
            return true;
        }

        if (mobLevelingManager == null || entityId < 0) {
            return false;
        }

        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        Integer overrideLevel = mobLevelingManager.getEntityLevelOverride(store, entityId);
        return overrideLevel != null && overrideLevel > 0;
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
            int fallbackLevel = mobLevelingManager.resolveMobLevel(ref, commandBuffer);
            if (fallbackLevel > 0) {
                resolvedLevel = fallbackLevel;
            }
        }

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
        if (mobLevelingManager != null) {
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
