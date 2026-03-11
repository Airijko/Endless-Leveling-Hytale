package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates mob leveling logic previously in `MobLevelingSystem`.
 */
public class MobLevelingManager {

    private static final double PLAYER_LEVEL_LOCK_RADIUS_BLOCKS = 40.0D;
    private static final Query<EntityStore> ENTITY_QUERY = Query.any();

    private final Map<Integer, Integer> cachedPlayerDiffs = new ConcurrentHashMap<>();
    private final Map<Long, Integer> cachedPosDiffs = new ConcurrentHashMap<>();
    private final Map<String, AreaOverride> areaOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> entityLevelOverrides = new ConcurrentHashMap<>();
    private final Map<Long, Integer> entityLevelOverridesScoped = new ConcurrentHashMap<>();
    private final Map<Long, MixedSourceChoice> mixedSourceChoiceByEntityKey = new ConcurrentHashMap<>();
    private final Map<String, String> loggedDistanceCentersByWorldKey = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> entityPartyOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, Float> entityMaxHealthSnapshots = new ConcurrentHashMap<>();
    private final Map<Long, Integer> tierLockByStoreKey = new ConcurrentHashMap<>();
    private final Map<Long, UUID> tierLockSourcePlayerByStoreKey = new ConcurrentHashMap<>();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final ConfigManager configManager;
    private final ConfigManager worldsConfigManager;
    private final PlayerDataManager playerDataManager;

    private enum LevelSourceMode {
        PLAYER,
        MIXED,
        DISTANCE,
        FIXED,
        TIERS
    }

    private enum MixedSourceChoice {
        PLAYER,
        DISTANCE
    }

    public MobLevelingManager(PluginFilesManager filesManager, PlayerDataManager playerDataManager) {
        this.configManager = new ConfigManager(filesManager, filesManager.getLevelingFile());
        this.worldsConfigManager = new ConfigManager(filesManager, filesManager.getWorldsFile());
        this.playerDataManager = playerDataManager;
    }

    /** Reload mob leveling config and clear cached diffs. */
    public void reloadConfig() {
        configManager.load();
        worldsConfigManager.load();
        cachedPlayerDiffs.clear();
        cachedPosDiffs.clear();
        mixedSourceChoiceByEntityKey.clear();
        loggedDistanceCentersByWorldKey.clear();
        tierLockByStoreKey.clear();
        tierLockSourcePlayerByStoreKey.clear();
    }

    /**
     * For TIERS mode dungeons/instances: lock the tier from the first joining
     * player
     * context and immediately push matching level overrides to all eligible mobs in
     * that world store.
     */
    public void syncTierLevelOverridesForDungeon(Store<EntityStore> store, UUID sourcePlayerUuid) {
        if (store == null || store.isShutdown() || sourcePlayerUuid == null) {
            return;
        }

        if (getLevelSourceMode(store) != LevelSourceMode.TIERS) {
            return;
        }

        long storeKey = toStoreKey(store);
        Integer existingTier = tierLockByStoreKey.get(storeKey);
        if (existingTier != null && existingTier > 0) {
            return;
        }

        int lockedTier = resolveTierLockForPlayerContext(store, sourcePlayerUuid);
        if (lockedTier <= 0) {
            return;
        }

        tierLockByStoreKey.put(storeKey, lockedTier);
        tierLockSourcePlayerByStoreKey.put(storeKey, sourcePlayerUuid);

        int changed = applyTierLockOverridesToStore(store, lockedTier);
        LOGGER.atInfo().log("Tier lock established for world %s (tier=%d, source=%s, changed=%d)",
                resolveWorldIdentifier(store),
                lockedTier,
                sourcePlayerUuid,
                changed);
    }

    private int applyTierLockOverridesToStore(Store<EntityStore> store, int lockedTier) {
        if (store == null || store.isShutdown() || lockedTier <= 0) {
            return 0;
        }

        int[] changed = { 0 };
        store.forEachChunk(ENTITY_QUERY, (chunk, commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                int entityId = ref.getIndex();
                if (entityId < 0) {
                    continue;
                }

                NPCEntity npcEntity = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
                if (npcEntity == null) {
                    continue;
                }

                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null && playerRef.isValid()) {
                    continue;
                }

                if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
                    continue;
                }

                if (isEntityBlacklisted(ref, store, commandBuffer)) {
                    continue;
                }

                Vector3d mobPosition = getWorldPosition(ref, commandBuffer);
                int resolvedLevel = resolveTierLevelForLockedTier(store, entityId, mobPosition, lockedTier);
                if (resolvedLevel <= 0) {
                    continue;
                }

                if (setEntityLevelOverrideIfChanged(store, entityId, resolvedLevel)) {
                    changed[0]++;
                }
            }
        });

        return changed[0];
    }

    private int resolveTierLockForPlayerContext(Store<EntityStore> store, UUID sourcePlayerUuid) {
        TierCount tierCount = resolveTierCount(store);
        int totalTiers = tierCount.totalTiers();
        boolean endlessTiers = tierCount.endless();
        int levelsPerTier = Math.max(0, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 25, store));
        if ((!endlessTiers && totalTiers <= 1) || levelsPerTier <= 0) {
            return 1;
        }

        int referencePlayerLevel = resolveTierReferenceLevelForPlayerContext(store, sourcePlayerUuid);
        LevelRange baseRange = getTierBaseLevelRange(store);
        if (referencePlayerLevel <= 0 || referencePlayerLevel < baseRange.min()) {
            return 1;
        }

        int tierPromotionAllowance = getTierPromotionAllowance(store);
        TierPromotionAllowanceMode allowanceMode = getTierPromotionAllowanceMode(store);
        return applyTierPromotionAllowance(referencePlayerLevel, baseRange, totalTiers, levelsPerTier, 1,
                tierPromotionAllowance, allowanceMode, endlessTiers);
    }

    private int resolveTierReferenceLevelForPlayerContext(Store<EntityStore> store, UUID sourcePlayerUuid) {
        int fallbackLevel = getPlayerLevelIncludingOnline(sourcePlayerUuid);
        if (!isPartyPlayerSourceEnabled(store) || sourcePlayerUuid == null) {
            return fallbackLevel;
        }

        PartyManager partyManager = resolvePartyManager();
        if (partyManager == null || !partyManager.isAvailable() || !partyManager.isInParty(sourcePlayerUuid)) {
            return fallbackLevel;
        }

        Set<UUID> partyMembers = partyManager.getOnlinePartyMembers(sourcePlayerUuid);
        if (partyMembers.isEmpty()) {
            partyMembers = partyManager.getPartyMembers(sourcePlayerUuid);
        }
        if (partyMembers.isEmpty()) {
            return fallbackLevel;
        }

        List<Integer> partyLevels = new ArrayList<>();
        for (UUID memberUuid : partyMembers) {
            if (memberUuid == null) {
                continue;
            }
            int level = getPlayerLevelIncludingOnline(memberUuid);
            if (level > 0) {
                partyLevels.add(level);
            }
        }

        if (partyLevels.isEmpty()) {
            return fallbackLevel;
        }

        return computePartyReferenceLevel(partyLevels, getPartyLevelCalculationMode(store));
    }

    private int computePartyReferenceLevel(List<Integer> levels, PartyLevelCalculation calculationMode) {
        if (levels == null || levels.isEmpty()) {
            return 1;
        }

        List<Integer> normalized = new ArrayList<>(levels.size());
        for (Integer level : levels) {
            if (level == null) {
                continue;
            }
            normalized.add(Math.max(1, level));
        }
        if (normalized.isEmpty()) {
            return 1;
        }

        if (calculationMode == PartyLevelCalculation.MEDIAN) {
            Collections.sort(normalized);
            int size = normalized.size();
            int mid = size / 2;
            if ((size % 2) == 1) {
                return normalized.get(mid);
            }
            return (int) Math.round((normalized.get(mid - 1) + normalized.get(mid)) / 2.0D);
        }

        double sum = 0.0D;
        for (int level : normalized) {
            sum += level;
        }
        return (int) Math.round(sum / normalized.size());
    }

    private int getPlayerLevelIncludingOnline(UUID uuid) {
        if (uuid == null) {
            return 1;
        }

        PlayerData data = playerDataManager != null ? playerDataManager.get(uuid) : null;
        if (data == null && playerDataManager != null) {
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null && playerRef.isValid()) {
                data = playerDataManager.loadOrCreate(uuid, playerRef.getUsername());
            }
        }

        if (data == null) {
            return 1;
        }
        return Math.max(1, data.getLevel());
    }

    private int resolveTierLevelForLockedTier(Store<EntityStore> store,
            Integer entityId,
            Vector3d mobPosition,
            int lockedTier) {
        LevelRange baseRange = getTierBaseLevelRange(store);
        int levelsPerTier = Math.max(0, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 25, store));
        if (levelsPerTier <= 0) {
            return sampleLevel(baseRange.min(), baseRange.max(), entityId, mobPosition);
        }

        LevelRange tierRange = getTierRange(baseRange, lockedTier, levelsPerTier);
        return sampleLevel(tierRange.min(), tierRange.max(), entityId, mobPosition);
    }

    /**
     * Resolves a mob level for an entity when it is eligible for mob leveling.
     * Returns null when the entity should not be leveled.
     */
    public Integer resolveMobLevelForEntity(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        return resolveMobLevelForEntity(ref, store, commandBuffer, Integer.MAX_VALUE);
    }

    public Integer resolveMobLevelForEntity(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, int resolveAttempts) {
        if (ref == null)
            return null;
        if (!isMobLevelingEnabled())
            return null;
        if (store == null && commandBuffer == null)
            return null;

        if (!isEligibleForMobLeveling(ref, store, commandBuffer)) {
            return null;
        }

        int mobLevel = resolveMobLevelForEntityWithDeferredFallback(ref, commandBuffer, resolveAttempts);
        if (mobLevel <= 0) {
            return null;
        }

        EntityStatMap statMap = resolveComponent(ref, store, commandBuffer, EntityStatMap.getComponentType());
        if (statMap == null)
            return null;

        var health = statMap.get(DefaultEntityStatTypes.getHealth());
        if (health == null)
            return null;

        if (!Float.isFinite(health.get()) || health.get() <= 0.0f)
            return null;

        return mobLevel;
    }

    public String describePlayerContextForEntity(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        try {
            Ref<EntityStore> safeRef = ref;
            Store<EntityStore> safeStore = store;
            if (safeRef != null && safeStore == null) {
                safeStore = safeRef.getStore();
            }

            Vector3d mobPos = getWorldPosition(safeRef, commandBuffer);
            Universe universe = Universe.get();
            if (mobPos == null) {
                return "mode=" + getLevelSourceMode() + "|mobPos=missing";
            }
            if (universe == null) {
                return "mode=" + getLevelSourceMode() + "|universe=missing";
            }

            int sameWorldPlayers = 0;
            int inRadiusPlayers = 0;
            double nearestDistSq = Double.MAX_VALUE;
            int nearestLevel = -1;
            double radiusSq = PLAYER_LEVEL_LOCK_RADIUS_BLOCKS * PLAYER_LEVEL_LOCK_RADIUS_BLOCKS;

            for (PlayerRef playerRef : universe.getPlayers()) {
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                Ref<EntityStore> playerEntityRef = playerRef.getReference();
                if (playerEntityRef == null) {
                    continue;
                }

                if (!isSameWorld(safeStore, playerEntityRef.getStore())) {
                    continue;
                }
                sameWorldPlayers++;

                Vector3d playerPos = getWorldPosition(playerEntityRef, null);
                if (playerPos == null) {
                    continue;
                }

                double distSq = horizontalDistanceSquared(mobPos, playerPos);
                if (distSq <= radiusSq) {
                    inRadiusPlayers++;
                }
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestLevel = getPlayerLevel(playerRef.getUuid());
                }
            }

            String nearestDistance = nearestDistSq == Double.MAX_VALUE
                    ? "none"
                    : String.format(Locale.ROOT, "%.2f", Math.sqrt(Math.max(0.0D, nearestDistSq)));
            String nearestLevelText = nearestLevel > 0 ? Integer.toString(nearestLevel) : "none";

            return "mode=" + getLevelSourceMode()
                    + "|sameWorldPlayers=" + sameWorldPlayers
                    + "|inRadiusPlayers=" + inRadiusPlayers
                    + "|radius=" + String.format(Locale.ROOT, "%.1f", PLAYER_LEVEL_LOCK_RADIUS_BLOCKS)
                    + "|nearestDist=" + nearestDistance
                    + "|nearestLevel=" + nearestLevelText;
        } catch (Throwable t) {
            return "playerContext-error=" + t.getClass().getSimpleName();
        }
    }

    private int resolveMobLevelForEntityWithDeferredFallback(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int resolveAttempts) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        Vector3d position = getWorldPosition(ref, commandBuffer);
        Integer entityId = ref != null ? ref.getIndex() : null;

        Integer override = resolveExternalOverride(ref, store, commandBuffer, position, entityId);
        if (override != null && override > 0) {
            return clampToConfiguredRange(override);
        }

        LevelSourceMode mode = getLevelSourceMode(store);
        int level;
        try {
            level = switch (mode) {
                case PLAYER -> resolvePlayerBasedLevelWithDeferredFallback(store, position, entityId, resolveAttempts);
                case MIXED -> resolveMixedLevel(store, position, entityId);
                case DISTANCE -> resolveDistanceLevel(store, position);
                case FIXED -> getFixedLevel(store, entityId, position);
                case TIERS -> getTieredLevel(store, entityId, position);
            };
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobLeveling: failed to resolve level via mode %s: %s", mode, t.toString());
            level = getFixedLevel(store, entityId, position);
        }

        if (level <= 0) {
            return -1;
        }

        return clampToConfiguredRange(level, store);
    }

    private boolean isEligibleForMobLeveling(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        Object npcComp = resolveComponent(ref, store, commandBuffer, NPCEntity.getComponentType());
        if (npcComp == null) {
            return false;
        }

        PlayerRef playerRef = resolveComponent(ref, store, commandBuffer, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            return false;
        }

        if (isEntityBlacklisted(ref, store, commandBuffer)) {
            return false;
        }

        DeathComponent deathComponent = resolveComponent(ref, store, commandBuffer, DeathComponent.getComponentType());
        if (deathComponent != null) {
            return false;
        }

        return true;
    }

    public void forgetEntity(int entityIndex) {
        entityLevelOverrides.remove(entityIndex);
        entityPartyOverrides.remove(entityIndex);
        cachedPlayerDiffs.remove(entityIndex);
        entityMaxHealthSnapshots.remove(entityIndex);
        clearMixedChoicesByEntityIndex(entityIndex);
    }

    public void forgetEntity(Store<EntityStore> store, int entityIndex) {
        if (entityIndex < 0) {
            return;
        }
        long entityKey = toEntityKey(store, entityIndex);
        entityLevelOverridesScoped.remove(entityKey);
        mixedSourceChoiceByEntityKey.remove(entityKey);
        forgetEntity(entityIndex);
    }

    public void forgetEntityByKey(long entityKey) {
        entityLevelOverridesScoped.remove(entityKey);
        mixedSourceChoiceByEntityKey.remove(entityKey);
    }

    public void recordEntityMaxHealth(int entityIndex, float maxHealth) {
        if (entityIndex < 0 || !Float.isFinite(maxHealth) || maxHealth <= 0.0f) {
            return;
        }
        entityMaxHealthSnapshots.put(entityIndex, maxHealth);
    }

    public float getEntityMaxHealthSnapshot(int entityIndex) {
        Float cached = entityMaxHealthSnapshots.get(entityIndex);
        if (cached == null || !Float.isFinite(cached) || cached <= 0.0f) {
            return -1.0f;
        }
        return cached;
    }

    private <T extends Component<EntityStore>> T resolveComponent(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer, ComponentType<EntityStore, T> type) {
        if (commandBuffer != null) {
            T component = commandBuffer.getComponent(ref, type);
            if (component != null)
                return component;
        }
        if (store != null)
            return store.getComponent(ref, type);
        return null;
    }

    /**
     * Resolve the configured mob level for the given entity.
     */
    public int resolveMobLevel(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        Vector3d position = getWorldPosition(ref, commandBuffer);
        Integer entityId = ref != null ? ref.getIndex() : null;
        return resolveMobLevel(store, position, entityId, ref, commandBuffer);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition) {
        return resolveMobLevel(store, mobPosition, null);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition, Integer entityId) {
        return resolveMobLevel(store, mobPosition, entityId, null, null);
    }

    private int resolveMobLevel(Store<EntityStore> store,
            Vector3d mobPosition,
            Integer entityId,
            Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        Integer override = resolveExternalOverride(ref, store, commandBuffer, mobPosition, entityId);
        if (override != null && override > 0) {
            return override;
        }

        LevelSourceMode mode = getLevelSourceMode(store);
        int level;
        try {
            level = switch (mode) {
                case PLAYER -> resolvePlayerBasedLevel(store, mobPosition, entityId);
                case MIXED -> resolveMixedLevel(store, mobPosition, entityId);
                case DISTANCE -> resolveDistanceLevel(store, mobPosition);
                case FIXED -> getFixedLevel(store, entityId, mobPosition);
                case TIERS -> getTieredLevel(store, entityId, mobPosition);
            };
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobLeveling: failed to resolve level via mode %s: %s", mode, t.toString());
            level = getFixedLevel(store, entityId, mobPosition);
        }
        return clampToConfiguredRange(level, store);
    }

    /** True when mob levels are derived from nearby player levels. */
    public boolean isPlayerBasedMode() {
        LevelSourceMode mode = getLevelSourceMode();
        return mode == LevelSourceMode.PLAYER || mode == LevelSourceMode.MIXED;
    }

    public boolean isLevelSourcePlayerMode() {
        return isPurePlayerSourceMode();
    }

    public boolean isLevelSourceMixedMode() {
        return getLevelSourceMode() == LevelSourceMode.MIXED;
    }

    public String describeMixedPromotionTrigger(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return "ctx=missing-ref";
        }

        Store<EntityStore> safeStore = store != null ? store : ref.getStore();
        Vector3d mobPos = getWorldPosition(ref, commandBuffer);
        if (mobPos == null) {
            return "ctx=missing-pos";
        }

        int entityId = ref.getIndex();
        int distanceLevel = resolveDistanceLevel(safeStore, mobPos);
        int playerLevel = resolvePlayerBasedLevelWithoutFallback(safeStore, mobPos, entityId);
        int playerLowerBound = resolvePlayerLowerBoundForMixed(safeStore, mobPos);
        int xpMaxDifference = getExperienceXpMaxDifference(safeStore);
        return String.format(
                Locale.ROOT,
                "distance=%d player=%d floor=%d xpMaxDiff=%d",
                distanceLevel,
                playerLevel,
                playerLowerBound,
                xpMaxDifference);
    }

    private boolean isPurePlayerSourceMode() {
        return getLevelSourceMode() == LevelSourceMode.PLAYER;
    }

    /**
     * Expected mob level range for the provided player level in player-based mode.
     */
    public LevelRange getPlayerBasedLevelRange(int playerLevel) {
        int level = Math.max(1, playerLevel);
        int offset = getPlayerBasedOffset(null);
        int minDiff = getPlayerBasedMinDifference(null);
        int maxDiff = getPlayerBasedMaxDifference(null);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int min = clampToConfiguredRange(level + minDiff + offset, null);
        int max = clampToConfiguredRange(level + maxDiff + offset, null);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return new LevelRange(min, max);
    }

    private Integer resolveExternalOverride(Store<EntityStore> store, Vector3d mobPosition, Integer entityId) {
        return resolveExternalOverride(null, store, null, mobPosition, entityId);
    }

    private Integer resolveExternalOverride(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            Vector3d mobPosition,
            Integer entityId) {
        if (entityId != null) {
            if (store != null) {
                Integer scoped = entityLevelOverridesScoped.get(toEntityKey(store, entityId));
                if (scoped != null) {
                    return Math.max(1, scoped);
                }
            }
            Integer direct = entityLevelOverrides.get(entityId);
            if (direct != null) {
                return Math.max(1, direct);
            }
        }

        Integer worldMobOverrideLevel = resolveWorldMobOverrideLevel(ref, store, commandBuffer);
        if (worldMobOverrideLevel != null && worldMobOverrideLevel > 0) {
            return worldMobOverrideLevel;
        }

        if (areaOverrides.isEmpty()) {
            return null;
        }

        String worldId = resolveWorldId(store);
        for (AreaOverride override : areaOverrides.values()) {
            if (override == null) {
                continue;
            }
            if (override.worldId != null && worldId != null
                    && !override.worldId.equalsIgnoreCase(worldId)) {
                continue;
            }
            if (mobPosition == null) {
                continue;
            }
            double dx = mobPosition.getX() - override.centerX;
            double dz = mobPosition.getZ() - override.centerZ;
            double distSq = (dx * dx) + (dz * dz);
            if (distSq > override.radiusSq) {
                continue;
            }
            int sampled = sampleLevel(override.minLevel, override.maxLevel, entityId, mobPosition);
            return Math.max(1, sampled);
        }
        return null;
    }

    private Integer resolveWorldMobOverrideLevel(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        WorldMobOverride override = resolveWorldMobOverride(ref, store, commandBuffer);
        if (override == null) {
            return null;
        }

        Integer level = override.level();
        if (level == null || level <= 0) {
            return level;
        }

        Store<EntityStore> effectiveStore = store != null ? store : (ref != null ? ref.getStore() : null);
        if (getLevelSourceMode(effectiveStore) != LevelSourceMode.TIERS) {
            return level;
        }

        Integer entityId = ref != null ? ref.getIndex() : null;
        Vector3d mobPosition = ref != null ? getWorldPosition(ref, commandBuffer) : null;
        return applyTierOffsetToConfiguredLevel(effectiveStore, entityId, mobPosition, level);
    }

    private WorldMobOverride resolveWorldMobOverride(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return null;
        }

        Store<EntityStore> effectiveStore = store != null ? store : ref.getStore();
        String mobType = resolveMobType(ref, effectiveStore, commandBuffer);
        String normalizedMobType = normalizeMobIdentifier(mobType);
        if (normalizedMobType == null) {
            return null;
        }

        String worldId = resolveWorldId(effectiveStore);
        Object rootRaw = worldsConfigManager.get("World_Overrides", null, false);
        if (!(rootRaw instanceof Map<?, ?> rootMap) || rootMap.isEmpty()) {
            return null;
        }

        if (worldId != null && !worldId.isBlank()) {
            Object exactRaw = resolveCaseInsensitive(rootMap, worldId);
            WorldMobOverride exactOverride = resolveWorldMobOverrideFromWorldNode(exactRaw, normalizedMobType);
            if (exactOverride != null) {
                return exactOverride;
            }

            Map<?, ?> bestWildcardMap = resolveBestWildcardWorldOverrideMap(rootMap, worldId);
            WorldMobOverride wildcardOverride = resolveWorldMobOverrideFromWorldNode(bestWildcardMap,
                    normalizedMobType);
            if (wildcardOverride != null) {
                return wildcardOverride;
            }
        }

        Object defaultRaw = resolveCaseInsensitive(rootMap, "default");
        return resolveWorldMobOverrideFromWorldNode(defaultRaw, normalizedMobType);
    }

    public List<String> getMobOverrideAugmentIds(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        return getMobOverrideAugmentIds(ref, store, commandBuffer);
    }

    public List<String> getMobOverrideAugmentIds(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        WorldMobOverride override = resolveWorldMobOverride(ref, store, commandBuffer);
        if (override == null || override.augmentIds() == null || override.augmentIds().isEmpty()) {
            return List.of();
        }
        return override.augmentIds();
    }

    private WorldMobOverride resolveWorldMobOverrideFromWorldNode(Object worldNode, String normalizedMobType) {
        if (!(worldNode instanceof Map<?, ?> worldMap) || normalizedMobType == null || normalizedMobType.isBlank()) {
            return null;
        }

        Object mobOverridesRaw = resolveCaseInsensitive(worldMap, "Mob_Overrides");
        if (!(mobOverridesRaw instanceof Map<?, ?> mobOverridesMap) || mobOverridesMap.isEmpty()) {
            return null;
        }

        WorldMobOverride bestWildcardOverride = null;
        int bestWildcardSpecificity = -1;
        int bestWildcardLength = -1;

        for (Map.Entry<?, ?> entry : mobOverridesMap.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String rule)) {
                continue;
            }

            Object rawValue = entry.getValue();
            WorldMobOverride override = parseMobOverride(rawValue);
            if (override == null) {
                continue;
            }

            List<String> candidateRules = resolveMobOverrideCandidateRules(rule, rawValue);
            if (candidateRules.isEmpty()) {
                continue;
            }

            for (String candidateRule : candidateRules) {
                String normalizedRule = normalizeMobIdentifier(candidateRule);
                if (normalizedRule == null || normalizedRule.isBlank()) {
                    continue;
                }

                if (normalizedRule.indexOf('*') < 0) {
                    if (normalizedMobType.equals(normalizedRule)) {
                        return override;
                    }
                    continue;
                }

                if (!matchesWildcard(normalizedMobType, normalizedRule)) {
                    continue;
                }

                int specificity = wildcardSpecificity(normalizedRule);
                int length = normalizedRule.length();
                if (specificity > bestWildcardSpecificity
                        || (specificity == bestWildcardSpecificity && length > bestWildcardLength)) {
                    bestWildcardOverride = override;
                    bestWildcardSpecificity = specificity;
                    bestWildcardLength = length;
                }
            }
        }

        return bestWildcardOverride;
    }

    private List<String> resolveMobOverrideCandidateRules(String fallbackRule, Object rawValue) {
        Set<String> rules = new LinkedHashSet<>();

        if (rawValue instanceof Map<?, ?> mapValue) {
            appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Names"), rules);
            appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Mob_Names"), rules);
            appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Ids"), rules);
            appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Mob_Types"), rules);
            appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Rules"), rules);
        }

        if (rules.isEmpty()) {
            appendMobOverrideRulesFromRaw(fallbackRule, rules);
        }

        return new ArrayList<>(rules);
    }

    private void appendMobOverrideRulesFromRaw(Object rawRules, Set<String> target) {
        if (rawRules == null || target == null) {
            return;
        }

        if (rawRules instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                appendMobOverrideRulesFromRaw(element, target);
            }
            return;
        }

        if (!(rawRules instanceof String text)) {
            target.add(rawRules.toString());
            return;
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        String[] parts = trimmed.split(",");
        for (String part : parts) {
            String token = part == null ? "" : part.trim();
            if (!token.isEmpty()) {
                target.add(token);
            }
        }
    }

    private WorldMobOverride parseMobOverride(Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        Integer level = parseMobOverrideLevel(rawValue);

        Double healthMultiplier = null;
        Double damageMultiplier = null;
        Double defenseReduction = null;
        MobOverrideLinearScaling healthScaling = null;
        MobOverrideLinearScaling damageScaling = null;
        MobOverrideDefenseScaling defenseScaling = null;
        List<String> augmentIds = List.of();
        MobOverrideAugmentModifiers augmentModifiers = null;

        if (rawValue instanceof Map<?, ?> mapValue) {
            healthMultiplier = parseMobOverrideMultiplier(
                    mapValue,
                    "Health_Multiplier",
                    "Health_Modifier",
                    "Health");
            damageMultiplier = parseMobOverrideMultiplier(
                    mapValue,
                    "Damage_Multiplier",
                    "Damage_Modifier",
                    "Damage");
            defenseReduction = parseMobOverrideReduction(
                    mapValue,
                    "Defense_Multiplier",
                    "Defense_Modifier",
                    "Defense");

            Object scalingRaw = resolveCaseInsensitive(mapValue, "Scaling");
            if (scalingRaw instanceof Map<?, ?> scalingMap) {
                healthScaling = parseMobOverrideLinearScaling(resolveCaseInsensitive(scalingMap, "Health"));
                Object damageScalingRaw = resolveCaseInsensitive(scalingMap, "Damage");
                damageScaling = parseMobOverrideLinearScaling(damageScalingRaw);
                defenseScaling = parseMobOverrideDefenseScaling(resolveCaseInsensitive(scalingMap, "Defense"));
            }

            augmentIds = parseMobOverrideAugmentIds(mapValue);
            augmentModifiers = parseMobOverrideAugmentModifiers(augmentIds);
        }

        boolean hasStatModifier = healthMultiplier != null
                || damageMultiplier != null
                || defenseReduction != null
                || healthScaling != null
                || damageScaling != null
                || defenseScaling != null
                || !augmentIds.isEmpty()
                || augmentModifiers != null;
        if (level == null && !hasStatModifier) {
            return null;
        }

        return new WorldMobOverride(
                level,
                healthMultiplier,
                damageMultiplier,
                defenseReduction,
                healthScaling,
                damageScaling,
                defenseScaling,
                augmentIds,
                augmentModifiers);
    }

    private List<String> parseMobOverrideAugmentIds(Map<?, ?> mapValue) {
        Set<String> augmentIds = new LinkedHashSet<>();
        appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Augments"), augmentIds);
        appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Augment_Ids"), augmentIds);
        appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "AugmentIds"), augmentIds);
        appendMobOverrideRulesFromRaw(resolveCaseInsensitive(mapValue, "Augment_IDs"), augmentIds);
        if (augmentIds.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>(augmentIds.size());
        for (String augmentId : augmentIds) {
            if (augmentId == null) {
                continue;
            }
            String cleaned = augmentId.trim();
            if (!cleaned.isEmpty()) {
                normalized.add(cleaned);
            }
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private MobOverrideAugmentModifiers parseMobOverrideAugmentModifiers(List<String> augmentIds) {
        if (augmentIds == null || augmentIds.isEmpty()) {
            return null;
        }

        AugmentManager augmentManager = resolveAugmentManager();
        if (augmentManager == null) {
            return null;
        }

        int appliedAugments = 0;
        double healthPercentBonus = 0.0D;
        double damagePercentBonus = 0.0D;

        for (String rawId : augmentIds) {
            if (rawId == null || rawId.isBlank()) {
                continue;
            }

            AugmentDefinition definition = augmentManager.getAugment(rawId.trim());
            if (definition == null) {
                continue;
            }

            Map<String, Object> passives = definition.getPassives();
            if (passives.isEmpty()) {
                continue;
            }

            double healthBonus = AugmentValueReader.getNestedDouble(passives, 0.0D, "buffs", "max_health_percent",
                    "value");

            double damageBonus = 0.0D;
            damageBonus += AugmentValueReader.getNestedDouble(passives, 0.0D, "buffs", "bonus_damage", "value");
            damageBonus += AugmentValueReader.getNestedDouble(passives, 0.0D, "bonus_damage_on_hit", "value");
            damageBonus += AugmentValueReader.getNestedDouble(passives, 0.0D, "healthy_state", "bonus_damage",
                    "value");
            damageBonus += AugmentValueReader.getNestedDouble(passives, 0.0D, "max_stack_bonus", "bonus_true_damage",
                    "true_damage_percent");

            double strengthMultiplier = AugmentValueReader.getNestedDouble(passives, 1.0D, "brute_force",
                    "strength_multiplier");
            double sorceryMultiplier = AugmentValueReader.getNestedDouble(passives, 1.0D, "brute_force",
                    "sorcery_multiplier");
            if (strengthMultiplier > 1.0D || sorceryMultiplier > 1.0D) {
                double blended = ((Math.max(1.0D, strengthMultiplier) - 1.0D)
                        + (Math.max(1.0D, sorceryMultiplier) - 1.0D)) / 2.0D;
                damageBonus += blended;
            }

            healthPercentBonus += healthBonus;
            damagePercentBonus += damageBonus;
            appliedAugments++;
        }

        if (appliedAugments <= 0) {
            return null;
        }

        double healthMultiplier = Math.max(0.0001D, 1.0D + healthPercentBonus);
        double damageMultiplier = Math.max(0.0001D, 1.0D + damagePercentBonus);
        if (Math.abs(healthMultiplier - 1.0D) < 1e-9 && Math.abs(damageMultiplier - 1.0D) < 1e-9) {
            return null;
        }

        return new MobOverrideAugmentModifiers(healthMultiplier, damageMultiplier);
    }

    private AugmentManager resolveAugmentManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return null;
        }
        return plugin.getAugmentManager();
    }

    private MobOverrideLinearScaling parseMobOverrideLinearScaling(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> scalingMap)) {
            return null;
        }

        Object enabledRaw = resolveCaseInsensitive(scalingMap, "Enabled");
        boolean enabled = parseBooleanOrDefault(enabledRaw, true);

        Double baseMultiplier = parsePositiveDouble(resolveCaseInsensitive(scalingMap, "Base_Multiplier"));
        Double perLevel = parseNonNegativeDouble(resolveCaseInsensitive(scalingMap, "Per_Level"));

        boolean hasAnyValue = enabledRaw != null || baseMultiplier != null || perLevel != null;
        if (!hasAnyValue) {
            return null;
        }

        return new MobOverrideLinearScaling(enabled, baseMultiplier, perLevel);
    }

    private MobOverrideDefenseScaling parseMobOverrideDefenseScaling(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> scalingMap)) {
            return null;
        }

        Object enabledRaw = resolveCaseInsensitive(scalingMap, "Enabled");
        boolean enabled = parseBooleanOrDefault(enabledRaw, true);

        Double atNegativeMaxDifference = parseNonNegativeDouble(
                resolveCaseInsensitive(scalingMap, "At_Negative_Max_Difference"));
        Double atPositiveMaxDifference = parseNonNegativeDouble(
                resolveCaseInsensitive(scalingMap, "At_Positive_Max_Difference"));
        Double belowNegativeMaxDifference = parseNonNegativeDouble(
                resolveCaseInsensitive(scalingMap, "Below_Negative_Max_Difference"));
        Double abovePositiveMaxDifference = parseNonNegativeDouble(
                resolveCaseInsensitive(scalingMap, "Above_Positive_Max_Difference"));

        boolean hasAnyValue = enabledRaw != null
                || atNegativeMaxDifference != null
                || atPositiveMaxDifference != null
                || belowNegativeMaxDifference != null
                || abovePositiveMaxDifference != null;
        if (!hasAnyValue) {
            return null;
        }

        return new MobOverrideDefenseScaling(
                enabled,
                atNegativeMaxDifference,
                atPositiveMaxDifference,
                belowNegativeMaxDifference,
                abovePositiveMaxDifference);
    }

    private boolean parseBooleanOrDefault(Object rawValue, boolean defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue instanceof Boolean b) {
            return b;
        }
        if (rawValue instanceof Number n) {
            return n.intValue() != 0;
        }
        if (rawValue instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return defaultValue;
    }

    private Double parseMobOverrideMultiplier(Map<?, ?> mapValue,
            String topLevelMultiplierKey,
            String topLevelLegacyModifierKey,
            String statKey) {
        Object raw = resolveMobOverrideStatValue(mapValue, topLevelMultiplierKey, topLevelLegacyModifierKey, statKey);
        return parsePositiveDouble(raw);
    }

    private Double parseMobOverrideReduction(Map<?, ?> mapValue,
            String topLevelMultiplierKey,
            String topLevelLegacyModifierKey,
            String statKey) {
        Object raw = resolveMobOverrideStatValue(mapValue, topLevelMultiplierKey, topLevelLegacyModifierKey, statKey);
        Double parsed = parseNonNegativeDouble(raw);
        if (parsed == null) {
            return null;
        }
        return clampReduction(parsed);
    }

    private Object resolveMobOverrideStatValue(Map<?, ?> mapValue,
            String topLevelMultiplierKey,
            String topLevelLegacyModifierKey,
            String statKey) {
        if (mapValue == null) {
            return null;
        }

        Object raw = resolveCaseInsensitive(mapValue, topLevelMultiplierKey);
        if (raw == null) {
            raw = resolveCaseInsensitive(mapValue, topLevelLegacyModifierKey);
        }
        if (raw == null) {
            raw = resolveCaseInsensitive(mapValue, statKey);
        }

        if (raw == null) {
            Object statModifiersRaw = resolveCaseInsensitive(mapValue, "Stat_Multipliers");
            if (!(statModifiersRaw instanceof Map<?, ?>)) {
                statModifiersRaw = resolveCaseInsensitive(mapValue, "Stat_Modifiers");
            }
            if (!(statModifiersRaw instanceof Map<?, ?>)) {
                statModifiersRaw = resolveCaseInsensitive(mapValue, "Multipliers");
            }
            if (!(statModifiersRaw instanceof Map<?, ?>)) {
                statModifiersRaw = resolveCaseInsensitive(mapValue, "Modifiers");
            }
            if (statModifiersRaw instanceof Map<?, ?> statMap) {
                raw = resolveCaseInsensitive(statMap, statKey);
            }
        }

        return raw;
    }

    private Double parsePositiveDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value <= 0.0D) {
                return null;
            }
            return value;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                double value = Double.parseDouble(trimmed);
                if (!Double.isFinite(value) || value <= 0.0D) {
                    return null;
                }
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double parseNonNegativeDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value < 0.0D) {
                return null;
            }
            return value;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                double value = Double.parseDouble(trimmed);
                if (!Double.isFinite(value) || value < 0.0D) {
                    return null;
                }
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer parseMobOverrideLevel(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof Number number) {
            int level = number.intValue();
            return level > 0 ? level : null;
        }
        if (rawValue instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.matches("-?\\d+")) {
                return null;
            }
            try {
                int level = Integer.parseInt(trimmed);
                return level > 0 ? level : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (rawValue instanceof Map<?, ?> mapValue) {
            Object levelNode = resolveCaseInsensitive(mapValue, "Level");
            return parseMobOverrideLevel(levelNode);
        }
        return null;
    }

    private int sampleLevel(int minLevel, int maxLevel, Integer entityId, Vector3d mobPosition) {
        int min = Math.max(1, Math.min(minLevel, maxLevel));
        int max = Math.max(min, Math.max(minLevel, maxLevel));
        if (min == max) {
            return min;
        }
        long seed = 0L;
        if (entityId != null) {
            seed = entityId.longValue();
        } else if (mobPosition != null) {
            seed = hashPosition(mobPosition);
        }
        long scrambled = seed ^ (seed << 21) ^ (seed >>> 7) ^ (seed << 3);
        long span = (long) (max - min + 1);
        long rolled = Math.floorMod(scrambled, span);
        return (int) (min + rolled);
    }

    private LevelSourceMode getLevelSourceMode() {
        return getLevelSourceMode(null, null);
    }

    private LevelSourceMode getLevelSourceMode(Store<EntityStore> store) {
        return getLevelSourceMode(store, null);
    }

    private LevelSourceMode getLevelSourceMode(Store<EntityStore> store, Object worldHint) {
        Object modeObj = getMobLevelingValue("Mob_Leveling.Level_Source.Mode", "FIXED", store, worldHint);
        if (modeObj == null)
            return LevelSourceMode.FIXED;
        String normalized = modeObj.toString().trim().toUpperCase();
        if (normalized.isEmpty())
            return LevelSourceMode.FIXED;
        // "TIERED" is the canonical name; "TIERS" is kept for backward compatibility.
        if ("TIERED".equals(normalized))
            normalized = "TIERS";
        try {
            return LevelSourceMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return LevelSourceMode.FIXED;
        }
    }

    private int resolveDistanceLevel(Store<EntityStore> store, Vector3d position) {
        if (position == null)
            return getFixedLevel(store, null, null);

        double centerX = 0.0;
        double centerZ = 0.0;
        boolean usingSpawnCenter = false;
        boolean spawnResolved = false;

        try {
            Object centerRaw = getMobLevelingValue(
                    "Mob_Leveling.Level_Source.Distance_Level.Center_Coordinates",
                    "SPAWN",
                    store,
                    null);
            String centerStr = centerRaw != null ? centerRaw.toString().trim() : "SPAWN";

            if (centerStr.equalsIgnoreCase("SPAWN")) {
                usingSpawnCenter = true;
                Vector3d spawnCenter = resolveWorldSpawnCenter(store);
                if (spawnCenter != null) {
                    centerX = spawnCenter.getX();
                    centerZ = spawnCenter.getZ();
                    spawnResolved = true;
                }
            } else {
                String[] parts = centerStr.split(",");
                if (parts.length >= 2) {
                    centerX = Double.parseDouble(parts[0].trim());
                    centerZ = Double.parseDouble(parts[1].trim());
                }
            }
        } catch (Exception ignored) {
            // fallback to 0,0
        }

        if (usingSpawnCenter) {
            logDistanceCenterIfChanged(store, centerX, centerZ, spawnResolved);
        }

        double x = position.getX();
        double z = position.getZ();
        double dx = x - centerX;
        double dz = z - centerZ;
        double distance = Math.sqrt((dx * dx) + (dz * dz));

        double blocksPerLevel = Math.max(1.0,
                getConfigDouble("Mob_Leveling.Level_Source.Distance_Level.Blocks_Per_Level", 100.0, store));
        int startLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Start_Level", 1, store);
        int minLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Min_Level", 1, store);
        int maxLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Max_Level", 200, store);

        int computed = startLevel + (int) Math.floor(distance / blocksPerLevel);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }
        return Math.max(minLevel, Math.min(maxLevel, computed));
    }

    public String describeDistanceCenter(Store<EntityStore> store) {
        return describeDistanceCenter(store, null);
    }

    public String describeDistanceCenter(Store<EntityStore> store, Object worldHint) {
        String worldId = resolveWorldId(store, worldHint);
        String world = (worldId == null || worldId.isBlank()) ? "unknown-world" : worldId;

        Object centerRaw = getMobLevelingValue(
                "Mob_Leveling.Level_Source.Distance_Level.Center_Coordinates",
                "SPAWN",
                store,
                worldHint);
        String centerStr = centerRaw != null ? centerRaw.toString().trim() : "SPAWN";
        if (centerStr.isBlank()) {
            centerStr = "SPAWN";
        }

        double centerX = 0.0D;
        double centerZ = 0.0D;
        boolean usingSpawn = centerStr.equalsIgnoreCase("SPAWN");
        boolean resolvedSpawn = false;
        String resolution = "ok";

        try {
            if (usingSpawn) {
                Vector3d spawnCenter = resolveWorldSpawnCenter(store, worldHint);
                if (spawnCenter != null) {
                    centerX = spawnCenter.getX();
                    centerZ = spawnCenter.getZ();
                    resolvedSpawn = true;
                } else {
                    resolution = "spawn-unresolved";
                }
            } else {
                String[] parts = centerStr.split(",");
                if (parts.length >= 2) {
                    centerX = Double.parseDouble(parts[0].trim());
                    centerZ = Double.parseDouble(parts[1].trim());
                } else {
                    resolution = "invalid-manual-center";
                }
            }
        } catch (Exception ex) {
            resolution = "center-parse-error:" + ex.getClass().getSimpleName();
        }

        return String.format(
                Locale.ROOT,
                "mode=%s world=%s source=%s center=(%.2f, %.2f) spawnResolved=%s resolution=%s",
                getLevelSourceMode(store, worldHint),
                world,
                usingSpawn ? "SPAWN" : centerStr,
                centerX,
                centerZ,
                resolvedSpawn ? "true" : "false",
                resolution);
    }

    private void logDistanceCenterIfChanged(Store<EntityStore> store, double centerX, double centerZ,
            boolean spawnResolved) {
        String worldId = resolveWorldId(store, null);
        String worldKey = (worldId == null || worldId.isBlank()) ? "unknown-world" : worldId;
        String state = String.format(Locale.ROOT, "x=%.2f|z=%.2f|resolved=%s", centerX, centerZ,
                spawnResolved ? "true" : "false");

        String previous = loggedDistanceCentersByWorldKey.put(worldKey, state);
        if (state.equals(previous)) {
            return;
        }

        if (spawnResolved) {
            LOGGER.atInfo().log("DistanceCenterResolved world=%s center=(%.2f, %.2f) source=SPAWN", worldKey, centerX,
                    centerZ);
        } else {
            LOGGER.atWarning().log(
                    "DistanceCenterFallback world=%s center=(%.2f, %.2f) source=SPAWN reason=spawn-unresolved",
                    worldKey,
                    centerX,
                    centerZ);
        }
    }

    private Vector3d resolveWorldSpawnCenter(Store<EntityStore> store) {
        return resolveWorldSpawnCenter(store, null);
    }

    private Vector3d resolveWorldSpawnCenter(Store<EntityStore> store, Object worldHint) {
        Object world = worldHint != null ? worldHint : resolveWorldObject(store);
        Vector3d fromWorld = extractSpawnFromWorld(world);
        if (fromWorld != null) {
            return fromWorld;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }

        String worldId = resolveWorldId(store, worldHint);
        if (worldId == null || worldId.isBlank()) {
            return null;
        }

        try {
            Method getWorldByName = universe.getClass().getMethod("getWorld", String.class);
            Object worldByName = getWorldByName.invoke(universe, worldId);
            return extractSpawnFromWorld(worldByName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object resolveWorldObject(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }

        Object directWorld = invokeNoArg(store, "getWorld");
        if (directWorld != null) {
            return directWorld;
        }

        Object externalData = invokeNoArg(store, "getExternalData");
        Object externalWorld = invokeNoArg(externalData, "getWorld");
        if (externalWorld != null) {
            return externalWorld;
        }

        Object nestedStore = invokeNoArg(store, "getStore");
        if (nestedStore instanceof Store<?> nested && nested != store) {
            @SuppressWarnings("unchecked")
            Store<EntityStore> casted = (Store<EntityStore>) nested;
            Object nestedWorld = resolveWorldObject(casted);
            if (nestedWorld != null) {
                return nestedWorld;
            }
        }

        return null;
    }

    private Vector3d extractSpawnFromWorld(Object world) {
        if (world == null) {
            return null;
        }

        Object spawnCandidate = invokeNoArg(world, "getSpawnPoint");
        Vector3d extracted = asVector3d(spawnCandidate);
        if (extracted != null) {
            return extracted;
        }

        Object worldConfig = invokeNoArg(world, "getWorldConfig");
        Object spawnProvider = invokeNoArg(worldConfig, "getSpawnProvider");
        if (spawnProvider != null) {
            Object providerSpawn = invokeSpawnProviderGetSpawnPoint(spawnProvider, world, new UUID(0L, 0L));
            Vector3d providerVector = asVector3d(providerSpawn);
            if (providerVector != null) {
                return providerVector;
            }
        }

        return null;
    }

    private Object invokeSpawnProviderGetSpawnPoint(Object spawnProvider, Object world, UUID sampleUuid) {
        if (spawnProvider == null || world == null || sampleUuid == null) {
            return null;
        }

        Method bestMethod = null;
        for (Method method : spawnProvider.getClass().getMethods()) {
            if (!"getSpawnPoint".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2) {
                continue;
            }
            if (!parameterTypes[0].isAssignableFrom(world.getClass())) {
                continue;
            }
            if (!parameterTypes[1].isAssignableFrom(UUID.class)) {
                continue;
            }
            bestMethod = method;
            break;
        }

        if (bestMethod == null) {
            return null;
        }

        try {
            return bestMethod.invoke(spawnProvider, world, sampleUuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Vector3d asVector3d(Object candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof Vector3d vector3d) {
            return vector3d;
        }

        Object position = invokeNoArg(candidate, "getPosition");
        if (position instanceof Vector3d vector3d) {
            return vector3d;
        }

        return null;
    }

    private int resolvePlayerBasedLevel(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        int computed = computePlayerModeLevelFromNearestPlayer(store, mobPos, entityId);
        if (computed > 0) {
            return clampToConfiguredRange(computed, store);
        }

        return -1;
    }

    private int resolvePlayerBasedLevelWithDeferredFallback(Store<EntityStore> store,
            Vector3d mobPos,
            Integer entityId,
            int resolveAttempts) {
        if (resolveAttempts < 0) {
            return -1;
        }

        int computed = computePlayerModeLevelFromNearestPlayer(store, mobPos, entityId);
        if (computed > 0) {
            return clampToConfiguredRange(computed, store);
        }

        return -1;
    }

    private int computePlayerModeLevelFromNearestPlayer(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        return computePlayerModeLevelFromNearestPlayerWithinRadius(store, mobPos, entityId, -1.0D);
    }

    private int computePlayerModeLevelFromNearestPlayerWithinRadius(Store<EntityStore> store,
            Vector3d mobPos,
            Integer entityId,
            double radiusBlocks) {
        if (mobPos == null) {
            return -1;
        }

        int nearestPlayerLevel = findNearestPlayerLevelWithinRadius(store, mobPos, radiusBlocks);
        if (nearestPlayerLevel <= 0) {
            return -1;
        }

        int offset = getPlayerBasedOffset(store);
        int minDiff = getPlayerBasedMinDifference(store);
        int maxDiff = getPlayerBasedMaxDifference(store);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int minAllowed = nearestPlayerLevel + minDiff;
        int maxAllowed = nearestPlayerLevel + maxDiff;
        int rolledDiff = samplePlayerDiff(entityId, mobPos, minDiff, maxDiff);
        int target = nearestPlayerLevel + offset + rolledDiff;
        int clamped = Math.max(minAllowed, Math.min(maxAllowed, target));
        return clampToConfiguredRange(clamped, store);
    }

    private int resolvePlayerBasedLevelWithoutFallback(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        if (mobPos == null)
            return -1;

        if (isPartyPlayerSourceEnabled(store)) {
            int partyResolved = resolvePlayerBasedLevelWithPartySystem(store, mobPos, entityId);
            if (partyResolved > 0) {
                return partyResolved;
            }
            return resolveClassicPlayerBasedLevelWithoutFallback(store, mobPos, entityId);
        }

        return resolveClassicPlayerBasedLevelWithoutFallback(store, mobPos, entityId);
    }

    private int resolveClassicPlayerBasedLevelWithoutFallback(Store<EntityStore> store, Vector3d mobPos,
            Integer entityId) {
        if (mobPos == null) {
            return -1;
        }

        int nearestPlayerLevel = findNearestPlayerLevel(store, mobPos);
        if (nearestPlayerLevel <= 0)
            return -1;

        int offset = getPlayerBasedOffset(store);
        int minDiff = getPlayerBasedMinDifference(store);
        int maxDiff = getPlayerBasedMaxDifference(store);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int minAllowed = nearestPlayerLevel + minDiff;
        int maxAllowed = nearestPlayerLevel + maxDiff;
        int rolledDiff = samplePlayerDiff(entityId, mobPos, minDiff, maxDiff);
        int target = nearestPlayerLevel + offset + rolledDiff;
        int clamped = Math.max(minAllowed, Math.min(maxAllowed, target));
        return clamped;
    }

    private int resolveMixedLevel(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        if (mobPos == null) {
            return getFixedLevel(store, entityId, null);
        }

        int distanceLevel = resolveDistanceLevel(store, mobPos);
        int playerLevel = resolvePlayerBasedLevelWithoutFallback(store, mobPos, entityId);
        int playerLowerBound = resolvePlayerLowerBoundForMixed(store, mobPos);

        if (playerLevel > 0 && playerLowerBound > 0 && distanceLevel < playerLowerBound) {
            rememberMixedSourceChoice(store, entityId, MixedSourceChoice.PLAYER);
            return clampToConfiguredRange(Math.max(playerLevel, playerLowerBound), store);
        }

        MixedSourceChoice choice = resolveMixedSourceChoice(store, mobPos, entityId);
        if (choice == MixedSourceChoice.PLAYER) {
            if (playerLevel > 0) {
                return playerLevel;
            }
            return -1;
        }

        return distanceLevel;
    }

    private MixedSourceChoice resolveMixedSourceChoice(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        if (store != null && entityId != null) {
            long entityKey = toEntityKey(store, entityId);
            MixedSourceChoice existing = mixedSourceChoiceByEntityKey.get(entityKey);
            if (existing != null) {
                return existing;
            }
        }

        MixedSourceChoice rolled = rollMixedSourceChoice(store, entityId, mobPos);
        rememberMixedSourceChoice(store, entityId, rolled);
        return rolled;
    }

    private MixedSourceChoice rollMixedSourceChoice(Store<EntityStore> store, Integer entityId, Vector3d mobPos) {
        double playerWeight = getMixedPlayerWeight(store);
        if (playerWeight <= 0.0D) {
            return MixedSourceChoice.DISTANCE;
        }
        if (playerWeight >= 1.0D) {
            return MixedSourceChoice.PLAYER;
        }

        long seed = entityId != null ? entityId.longValue() : hashPosition(mobPos);
        long scrambled = seed ^ (seed >>> 33) ^ (seed << 11);
        long bucket = Math.floorMod(scrambled, 10_000L);
        double rolled = bucket / 10_000.0D;
        return rolled < playerWeight ? MixedSourceChoice.PLAYER : MixedSourceChoice.DISTANCE;
    }

    private void rememberMixedSourceChoice(Store<EntityStore> store, Integer entityId, MixedSourceChoice choice) {
        if (store == null || entityId == null || entityId < 0 || choice == null) {
            return;
        }
        mixedSourceChoiceByEntityKey.put(toEntityKey(store, entityId), choice);
    }

    private void clearMixedChoicesByEntityIndex(int entityIndex) {
        if (entityIndex < 0 || mixedSourceChoiceByEntityKey.isEmpty()) {
            return;
        }
        long idPart = Integer.toUnsignedLong(entityIndex);
        mixedSourceChoiceByEntityKey.keySet().removeIf(key -> (key & 0xFFFFFFFFL) == idPart);
    }

    private int resolvePlayerLowerBoundForMixed(Store<EntityStore> store, Vector3d mobPos) {
        if (mobPos == null) {
            return -1;
        }

        int xpMaxDifference = getExperienceXpMaxDifference(store);

        if (isPartyPlayerSourceEnabled(store)) {
            double radius = Math.max(0.0D, getPlayerPartyInfluenceRadius(store));
            List<PlayerContext> nearbyPlayers = getPlayersWithinRadius(store, mobPos, radius);
            if (nearbyPlayers.isEmpty()) {
                return -1;
            }
            PartyContext dominant = resolveDominantPartyContext(nearbyPlayers, store);
            if (dominant == null || dominant.members().isEmpty()) {
                return -1;
            }
            int vpl = computeVirtualPartyLevel(dominant.members(), store);
            int floor = vpl - xpMaxDifference;
            return clampToConfiguredRange(floor, store);
        }

        int nearestPlayerLevel = findNearestPlayerLevel(store, mobPos);
        if (nearestPlayerLevel <= 0) {
            return -1;
        }
        int floor = nearestPlayerLevel - xpMaxDifference;
        return clampToConfiguredRange(floor, store);
    }

    private int getExperienceXpMaxDifference(Store<EntityStore> store) {
        return Math.max(0, getGlobalConfigInt("Mob_Leveling.Experience.XP_Level_Range.Max_Difference", 10));
    }

    private int getMobScalingLevelDifferenceRange(Store<EntityStore> store) {
        String preferredMobScalingPath = "Mob_Leveling.Mob_Scaling.Mob_Level_Scaling_Difference.Range";
        if (hasMobLevelingPath(preferredMobScalingPath, store, null)) {
            return Math.max(0, getConfigInt(preferredMobScalingPath, 10, store));
        }

        // Alternate key variant under Mob_Scaling.
        String preferredMobScalingAliasPath = "Mob_Leveling.Mob_Scaling.Level_Scaling_Difference.Range";
        if (hasMobLevelingPath(preferredMobScalingAliasPath, store, null)) {
            return Math.max(0, getConfigInt(preferredMobScalingAliasPath, 10, store));
        }

        String preferredPath = "Mob_Leveling.Scaling.Level_Scaling_Difference.Range";
        if (hasMobLevelingPath(preferredPath, store, null)) {
            return Math.max(0, getConfigInt(preferredPath, 10, store));
        }

        // Backward compatibility for previous naming.
        String legacyPathV2 = "Mob_Leveling.Scaling.Level_Difference.Range";
        if (hasMobLevelingPath(legacyPathV2, store, null)) {
            return Math.max(0, getConfigInt(legacyPathV2, 10, store));
        }

        // Backward compatibility for older key format.
        String legacyPath = "Mob_Leveling.Scaling.Level_Difference.Max_Difference";
        if (hasMobLevelingPath(legacyPath, store, null)) {
            return Math.max(0, getConfigInt(legacyPath, 10, store));
        }

        // Backward compatibility: if the dedicated scaling value is not configured,
        // reuse the XP range max difference so existing servers keep current behavior.
        return getExperienceXpMaxDifference(store);
    }

    private int resolvePlayerBasedLevelWithPartySystem(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        double radius = Math.max(0.0D, getPlayerPartyInfluenceRadius(store));
        List<PlayerContext> nearbyPlayers = getPlayersWithinRadius(store, mobPos, radius);
        if (nearbyPlayers.isEmpty()) {
            return -1;
        }

        PartyContext dominant = resolveDominantPartyContext(nearbyPlayers, store);
        if (dominant == null || dominant.members().isEmpty()) {
            return -1;
        }

        int vpl = computeVirtualPartyLevel(dominant.members(), store);
        int offset = getPlayerBasedOffset(store);
        int minDiff = getPlayerBasedMinDifference(store);
        int maxDiff = getPlayerBasedMaxDifference(store);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int minLevel = clampToConfiguredRange(vpl + offset + minDiff, store);
        int maxLevel = clampToConfiguredRange(vpl + offset + maxDiff, store);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }

        int resolvedLevel = sampleLevel(minLevel, maxLevel, entityId, mobPos);
        return resolvedLevel;
    }

    private int fallbackPlayerSourceLevel(Store<EntityStore> store, Vector3d mobPos) {
        if (mobPos != null) {
            return resolveDistanceLevel(store, mobPos);
        }
        return getFixedLevel(store);
    }

    private List<PlayerContext> getPlayersWithinRadius(Store<EntityStore> mobStore, Vector3d mobPos, double radius) {
        Universe universe = Universe.get();
        if (universe == null || mobPos == null) {
            return List.of();
        }

        double radiusSq = radius <= 0.0D ? 0.0D : radius * radius;
        PartyManager partyManager = resolvePartyManager();

        List<PlayerContext> nearby = new ArrayList<>();
        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null) {
                continue;
            }

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            if (!isSameWorld(mobStore, playerStore)) {
                continue;
            }

            Vector3d playerPos = getWorldPosition(playerEntityRef, null);
            if (playerPos == null) {
                continue;
            }

            double distSq = horizontalDistanceSquared(mobPos, playerPos);
            if (radiusSq > 0.0D && distSq > radiusSq) {
                continue;
            }

            UUID uuid = playerRef.getUuid();
            int level = getPlayerLevel(uuid);
            UUID partyId = resolvePartyId(partyManager, uuid);
            nearby.add(new PlayerContext(uuid, level, distSq, partyId));
        }
        return nearby;
    }

    private PartyContext resolveDominantPartyContext(List<PlayerContext> nearbyPlayers, Store<EntityStore> store) {
        if (nearbyPlayers == null || nearbyPlayers.isEmpty()) {
            return null;
        }

        Map<UUID, List<PlayerContext>> groupedParties = new HashMap<>();
        List<PlayerContext> soloPlayers = new ArrayList<>();
        for (PlayerContext ctx : nearbyPlayers) {
            if (ctx == null) {
                continue;
            }
            if (ctx.partyId() != null) {
                groupedParties.computeIfAbsent(ctx.partyId(), ignored -> new ArrayList<>()).add(ctx);
            } else {
                soloPlayers.add(ctx);
            }
        }

        List<PartyContext> partyContexts = new ArrayList<>();
        if (!groupedParties.isEmpty()) {
            for (Map.Entry<UUID, List<PlayerContext>> entry : groupedParties.entrySet()) {
                List<PlayerContext> members = entry.getValue();
                if (members == null || members.isEmpty()) {
                    continue;
                }
                partyContexts.add(new PartyContext(entry.getKey(), members));
            }
        } else {
            for (PlayerContext solo : soloPlayers) {
                if (solo == null) {
                    continue;
                }
                partyContexts.add(new PartyContext(solo.playerId(), List.of(solo)));
            }
        }

        if (partyContexts.isEmpty()) {
            return null;
        }

        DominantPartyMode mode = getDominantPartyResolutionMode(store);
        PartyContext selected = null;
        for (PartyContext candidate : partyContexts) {
            if (candidate == null || candidate.members().isEmpty()) {
                continue;
            }
            if (selected == null) {
                selected = candidate;
                continue;
            }
            if (isBetterDominantParty(candidate, selected, mode)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private boolean isBetterDominantParty(PartyContext candidate, PartyContext current, DominantPartyMode mode) {
        if (candidate == null || current == null) {
            return false;
        }

        return switch (mode) {
            case MOST_MEMBERS -> {
                int candidateSize = candidate.members().size();
                int currentSize = current.members().size();
                if (candidateSize != currentSize) {
                    yield candidateSize > currentSize;
                }
                double candidateClosest = minDistanceSq(candidate.members());
                double currentClosest = minDistanceSq(current.members());
                if (Math.abs(candidateClosest - currentClosest) > 0.000001D) {
                    yield candidateClosest < currentClosest;
                }

                yield comparePartyId(candidate.partyId(), current.partyId()) < 0;
            }
            case CLOSEST_MEMBER ->

            {
                double candidateClosest = minDistanceSq(candidate.members());
                double currentClosest = minDistanceSq(current.members());
                if (Math.abs(candidateClosest - currentClosest) > 0.000001D) {
                    yield candidateClosest < currentClosest;
                }
                int candidateSize = candidate.members().size();
                int currentSize = current.members().size();
                if (candidateSize != currentSize) {
                    yield candidateSize > currentSize;
                }

                yield comparePartyId(candidate.partyId(), current.partyId()) < 0;
            }
            case HIGHEST_AVERAGE ->

            {
                double candidateAverage = averageLevel(candidate.members());
                double currentAverage = averageLevel(current.members());
                if (Math.abs(candidateAverage - currentAverage) > 0.000001D) {
                    yield candidateAverage > currentAverage;
                }
                double candidateClosest = minDistanceSq(candidate.members());
                double currentClosest = minDistanceSq(current.members());
                if (Math.abs(candidateClosest - currentClosest) > 0.000001D) {
                    yield candidateClosest < currentClosest;
                }

                yield comparePartyId(candidate.partyId(), current.partyId()) < 0;
            }

        };
    }

    private int computeVirtualPartyLevel(List<PlayerContext> members, Store<EntityStore> store) {
        if (members == null || members.isEmpty()) {
            return 1;
        }

        PartyLevelCalculation calculation = getPartyLevelCalculationMode(store);
        return switch (calculation) {
            case MEDIAN -> medianLevel(members);
            case AVERAGE -> (int) Math.round(averageLevel(members));
        };
    }

    private double averageLevel(List<PlayerContext> members) {
        if (members == null || members.isEmpty()) {
            return 1.0D;
        }
        double total = 0.0D;
        int count = 0;
        for (PlayerContext member : members) {
            if (member == null) {
                continue;
            }
            total += Math.max(1, member.level());
            count++;
        }
        if (count <= 0) {
            return 1.0D;
        }
        return total / count;
    }

    private int medianLevel(List<PlayerContext> members) {
        if (members == null || members.isEmpty()) {
            return 1;
        }
        List<Integer> levels = new ArrayList<>(members.size());
        for (PlayerContext member : members) {
            if (member == null) {
                continue;
            }
            levels.add(Math.max(1, member.level()));
        }
        if (levels.isEmpty()) {
            return 1;
        }
        Collections.sort(levels);
        int size = levels.size();
        int mid = size / 2;
        if (size % 2 == 1) {
            return levels.get(mid);
        }
        double median = (levels.get(mid - 1) + levels.get(mid)) / 2.0D;
        return (int) Math.round(median);
    }

    private double minDistanceSq(List<PlayerContext> members) {
        if (members == null || members.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double min = Double.MAX_VALUE;
        for (PlayerContext member : members) {
            if (member == null) {
                continue;
            }
            min = Math.min(min, Math.max(0.0D, member.distanceSq()));
        }
        return min;
    }

    private int comparePartyId(UUID a, UUID b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    private UUID resolvePartyId(PartyManager partyManager, UUID playerId) {
        if (partyManager == null || playerId == null || !partyManager.isAvailable()) {
            return null;
        }
        if (!partyManager.isInParty(playerId)) {
            return null;
        }
        UUID leader = partyManager.getPartyLeader(playerId);
        return leader != null ? leader : playerId;
    }

    private PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    private boolean isPartyPlayerSourceEnabled() {
        return isPartyPlayerSourceEnabled(null);
    }

    private boolean isPartyPlayerSourceEnabled(Store<EntityStore> store) {
        return getPartySystemBoolean("Enabled", false, store);
    }

    public double getPartyXpShareRange(Store<EntityStore> store) {
        double configured = getPartySystemDouble("XP_Share_Range", Double.NaN, store);
        if (!Double.isFinite(configured)) {
            configured = getPartySystemDouble("Share_Range", Double.NaN, store);
        }
        if (!Double.isFinite(configured)) {
            configured = getPartySystemDouble("Influence_Radius", Double.NaN, store);
        }
        if (Double.isFinite(configured)) {
            return Math.max(0.0D, configured);
        }

        // Backward compatibility when no share range is configured.
        return Math.max(0.0D, getPlayerPartyInfluenceRadius(store));
    }

    private double getPlayerPartyInfluenceRadius() {
        return getPlayerPartyInfluenceRadius(null);
    }

    private double getPlayerPartyInfluenceRadius(Store<EntityStore> store) {
        double preferred = getPlayerBasedDouble("Influence_Radius", Double.NaN, store);
        if (Double.isFinite(preferred)) {
            return Math.max(0.0D, preferred);
        }

        // Backward compatibility for legacy nesting.
        return Math.max(0.0D, getPlayerBasedDouble("Party_System.Influence_Radius", 25.0D, store));
    }

    private PartyLevelCalculation getPartyLevelCalculationMode() {
        return getPartyLevelCalculationMode(null);
    }

    private PartyLevelCalculation getPartyLevelCalculationMode(Store<EntityStore> store) {
        String raw = getPartySystemString("Level_Calculation", "AVERAGE", store);
        if (raw == null) {
            return PartyLevelCalculation.AVERAGE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("MEDIAN".equals(normalized)) {
            return PartyLevelCalculation.MEDIAN;
        }
        return PartyLevelCalculation.AVERAGE;
    }

    private DominantPartyMode getDominantPartyResolutionMode() {
        return getDominantPartyResolutionMode(null);
    }

    private DominantPartyMode getDominantPartyResolutionMode(Store<EntityStore> store) {
        String raw = getPartySystemString("Dominant_Party_Resolution.Mode", "MOST_MEMBERS", store);
        if (raw == null) {
            return DominantPartyMode.MOST_MEMBERS;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CLOSEST_MEMBER" -> DominantPartyMode.CLOSEST_MEMBER;
            case "HIGHEST_AVERAGE" -> DominantPartyMode.HIGHEST_AVERAGE;
            default -> DominantPartyMode.MOST_MEMBERS;
        };
    }

    private int getPlayerBasedOffset() {
        return getPlayerBasedOffset(null);
    }

    private int getPlayerBasedOffset(Store<EntityStore> store) {
        return getPlayerBasedInt("Offset", 0, store);
    }

    private int getPlayerBasedMinDifference() {
        return getPlayerBasedMinDifference(null);
    }

    private int getPlayerBasedMinDifference(Store<EntityStore> store) {
        return getPlayerBasedInt("Min_Difference", -3, store);
    }

    private int getPlayerBasedMaxDifference() {
        return getPlayerBasedMaxDifference(null);
    }

    private int getPlayerBasedMaxDifference(Store<EntityStore> store) {
        return getPlayerBasedInt("Max_Difference", 3, store);
    }

    private String getPlayerBasedString(String suffix, String defaultValue) {
        return getPlayerBasedString(suffix, defaultValue, null);
    }

    private String getPlayerBasedString(String suffix, String defaultValue, Store<EntityStore> store) {
        String primary = "Mob_Leveling.Level_Source.Player_Based." + suffix;
        String fallback = "Mob_Leveling.Player_Based." + suffix;
        if (hasMobLevelingPath(primary, store, null)) {
            Object raw = getMobLevelingValue(primary, defaultValue, store, null);
            return raw != null ? raw.toString() : defaultValue;
        }
        Object raw = getMobLevelingValue(fallback, defaultValue, store, null);
        return raw != null ? raw.toString() : defaultValue;
    }

    private int getPlayerBasedInt(String suffix, int defaultValue) {
        return getPlayerBasedInt(suffix, defaultValue, null);
    }

    private int getPlayerBasedInt(String suffix, int defaultValue, Store<EntityStore> store) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue), store);
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double getPlayerBasedDouble(String suffix, double defaultValue) {
        return getPlayerBasedDouble(suffix, defaultValue, null);
    }

    private double getPlayerBasedDouble(String suffix, double defaultValue, Store<EntityStore> store) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue), store);
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean getPlayerBasedBoolean(String suffix, boolean defaultValue) {
        return getPlayerBasedBoolean(suffix, defaultValue, null);
    }

    private boolean getPlayerBasedBoolean(String suffix, boolean defaultValue, Store<EntityStore> store) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue), store);
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private String getPartySystemString(String suffix, String defaultValue) {
        return getPartySystemString(suffix, defaultValue, null);
    }

    private String getPartySystemString(String suffix, String defaultValue, Store<EntityStore> store) {
        String primary = "Mob_Leveling.Level_Source.Party_System." + suffix;
        String fallbackGlobal = "Mob_Leveling.Party_System." + suffix;
        String legacyLevelSource = "Mob_Leveling.Level_Source.Player_Based.Party_System." + suffix;
        String legacyRoot = "Mob_Leveling.Player_Based.Party_System." + suffix;

        if (hasMobLevelingPath(primary, store, null)) {
            Object raw = getMobLevelingValue(primary, defaultValue, store, null);
            return raw != null ? raw.toString() : defaultValue;
        }

        if (hasMobLevelingPath(fallbackGlobal, store, null)) {
            Object raw = getMobLevelingValue(fallbackGlobal, defaultValue, store, null);
            return raw != null ? raw.toString() : defaultValue;
        }

        if (hasMobLevelingPath(legacyLevelSource, store, null)) {
            Object raw = getMobLevelingValue(legacyLevelSource, defaultValue, store, null);
            return raw != null ? raw.toString() : defaultValue;
        }

        Object raw = getMobLevelingValue(legacyRoot, defaultValue, store, null);
        return raw != null ? raw.toString() : defaultValue;
    }

    private boolean getPartySystemBoolean(String suffix, boolean defaultValue) {
        return getPartySystemBoolean(suffix, defaultValue, null);
    }

    private boolean getPartySystemBoolean(String suffix, boolean defaultValue, Store<EntityStore> store) {
        String raw = getPartySystemString(suffix, String.valueOf(defaultValue), store);
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private double getPartySystemDouble(String suffix, double defaultValue) {
        return getPartySystemDouble(suffix, defaultValue, null);
    }

    private double getPartySystemDouble(String suffix, double defaultValue, Store<EntityStore> store) {
        String raw = getPartySystemString(suffix, String.valueOf(defaultValue), store);
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double getMixedPlayerWeight() {
        return getMixedPlayerWeight(null);
    }

    private double getMixedPlayerWeight(Store<EntityStore> store) {
        return clampWeight(getMixedDouble("Player_Weight", 0.5D, store));
    }

    private String getMixedString(String suffix, String defaultValue) {
        return getMixedString(suffix, defaultValue, null);
    }

    private String getMixedString(String suffix, String defaultValue, Store<EntityStore> store) {
        String primary = "Mob_Leveling.Level_Source.Mixed." + suffix;
        String fallback = "Mob_Leveling.Mixed." + suffix;
        if (hasMobLevelingPath(primary, store, null)) {
            Object raw = getMobLevelingValue(primary, defaultValue, store, null);
            return raw != null ? raw.toString() : defaultValue;
        }
        Object raw = getMobLevelingValue(fallback, defaultValue, store, null);
        return raw != null ? raw.toString() : defaultValue;
    }

    private double getMixedDouble(String suffix, double defaultValue) {
        return getMixedDouble(suffix, defaultValue, null);
    }

    private double getMixedDouble(String suffix, double defaultValue, Store<EntityStore> store) {
        String raw = getMixedString(suffix, String.valueOf(defaultValue), store);
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double clampWeight(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private enum PartyLevelCalculation {
        AVERAGE,
        MEDIAN
    }

    private enum DominantPartyMode {
        MOST_MEMBERS,
        CLOSEST_MEMBER,
        HIGHEST_AVERAGE
    }

    private enum TierPromotionAllowanceMode {
        BELOW,
        ABOVE
    }

    private record PlayerContext(UUID playerId, int level, double distanceSq, UUID partyId) {
    }

    private record PartyContext(UUID partyId, List<PlayerContext> members) {
    }

    private record TierCount(int totalTiers, boolean endless) {
    }

    private int samplePlayerDiff(Integer entityId, Vector3d mobPos, int minDiff, int maxDiff) {
        int span = Math.max(1, (maxDiff - minDiff) + 1);
        long baseSeed = entityId != null ? entityId.longValue() : hashPosition(mobPos);
        long scrambled = baseSeed ^ (baseSeed >>> 33) ^ (baseSeed << 11);
        int rolled = minDiff + (int) Math.floorMod(scrambled, span);
        if (entityId != null) {
            return cachedPlayerDiffs.computeIfAbsent(entityId, ignored -> rolled);
        }
        long posKey = hashPosition(mobPos);
        return cachedPosDiffs.computeIfAbsent(posKey, ignored -> rolled);
    }

    private long hashPosition(Vector3d pos) {
        if (pos == null) {
            return 0L;
        }
        long xBits = Double.doubleToLongBits(pos.getX());
        long zBits = Double.doubleToLongBits(pos.getZ());
        long hash = 31L * xBits + zBits;
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        return hash;
    }

    private int findNearestPlayerLevel(Store<EntityStore> mobStore, Vector3d mobPos) {
        return findNearestPlayerLevelWithinRadius(mobStore, mobPos, -1.0D);
    }

    private int findNearestPlayerLevelWithinRadius(Store<EntityStore> mobStore, Vector3d mobPos,
            double radiusBlocks) {
        if (mobPos == null) {
            return -1;
        }

        Universe universe = Universe.get();
        if (universe == null)
            return -1;

        double radiusSq = radiusBlocks > 0.0D ? radiusBlocks * radiusBlocks : -1.0D;
        double closestDistSq = Double.MAX_VALUE;
        int bestLevel = -1;

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid())
                continue;
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null)
                continue;

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            if (!isSameWorld(mobStore, playerStore))
                continue;

            Vector3d playerPos = getWorldPosition(playerEntityRef, null);
            if (playerPos == null)
                continue;

            double distSq = horizontalDistanceSquared(mobPos, playerPos);
            if (radiusSq >= 0.0D && distSq > radiusSq) {
                continue;
            }
            if (distSq < closestDistSq) {
                int level = getPlayerLevel(playerRef.getUuid());
                closestDistSq = distSq;
                bestLevel = level;
            }
        }

        return bestLevel;
    }

    private Vector3d getWorldPosition(Ref<EntityStore> entityRef, CommandBuffer<EntityStore> commandBuffer) {
        if (entityRef == null)
            return null;

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

    private String resolveWorldId(Store<EntityStore> store, Object worldHint) {
        Object world = worldHint != null ? worldHint : resolveWorldObject(store);
        if (world == null) {
            return null;
        }

        String[] idMethods = new String[] { "getName", "getId", "getIdentifier" };
        for (String methodName : idMethods) {
            Object value = invokeNoArg(world, methodName);
            if (value == null) {
                continue;
            }
            String text = value.toString();
            if (!text.isBlank()) {
                return text;
            }
        }

        return world.toString();
    }

    private String resolveWorldId(Store<EntityStore> store) {
        return resolveWorldId(store, null);
    }

    public String resolveWorldIdentifier(Store<EntityStore> store) {
        return resolveWorldId(store, null);
    }

    private boolean isSameWorld(Store<EntityStore> mobStore, Store<EntityStore> playerStore) {
        if (mobStore == null || playerStore == null) {
            return true;
        }
        if (mobStore == playerStore) {
            return true;
        }

        String mobWorldId = resolveWorldId(mobStore);
        String playerWorldId = resolveWorldId(playerStore);
        if (mobWorldId == null || playerWorldId == null) {
            return true;
        }
        return mobWorldId.equalsIgnoreCase(playerWorldId);
    }

    private double horizontalDistanceSquared(Vector3d a, Vector3d b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (dx * dx) + (dz * dz);
    }

    private int getPlayerLevel(UUID uuid) {
        if (uuid == null || playerDataManager == null)
            return 1;
        PlayerData data = playerDataManager.get(uuid);
        if (data == null)
            return 1;
        return Math.max(1, data.getLevel());
    }

    public int getPlayerLevel(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return 1;
        }
        return getPlayerLevel(playerRef.getUuid());
    }

    /** Whether mob leveling is enabled (Mob_Leveling.Enabled) */
    public boolean isMobLevelingEnabled() {
        Object raw = getMobLevelingValue("Mob_Leveling.Enabled", Boolean.TRUE, null, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /** Whether passive mobs are allowed to be leveled */
    public boolean allowPassiveMobLeveling() {
        Object raw = getMobLevelingValue("Mob_Leveling.allow_passive_mob_leveling", Boolean.FALSE, null, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /** Returns true if mob type is blacklisted (case-insensitive) */
    public boolean isMobTypeBlacklisted(String mobType) {
        if (mobType == null || mobType.isBlank())
            return false;

        String normalizedMob = normalizeMobIdentifier(mobType);
        if (normalizedMob == null)
            return false;

        Object raw = getMobLevelingValue("Mob_Leveling.Blacklist_Mob_Types", null, null, null);
        if (raw == null)
            return false;

        if (raw instanceof Map<?, ?> map) {
            List<String> idRules = readStringList(map.get("ids"));
            List<String> keywordRules = readStringList(map.get("keywords"));

            if (matchesAnyIdRule(normalizedMob, idRules)) {
                return true;
            }
            return matchesAnyKeywordRule(normalizedMob, keywordRules);
        }

        if (raw instanceof Iterable<?> iterable) {
            return matchesAnyIdRule(normalizedMob, readStringList(iterable));
        }

        return matchesAnyIdRule(normalizedMob, Collections.singletonList(raw.toString()));
    }

    private static List<String> readStringList(Object node) {
        if (node == null) {
            return Collections.emptyList();
        }

        List<String> values = new ArrayList<>();
        if (node instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value == null) {
                    continue;
                }
                String text = value.toString().trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
            return values;
        }

        String single = node.toString().trim();
        if (!single.isEmpty()) {
            values.add(single);
        }
        return values;
    }

    private static boolean matchesAnyIdRule(String normalizedMob, List<String> idRules) {
        if (normalizedMob == null || idRules == null || idRules.isEmpty()) {
            return false;
        }

        for (String rule : idRules) {
            String normalizedRule = normalizeMobIdentifier(rule);
            if (normalizedRule == null) {
                continue;
            }
            if (matchesWildcard(normalizedMob, normalizedRule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyKeywordRule(String normalizedMob, List<String> keywordRules) {
        if (normalizedMob == null || keywordRules == null || keywordRules.isEmpty()) {
            return false;
        }

        List<String> tokens = tokenizeIdentifier(normalizedMob);
        for (String keyword : keywordRules) {
            String normalizedKeyword = normalizeKeyword(keyword);
            if (normalizedKeyword == null) {
                continue;
            }

            if (normalizedKeyword.indexOf('*') >= 0) {
                if (matchesWildcard(normalizedMob, normalizedKeyword)) {
                    return true;
                }
                for (String token : tokens) {
                    if (matchesWildcard(token, normalizedKeyword)) {
                        return true;
                    }
                }
                continue;
            }

            if (normalizedMob.equals(normalizedKeyword) || normalizedMob.contains(normalizedKeyword)) {
                return true;
            }

            for (String token : tokens) {
                if (token.equals(normalizedKeyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeMobIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int namespaceIndex = trimmed.lastIndexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex < trimmed.length() - 1) {
            trimmed = trimmed.substring(namespaceIndex + 1);
        }
        return trimmed.replace('-', '_')
                .replace('.', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizeKeyword(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replace('-', '_')
                .replace('.', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static List<String> tokenizeIdentifier(String normalizedIdentifier) {
        if (normalizedIdentifier == null || normalizedIdentifier.isBlank()) {
            return Collections.emptyList();
        }

        String[] rawParts = normalizedIdentifier.split("[^A-Z0-9]+");
        if (rawParts.length == 0) {
            return Collections.emptyList();
        }

        List<String> parts = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            parts.add(part);
        }
        return parts;
    }

    private static boolean matchesWildcard(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }

        int textIndex = 0;
        int patternIndex = 0;
        int starIndex = -1;
        int matchIndex = 0;

        while (textIndex < text.length()) {
            if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == text.charAt(textIndex)) {
                patternIndex++;
                textIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex;
                matchIndex = textIndex;
                patternIndex++;
            } else if (starIndex != -1) {
                patternIndex = starIndex + 1;
                matchIndex++;
                textIndex = matchIndex;
            } else {
                return false;
            }
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }

        return patternIndex == pattern.length();
    }

    /** Returns true if the referenced entity matches a blacklisted mob type */
    public boolean isEntityBlacklisted(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null)
            return false;
        String mobType = resolveMobType(ref, store, commandBuffer);
        return mobType != null && isMobTypeBlacklisted(mobType);
    }

    private String resolveMobType(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = resolveComponent(ref, store, commandBuffer, NPCEntity.getComponentType());
        if (npc != null) {
            try {
                String npcTypeId = npc.getNPCTypeId();
                if (npcTypeId != null && !npcTypeId.isBlank())
                    return npcTypeId;
            } catch (Throwable ignored) {
            }
        }

        WorldGenId worldGenId = resolveComponent(ref, store, commandBuffer, WorldGenId.getComponentType());
        if (worldGenId != null) {
            try {
                return Integer.toString(worldGenId.getWorldGenId());
            } catch (Throwable ignored) {
                try {
                    return worldGenId.toString();
                } catch (Throwable ignored2) {
                }
            }
        }
        return null;
    }

    public double getMobHealthMultiplierForLevel(int level) {
        return getMobHealthMultiplierForLevel(level, null);
    }

    private double getMobHealthMultiplierForLevel(int level, Store<EntityStore> store) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Health.Base_Multiplier", 1.0, store);
        double per = getConfigDouble("Mob_Leveling.Scaling.Health.Per_Level", 0.05, store);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    public double getMobHealthMultiplierForLevel(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int level) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        return getMobHealthMultiplierForLevel(ref, store, commandBuffer, level);
    }

    public double getMobHealthMultiplierForLevel(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            int level) {
        double baseMultiplier = getMobHealthMultiplierForLevel(level, store);
        double resolvedMultiplier = baseMultiplier;
        WorldMobOverride override = resolveWorldMobOverride(ref, store, commandBuffer);
        if (override != null) {
            if (override.healthMultiplier() != null) {
                resolvedMultiplier = override.healthMultiplier();
            } else {
                Double scaledOverride = resolveMobOverrideLinearScaling(
                        override.healthScaling(),
                        level,
                        "Mob_Leveling.Scaling.Health.Base_Multiplier",
                        1.0D,
                        "Mob_Leveling.Scaling.Health.Per_Level",
                        0.05D,
                        store);
                if (scaledOverride != null) {
                    resolvedMultiplier = scaledOverride;
                }
            }

            MobOverrideAugmentModifiers augmentModifiers = override.augmentModifiers();
            if (augmentModifiers != null) {
                resolvedMultiplier *= augmentModifiers.healthMultiplier();
            }
        }
        return Math.max(0.0001D, resolvedMultiplier);
    }

    /**
     * Computes additive max-health offset for a mob level from an unmodified base
     * max value.
     */
    public float computeMobHealthAdditive(int level, float baseMax) {
        if (!Float.isFinite(baseMax) || baseMax <= 0.0f) {
            return 0.0f;
        }
        double multiplier = getMobHealthMultiplierForLevel(level);
        float targetMax = (float) (baseMax * multiplier);
        return targetMax - baseMax;
    }

    /**
     * Computes mob health scaling from config-derived multipliers.
     * This is pure math and does not mutate ECS components.
     */
    public MobHealthScalingResult computeMobHealthScaling(int level,
            float baseMax,
            float previousMax,
            float previousValue) {
        float safeBaseMax = Math.max(1.0f, baseMax);
        double multiplier = getMobHealthMultiplierForLevel(level);
        float targetMax = (float) Math.max(1.0D, safeBaseMax * multiplier);
        float additive = targetMax - safeBaseMax;

        float ratio = previousMax > 0.0f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.0f, Math.min(targetMax, ratio * targetMax));
        if (previousValue <= 0.0f) {
            newValue = 0.0f;
        }

        return new MobHealthScalingResult(targetMax, additive, newValue);
    }

    public MobHealthScalingResult computeMobHealthScaling(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            int level,
            float baseMax,
            float previousMax,
            float previousValue) {
        float safeBaseMax = Math.max(1.0f, baseMax);
        double multiplier = getMobHealthMultiplierForLevel(ref, store, commandBuffer, level);
        float targetMax = (float) Math.max(1.0D, safeBaseMax * multiplier);
        float additive = targetMax - safeBaseMax;

        float ratio = previousMax > 0.0f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.0f, Math.min(targetMax, ratio * targetMax));
        if (previousValue <= 0.0f) {
            newValue = 0.0f;
        }

        return new MobHealthScalingResult(targetMax, additive, newValue);
    }

    public record MobHealthScalingResult(float targetMax, float additive, float newValue) {
    }

    public double getMobDamageMultiplierForLevel(int level) {
        return getMobDamageMultiplierForLevel(level, null);
    }

    private double getMobDamageMultiplierForLevel(int level, Store<EntityStore> store) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Damage.Base_Multiplier", 1.0, store);
        double per = getConfigDouble("Mob_Leveling.Scaling.Damage.Per_Level", 0.03, store);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    public double getMobDamageMultiplierForLevel(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int level) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        double baseMultiplier = getMobDamageMultiplierForLevel(level, store);
        double resolvedMultiplier = baseMultiplier;
        WorldMobOverride override = resolveWorldMobOverride(ref, store, commandBuffer);
        if (override != null) {
            if (override.damageMultiplier() != null) {
                resolvedMultiplier = override.damageMultiplier();
            } else {
                Double scaledOverride = resolveMobOverrideLinearScaling(
                        override.damageScaling(),
                        level,
                        "Mob_Leveling.Scaling.Damage.Base_Multiplier",
                        1.0D,
                        "Mob_Leveling.Scaling.Damage.Per_Level",
                        0.03D,
                        store);
                if (scaledOverride != null) {
                    resolvedMultiplier = scaledOverride;
                }
            }

            MobOverrideAugmentModifiers augmentModifiers = override.augmentModifiers();
            if (augmentModifiers != null) {
                resolvedMultiplier *= augmentModifiers.damageMultiplier();
            }
        }
        return Math.max(0.0001D, resolvedMultiplier);
    }

    /**
     * Resolve final mob damage multiplier for a mob-vs-player matchup.
     * This combines level-based damage scaling and max-difference scaling.
     */
    public double getMobDamageMultiplierForLevels(int mobLevel, int playerLevel) {
        double baseMultiplier = getMobDamageMultiplierForLevel(mobLevel);
        double differenceMultiplier = getMobDamageMaxDifferenceMultiplierForLevels(mobLevel, playerLevel);
        return Math.max(0.0001D, baseMultiplier * differenceMultiplier);
    }

    public double getMobDamageMultiplierForLevels(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int mobLevel,
            int playerLevel) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        double baseMultiplier = getMobDamageMultiplierForLevel(ref, commandBuffer, mobLevel);

        int safeMobLevel = Math.max(1, mobLevel);
        int safePlayerLevel = Math.max(1, playerLevel);
        int levelDifference = safeMobLevel - safePlayerLevel;

        double differenceMultiplier = getMobDamageMaxDifferenceMultiplierForLevelDifference(store, levelDifference);

        return Math.max(0.0001D, baseMultiplier * differenceMultiplier);
    }

    public double getMobDamageMaxDifferenceMultiplierForLevels(int mobLevel, int playerLevel) {
        return getMobDamageMaxDifferenceMultiplierForLevels(null, mobLevel, playerLevel);
    }

    private double getMobDamageMaxDifferenceMultiplierForLevels(Store<EntityStore> store, int mobLevel,
            int playerLevel) {
        int safeMobLevel = Math.max(1, mobLevel);
        int safePlayerLevel = Math.max(1, playerLevel);
        int levelDifference = safeMobLevel - safePlayerLevel;
        return getMobDamageMaxDifferenceMultiplierForLevelDifference(store, levelDifference);
    }

    private double getMobDamageMaxDifferenceMultiplierForLevelDifference(Store<EntityStore> store,
            int levelDifference) {
        if (!isMobDamageMaxDifferenceScalingEnabled(store)) {
            return 1.0D;
        }

        int maxDifference = getMobScalingLevelDifferenceRange(store);
        double atPositiveMax = clampNonNegativeMultiplier(
                getMobDamageMaxDifferenceConfigDouble("At_Positive_Max_Difference", 1.0D, store));

        // Baseline at same level: default 1.0x, but allow explicit zeroing behavior
        // when
        // At_Positive_Max_Difference is configured as 0 and negative-side keys are
        // omitted.
        double sameLevelBase = atPositiveMax <= 0.0D ? 0.0D : 1.0D;

        double atNegativeMax = clampNonNegativeMultiplier(
                getMobDamageMaxDifferenceConfigDouble("At_Negative_Max_Difference", sameLevelBase, store));
        double belowNegativeMax = clampNonNegativeMultiplier(
                getMobDamageMaxDifferenceConfigDouble("Below_Negative_Max_Difference", atNegativeMax, store));
        double abovePositiveMax = clampNonNegativeMultiplier(
                getMobDamageMaxDifferenceConfigDouble("Above_Positive_Max_Difference", 1.0D, store));

        if (maxDifference <= 0) {
            if (levelDifference > 0) {
                return abovePositiveMax;
            }
            if (levelDifference < 0) {
                return belowNegativeMax;
            }
            return sameLevelBase;
        }

        if (levelDifference == 0) {
            return sameLevelBase;
        }

        if (levelDifference < -maxDifference) {
            return belowNegativeMax;
        }

        if (levelDifference > maxDifference) {
            return abovePositiveMax;
        }

        if (levelDifference < 0) {
            double ratio = Math.abs(levelDifference) / (double) maxDifference;
            return lerp(sameLevelBase, atNegativeMax, ratio);
        }

        double ratio = levelDifference / (double) maxDifference;
        return lerp(sameLevelBase, atPositiveMax, ratio);
    }

    private boolean isMobDamageMaxDifferenceScalingEnabled(Store<EntityStore> store) {
        return getMobDamageMaxDifferenceConfigBoolean("Enabled", true, store);
    }

    private double getMobDamageMaxDifferenceConfigDouble(String suffix,
            double defaultValue,
            Store<EntityStore> store) {
        String preferredMobScalingPath = "Mob_Leveling.Mob_Scaling.Damage_Max_Difference." + suffix;
        if (hasMobLevelingPath(preferredMobScalingPath, store, null)) {
            return getConfigDouble(preferredMobScalingPath, defaultValue, store);
        }

        String modernPath = "Mob_Leveling.Damage_Max_Difference." + suffix;
        if (hasMobLevelingPath(modernPath, store, null)) {
            return getConfigDouble(modernPath, defaultValue, store);
        }

        String legacyPath = "Mob_Leveling.Scaling.Damage.Max_Difference." + suffix;
        if (hasMobLevelingPath(legacyPath, store, null)) {
            return getConfigDouble(legacyPath, defaultValue, store);
        }

        return defaultValue;
    }

    private boolean getMobDamageMaxDifferenceConfigBoolean(String suffix,
            boolean defaultValue,
            Store<EntityStore> store) {
        String preferredMobScalingPath = "Mob_Leveling.Mob_Scaling.Damage_Max_Difference." + suffix;
        if (hasMobLevelingPath(preferredMobScalingPath, store, null)) {
            return getConfigBoolean(preferredMobScalingPath, defaultValue, store);
        }

        String modernPath = "Mob_Leveling.Damage_Max_Difference." + suffix;
        if (hasMobLevelingPath(modernPath, store, null)) {
            return getConfigBoolean(modernPath, defaultValue, store);
        }

        String legacyPath = "Mob_Leveling.Scaling.Damage.Max_Difference." + suffix;
        if (hasMobLevelingPath(legacyPath, store, null)) {
            return getConfigBoolean(legacyPath, defaultValue, store);
        }

        return defaultValue;
    }

    public boolean isMobDamageScalingEnabled() {
        Object raw = getMobLevelingValue("Mob_Leveling.Scaling.Damage.Enabled", Boolean.FALSE, null, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    public boolean isMobHealthScalingEnabled() {
        return isMobHealthScalingEnabled(null);
    }

    public boolean isMobHealthScalingEnabled(Store<EntityStore> store) {
        Object raw = getMobLevelingValue("Mob_Leveling.Scaling.Health.Enabled", Boolean.FALSE, store, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /**
     * Whether mob defense scaling is enabled.
     */
    public boolean isMobDefenseScalingEnabled() {
        return isMobDefenseScalingEnabled(null);
    }

    private boolean isMobDefenseScalingEnabled(Store<EntityStore> store) {
        Object raw = getMobLevelingValue("Mob_Leveling.Scaling.Defense.Enabled", Boolean.FALSE, store, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /**
     * Resolve defense reduction for a mob against a specific player level matchup.
     *
     * @param mobLevel    target mob level
     * @param playerLevel attacking player level
     * @return reduction ratio in [0,1], where 0.75 means 75% damage reduction.
     */
    public double getMobDefenseReductionForLevels(int mobLevel, int playerLevel) {
        return getMobDefenseReductionForLevels(null, mobLevel, playerLevel);
    }

    private double getMobDefenseReductionForLevels(Store<EntityStore> store, int mobLevel, int playerLevel) {
        int safeMobLevel = Math.max(1, mobLevel);
        int safePlayerLevel = Math.max(1, playerLevel);
        int levelDifference = safeMobLevel - safePlayerLevel;
        return getMobDefenseReductionForLevelDifference(store, levelDifference);
    }

    public double getMobDefenseReductionForLevels(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int mobLevel,
            int playerLevel) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        int safeMobLevel = Math.max(1, mobLevel);
        int safePlayerLevel = Math.max(1, playerLevel);
        int levelDifference = safeMobLevel - safePlayerLevel;

        double baseReduction = getMobDefenseReductionForLevelDifference(store, levelDifference);

        WorldMobOverride override = resolveWorldMobOverride(ref, store, commandBuffer);
        if (override != null) {
            if (override.defenseReduction() != null) {
                return clampReduction(override.defenseReduction());
            }

            Double scaledOverride = resolveMobOverrideDefenseScaling(
                    override.defenseScaling(),
                    levelDifference,
                    store);
            if (scaledOverride != null) {
                return clampReduction(scaledOverride);
            }
        }

        return baseReduction;
    }

    private Double resolveMobOverrideLinearScaling(MobOverrideLinearScaling scaling,
            int level,
            String basePath,
            double defaultBase,
            String perLevelPath,
            double defaultPerLevel,
            Store<EntityStore> store) {
        if (scaling == null) {
            return null;
        }
        if (!scaling.enabled()) {
            return 1.0D;
        }

        double base = scaling.baseMultiplier() != null
                ? scaling.baseMultiplier()
                : getConfigDouble(basePath, defaultBase, store);
        double perLevel = scaling.perLevel() != null
                ? scaling.perLevel()
                : getConfigDouble(perLevelPath, defaultPerLevel, store);

        int effectiveLevel = Math.max(1, level);
        return base * (1.0D + perLevel * (effectiveLevel - 1));
    }

    private Double resolveMobOverrideDefenseScaling(MobOverrideDefenseScaling scaling,
            int levelDifference,
            Store<EntityStore> store) {
        if (scaling == null) {
            return null;
        }
        if (!scaling.enabled()) {
            return 0.0D;
        }

        int maxDifference = getMobScalingLevelDifferenceRange(store);

        double atNegativeMax = scaling.atNegativeMaxDifference() != null
                ? clampReduction(scaling.atNegativeMaxDifference())
                : clampReduction(
                        getConfigDouble("Mob_Leveling.Scaling.Defense.At_Negative_Max_Difference", 0.0D, store));
        double atPositiveMax = scaling.atPositiveMaxDifference() != null
                ? clampReduction(scaling.atPositiveMaxDifference())
                : clampReduction(
                        getConfigDouble("Mob_Leveling.Scaling.Defense.At_Positive_Max_Difference", 0.75D, store));
        double belowNegativeMax = scaling.belowNegativeMaxDifference() != null
                ? clampReduction(scaling.belowNegativeMaxDifference())
                : clampReduction(
                        getConfigDouble("Mob_Leveling.Scaling.Defense.Below_Negative_Max_Difference", 0.0D, store));
        double abovePositiveMax = scaling.abovePositiveMaxDifference() != null
                ? clampReduction(scaling.abovePositiveMaxDifference())
                : clampReduction(
                        getConfigDouble("Mob_Leveling.Scaling.Defense.Above_Positive_Max_Difference", 0.90D, store));

        if (maxDifference <= 0) {
            if (levelDifference > 0) {
                return abovePositiveMax;
            }
            if (levelDifference < 0) {
                return belowNegativeMax;
            }
            return atNegativeMax;
        }

        if (levelDifference < -maxDifference) {
            return belowNegativeMax;
        }
        if (levelDifference > maxDifference) {
            return abovePositiveMax;
        }

        double ratio = (levelDifference + maxDifference) / (double) (maxDifference * 2);
        return lerp(atNegativeMax, atPositiveMax, ratio);
    }

    /**
     * Resolve defense reduction from relative level difference (mob - player).
     */
    public double getMobDefenseReductionForLevelDifference(int levelDifference) {
        return getMobDefenseReductionForLevelDifference(null, levelDifference);
    }

    private double getMobDefenseReductionForLevelDifference(Store<EntityStore> store, int levelDifference) {
        if (!isMobDefenseScalingEnabled(store)) {
            return 0.0D;
        }

        int maxDifference = getMobScalingLevelDifferenceRange(store);
        double atNegativeMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.At_Negative_Max_Difference", 0.0D, store));
        double atPositiveMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.At_Positive_Max_Difference", 0.75D, store));
        double belowNegativeMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.Below_Negative_Max_Difference", 0.0D, store));
        double abovePositiveMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.Above_Positive_Max_Difference", 0.90D, store));

        if (maxDifference <= 0) {
            if (levelDifference > 0) {
                return abovePositiveMax;
            }
            if (levelDifference < 0) {
                return belowNegativeMax;
            }
            return atNegativeMax;
        }

        if (levelDifference < -maxDifference) {
            return belowNegativeMax;
        }
        if (levelDifference > maxDifference) {
            return abovePositiveMax;
        }

        double ratio = (levelDifference + maxDifference) / (double) (maxDifference * 2);
        return lerp(atNegativeMax, atPositiveMax, ratio);
    }

    private double clampReduction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private double clampNonNegativeMultiplier(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 1.0D;
        }
        return Math.max(0.0D, value);
    }

    private double lerp(double start, double end, double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        return start + ((end - start) * clamped);
    }

    private int getFixedLevel() {
        return getFixedLevel(null, null, null);
    }

    private int getFixedLevel(Store<EntityStore> store) {
        return getFixedLevel(store, null, null);
    }

    private int getFixedLevel(Store<EntityStore> store, Integer entityId, Vector3d mobPosition) {
        LevelRange fixedRange = getFixedLevelRange(store);
        return sampleLevel(fixedRange.min(), fixedRange.max(), entityId, mobPosition);
    }

    private int getTieredLevel(Store<EntityStore> store, Integer entityId, Vector3d mobPosition) {
        LevelRange tieredRange = getTieredLevelRange(store, entityId, mobPosition);
        return sampleLevel(tieredRange.min(), tieredRange.max(), entityId, mobPosition);
    }

    private LevelRange getFixedLevelRange(Store<EntityStore> store) {
        LevelRange configured = resolveConfiguredLevelRange("Mob_Leveling.Level_Source.Fixed_Level", store);
        if (configured != null) {
            return configured;
        }

        int fallback = clampToConfiguredRange(10, store);
        return new LevelRange(fallback, fallback);
    }

    private LevelRange getTierBaseLevelRange(Store<EntityStore> store) {
        LevelRange baseLevel = resolveConfiguredLevelRange("Mob_Leveling.Level_Source.Base_Level", store);
        if (baseLevel != null) {
            return baseLevel;
        }

        // Backward compatibility for existing TIERS configs.
        return getFixedLevelRange(store);
    }

    private LevelRange resolveConfiguredLevelRange(String basePath, Store<EntityStore> store) {
        if (basePath == null || basePath.isBlank()) {
            return null;
        }

        Object levelRaw = getMobLevelingValue(basePath + ".Level", null, store, null);
        Object minRaw = getMobLevelingValue(basePath + ".Min", null, store, null);
        Object maxRaw = getMobLevelingValue(basePath + ".Max", null, store, null);

        if (levelRaw == null && minRaw == null && maxRaw == null) {
            return null;
        }

        LevelRange parsedInlineRange = parseInlineFixedLevelRange(levelRaw);
        Integer parsedSingleLevel = parsePositiveInteger(levelRaw);
        Integer parsedMinLevel = parsePositiveInteger(minRaw);
        Integer parsedMaxLevel = parsePositiveInteger(maxRaw);

        int minLevel;
        int maxLevel;

        if (parsedMinLevel != null || parsedMaxLevel != null) {
            if (parsedMinLevel == null) {
                parsedMinLevel = parsedMaxLevel;
            }
            if (parsedMaxLevel == null) {
                parsedMaxLevel = parsedMinLevel;
            }
            minLevel = parsedMinLevel;
            maxLevel = parsedMaxLevel;
        } else if (parsedInlineRange != null) {
            minLevel = parsedInlineRange.min();
            maxLevel = parsedInlineRange.max();
        } else if (parsedSingleLevel != null) {
            minLevel = parsedSingleLevel;
            maxLevel = parsedSingleLevel;
        } else {
            return null;
        }

        minLevel = clampToConfiguredRange(minLevel, store);
        maxLevel = clampToConfiguredRange(maxLevel, store);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }

        return new LevelRange(minLevel, maxLevel);
    }

    private LevelRange getTieredLevelRange(Store<EntityStore> store, Integer entityId, Vector3d mobPosition) {
        LevelRange baseRange = getTierBaseLevelRange(store);
        int tierOffset = resolveTierLevelOffset(store, entityId, mobPosition);

        long rawMin = (long) baseRange.min() + (long) tierOffset;
        long rawMax = (long) baseRange.max() + (long) tierOffset;
        if (rawMin > rawMax) {
            long tmp = rawMin;
            rawMin = rawMax;
            rawMax = tmp;
        }

        // When Level_Range.Max is "ENDLESS" the tier window is fully uncapped — only
        // the configured minimum is respected.
        if (isEndlessLevelRange(store)) {
            int configuredMin = getConfigInt("Mob_Leveling.Level_Range.Min", 1, store);
            long adjustedMin = Math.max((long) configuredMin, rawMin);
            long adjustedMax = Math.max(adjustedMin, rawMax);
            return new LevelRange(
                    (int) Math.min(adjustedMin, Integer.MAX_VALUE),
                    (int) Math.min(adjustedMax, Integer.MAX_VALUE));
        }

        int configuredMin = getConfigInt("Mob_Leveling.Level_Range.Min", 1, store);
        int configuredMax = getConfigInt("Mob_Leveling.Level_Range.Max", 200, store);
        if (configuredMin > configuredMax) {
            int tmp = configuredMin;
            configuredMin = configuredMax;
            configuredMax = tmp;
        }

        long availableSpan = Math.max(0L, (long) configuredMax - (long) configuredMin);
        long desiredSpan = Math.max(0L, rawMax - rawMin);

        // Keep tier width whenever possible instead of collapsing to a single value at
        // bounds.
        if (desiredSpan >= availableSpan) {
            return new LevelRange(configuredMin, configuredMax);
        }

        long adjustedMin = rawMin;
        long adjustedMax = rawMax;

        if (adjustedMin < configuredMin) {
            long shift = configuredMin - adjustedMin;
            adjustedMin += shift;
            adjustedMax += shift;
        }
        if (adjustedMax > configuredMax) {
            long shift = adjustedMax - configuredMax;
            adjustedMin -= shift;
            adjustedMax -= shift;
        }

        adjustedMin = Math.max((long) configuredMin, adjustedMin);
        adjustedMax = Math.min((long) configuredMax, adjustedMax);
        if (adjustedMin > adjustedMax) {
            int clamped = clampToConfiguredRange(baseRange.max() + tierOffset, store);
            return new LevelRange(clamped, clamped);
        }

        return new LevelRange((int) adjustedMin, (int) adjustedMax);
    }

    private int resolveTierLevelOffset(Store<EntityStore> store, Integer entityId, Vector3d mobPosition) {
        TierCount tierCount = resolveTierCount(store);
        int totalTiers = tierCount.totalTiers();
        boolean endlessTiers = tierCount.endless();
        int levelsPerTier = Math.max(0, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 25, store));
        if ((!endlessTiers && totalTiers <= 1) || levelsPerTier <= 0) {
            return 0;
        }

        int resolvedTier = resolveTierIndex(store, entityId, mobPosition, totalTiers, levelsPerTier, endlessTiers);
        long tierOffset = (long) Math.max(0, resolvedTier - 1) * (long) levelsPerTier;
        return tierOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tierOffset;
    }

    private int resolveTierIndex(Store<EntityStore> store,
            Integer entityId,
            Vector3d mobPosition,
            int totalTiers,
            int levelsPerTier,
            boolean endlessTiers) {
        Integer lockedTier = resolveLockedTierForStore(store);
        if (lockedTier != null && lockedTier > 0) {
            return endlessTiers ? Math.max(1, lockedTier) : Math.max(1, Math.min(totalTiers, lockedTier));
        }

        if (!isTierPlayerAdaptationEnabled(store) || mobPosition == null) {
            if (endlessTiers) {
                return 1;
            }
            return sampleLevel(1, totalTiers, entityId, mobPosition);
        }

        LevelRange baseRange = getTierBaseLevelRange(store);
        int referencePlayerLevel = resolveTierReferencePlayerLevel(store, mobPosition);
        if (referencePlayerLevel <= 0 || referencePlayerLevel < baseRange.min()) {
            return 1;
        }

        int tierPromotionAllowance = getTierPromotionAllowance(store);
        TierPromotionAllowanceMode allowanceMode = getTierPromotionAllowanceMode(store);
        return applyTierPromotionAllowance(referencePlayerLevel, baseRange, totalTiers, levelsPerTier, 1,
                tierPromotionAllowance, allowanceMode, endlessTiers);
    }

    private Integer resolveLockedTierForStore(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        return tierLockByStoreKey.get(toStoreKey(store));
    }

    private int resolveTierReferencePlayerLevel(Store<EntityStore> store, Vector3d mobPosition) {
        if (mobPosition == null) {
            return -1;
        }

        if (isPartyPlayerSourceEnabled(store)) {
            double shareRange = getPartyXpShareRange(store);
            List<PlayerContext> nearbyPlayers = getPlayersWithinRadius(store, mobPosition, shareRange);
            if (!nearbyPlayers.isEmpty()) {
                PartyContext dominant = resolveDominantPartyContext(nearbyPlayers, store);
                if (dominant != null && !dominant.members().isEmpty()) {
                    return computeVirtualPartyLevel(dominant.members(), store);
                }
            }
        }

        return findNearestPlayerLevel(store, mobPosition);
    }

    private int applyTierPromotionAllowance(int playerLevel,
            LevelRange baseRange,
            int totalTiers,
            int levelsPerTier,
            int initialTier,
            int promotionAllowance,
            TierPromotionAllowanceMode allowanceMode,
            boolean endlessTiers) {
        int safePlayerLevel = Math.max(1, playerLevel);
        int resolvedTier = endlessTiers
                ? Math.max(1, initialTier)
                : Math.max(1, Math.min(totalTiers, initialTier));
        int allowance = Math.max(0, promotionAllowance);
        TierPromotionAllowanceMode mode = allowanceMode != null ? allowanceMode : TierPromotionAllowanceMode.BELOW;

        if (endlessTiers) {
            if (levelsPerTier <= 0) {
                return resolvedTier;
            }

            if (mode == TierPromotionAllowanceMode.ABOVE) {
                long numerator = (long) safePlayerLevel - (long) baseRange.max() - (long) allowance;
                int extra = (int) Math.floorDiv(numerator, levelsPerTier);
                return Math.max(resolvedTier, Math.max(1, extra + 2));
            }

            long numerator = (long) safePlayerLevel - (long) baseRange.min() + (long) allowance;
            int extra = (int) Math.floorDiv(numerator, levelsPerTier);
            return Math.max(resolvedTier, Math.max(1, extra + 1));
        }

        for (int tier = resolvedTier + 1; tier <= totalTiers; tier++) {
            LevelRange tierRange = getTierRange(baseRange, tier, levelsPerTier);
            int promotedThreshold;
            if (mode == TierPromotionAllowanceMode.ABOVE) {
                LevelRange previousTierRange = getTierRange(baseRange, tier - 1, levelsPerTier);
                promotedThreshold = Math.max(1, previousTierRange.max() + allowance);
            } else {
                promotedThreshold = Math.max(1, tierRange.min() - allowance);
            }

            if (safePlayerLevel >= promotedThreshold) {
                resolvedTier = tier;
            } else {
                break;
            }
        }

        return resolvedTier;
    }

    private TierCount resolveTierCount(Store<EntityStore> store) {
        Object raw = getMobLevelingValue("Mob_Leveling.Level_Source.Tiers.Total_Tiers", 1, store, null);
        if (raw instanceof String text) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);
            if ("ENDLESS".equals(normalized) || "INFINITE".equals(normalized)) {
                return new TierCount(1, true);
            }
        }

        Integer parsed = parseInteger(raw);
        if (parsed != null) {
            return new TierCount(Math.max(1, parsed), false);
        }
        return new TierCount(1, false);
    }

    private LevelRange getTierRange(LevelRange baseRange, int tier, int levelsPerTier) {
        int safeTier = Math.max(1, tier);
        long tierOffsetLong = (long) Math.max(0, safeTier - 1) * (long) Math.max(0, levelsPerTier);
        int tierOffset = tierOffsetLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tierOffsetLong;
        return new LevelRange(baseRange.min() + tierOffset, baseRange.max() + tierOffset);
    }

    private boolean isTierPlayerAdaptationEnabled(Store<EntityStore> store) {
        return getConfigBoolean("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Enabled", true, store);
    }

    private TierPromotionAllowanceMode getTierPromotionAllowanceMode(Store<EntityStore> store) {
        Object rawValue = getMobLevelingValue("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Allowance_Mode",
                "BELOW", store, null);
        String raw = rawValue != null ? rawValue.toString() : "BELOW";
        if (raw == null) {
            return TierPromotionAllowanceMode.BELOW;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ABOVE" -> TierPromotionAllowanceMode.ABOVE;
            default -> TierPromotionAllowanceMode.BELOW;
        };
    }

    private int getTierPromotionAllowance(Store<EntityStore> store) {
        Object newValue = getMobLevelingValue("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Range_Allowance",
                null, store, null);
        if (newValue != null) {
            Integer parsed = parseInteger(newValue);
            if (parsed != null) {
                return Math.max(0, parsed);
            }
        }

        // Backward compatibility with older configs.
        return Math.max(0,
                getConfigInt("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Below_Range_Allowance", 0, store));
    }

    private Integer parseInteger(Object raw) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof Number number) {
            return number.intValue();
        }

        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private int applyTierOffsetToConfiguredLevel(Store<EntityStore> store,
            Integer entityId,
            Vector3d mobPosition,
            int baseLevel) {
        if (baseLevel <= 0 || getLevelSourceMode(store) != LevelSourceMode.TIERS) {
            return baseLevel;
        }

        int tierOffset = resolveTierLevelOffset(store, entityId, mobPosition);
        return clampToConfiguredRange(baseLevel + tierOffset, store);
    }

    private LevelRange parseInlineFixedLevelRange(Object raw) {
        if (!(raw instanceof String text)) {
            return null;
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String[] parts = trimmed.split("-");
        if (parts.length != 2) {
            return null;
        }

        Integer min = parsePositiveInteger(parts[0]);
        Integer max = parsePositiveInteger(parts[1]);
        if (min == null || max == null) {
            return null;
        }

        int lower = Math.min(min, max);
        int upper = Math.max(min, max);
        return new LevelRange(lower, upper);
    }

    private Integer parsePositiveInteger(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : null;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.matches("\\d+")) {
                return null;
            }
            try {
                int value = Integer.parseInt(trimmed);
                return value > 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int clampToConfiguredRange(int level) {
        return clampToConfiguredRange(level, null);
    }

    private int clampToConfiguredRange(int level, Store<EntityStore> store) {
        int min = getConfigInt("Mob_Leveling.Level_Range.Min", 1, store);
        if (isEndlessLevelRange(store)) {
            return Math.max(min, level);
        }
        int max = getConfigInt("Mob_Leveling.Level_Range.Max", 200, store);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return Math.max(min, Math.min(max, level));
    }

    /**
     * Returns true when {@code Mob_Leveling.Level_Range.Max} is set to
     * {@code "ENDLESS"} for the given world/store context, meaning mob levels are
     * not capped by an upper bound.
     */
    private boolean isEndlessLevelRange(Store<EntityStore> store) {
        Object raw = getMobLevelingValue("Mob_Leveling.Level_Range.Max", null, store, null);
        return raw != null && "ENDLESS".equalsIgnoreCase(raw.toString().trim());
    }

    /** Inclusive mob level range. */
    public record LevelRange(int min, int max) {
    }

    private int getConfigInt(String path, int defaultValue) {
        return getConfigInt(path, defaultValue, null);
    }

    private int getConfigInt(String path, int defaultValue, Store<EntityStore> store) {
        Object raw = getMobLevelingValue(path, defaultValue, store, null);
        if (raw == null)
            return defaultValue;
        try {
            if (raw instanceof Number)
                return ((Number) raw).intValue();
            return Integer.parseInt(raw.toString().trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double getConfigDouble(String path, double defaultValue) {
        return getConfigDouble(path, defaultValue, null);
    }

    private double getConfigDouble(String path, double defaultValue, Store<EntityStore> store) {
        Object raw = getMobLevelingValue(path, defaultValue, store, null);
        if (raw == null)
            return defaultValue;
        try {
            if (raw instanceof Number)
                return ((Number) raw).doubleValue();
            return Double.parseDouble(raw.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    public boolean shouldShowMobLevelUi() {
        return getGlobalConfigBoolean("Mob_Leveling.UI.Show_Mob_Level", true);
    }

    public boolean shouldIncludeLevelInNameplate() {
        return getGlobalConfigBoolean("Mob_Leveling.UI.Show_Level_In_Name", true);
    }

    private int getGlobalConfigInt(String path, int defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw == null) {
            return defaultValue;
        }
        try {
            if (raw instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(raw.toString().trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean getGlobalConfigBoolean(String path, boolean defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return defaultValue;
    }

    private boolean getConfigBoolean(String path, boolean defaultValue) {
        return getConfigBoolean(path, defaultValue, null);
    }

    private boolean getConfigBoolean(String path, boolean defaultValue, Store<EntityStore> store) {
        Object raw = getMobLevelingValue(path, defaultValue, store, null);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return defaultValue;
    }

    private boolean hasMobLevelingPath(String path, Store<EntityStore> store, Object worldHint) {
        if (path == null || path.isBlank()) {
            return false;
        }

        Object override = getWorldOverrideValue(path, store, worldHint);
        if (override != null) {
            return true;
        }

        return configManager.hasPath(path);
    }

    private Object getMobLevelingValue(String path, Object defaultValue, Store<EntityStore> store, Object worldHint) {
        Object override = getWorldOverrideValue(path, store, worldHint);
        if (override != null) {
            return override;
        }
        return configManager.get(path, defaultValue, false);
    }

    private Object getWorldOverrideValue(String path, Store<EntityStore> store, Object worldHint) {
        if (path == null || !path.startsWith("Mob_Leveling.")) {
            return null;
        }

        Object rootRaw = worldsConfigManager.get("World_Overrides", null, false);
        if (!(rootRaw instanceof Map<?, ?> rootMap) || rootMap.isEmpty()) {
            return null;
        }

        String relativePath = path.substring("Mob_Leveling.".length());
        String worldId = resolveWorldId(store, worldHint);

        if (worldId != null && !worldId.isBlank()) {
            Object exact = resolveCaseInsensitive(rootMap, worldId);
            if (exact instanceof Map<?, ?> exactMap) {
                Object value = resolveNestedValue(exactMap, relativePath);
                if (value != null) {
                    return value;
                }
            }

            Map<?, ?> bestPatternMap = resolveBestWildcardWorldOverrideMap(rootMap, worldId);
            if (bestPatternMap != null) {
                Object value = resolveNestedValue(bestPatternMap, relativePath);
                if (value != null) {
                    return value;
                }
            }
        }

        Object globalNode = resolveCaseInsensitive(rootMap, "global");
        if (globalNode instanceof Map<?, ?> globalMap) {
            Object value = resolveNestedValue(globalMap, relativePath);
            if (value != null) {
                return value;
            }
        }

        Object defaultNode = resolveCaseInsensitive(rootMap, "default");
        if (defaultNode instanceof Map<?, ?> defaultMap) {
            Object value = resolveNestedValue(defaultMap, relativePath);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private Map<?, ?> resolveBestWildcardWorldOverrideMap(Map<?, ?> rootMap, String worldId) {
        if (rootMap == null || rootMap.isEmpty() || worldId == null || worldId.isBlank()) {
            return null;
        }

        String normalizedWorldId = normalizeWorldPattern(worldId);
        if (normalizedWorldId == null || normalizedWorldId.isBlank()) {
            return null;
        }

        Map<?, ?> bestPatternMap = null;
        int bestSpecificity = -1;
        int bestLength = -1;

        for (Map.Entry<?, ?> entry : rootMap.entrySet()) {
            Object keyObj = entry.getKey();
            Object valueObj = entry.getValue();
            if (!(keyObj instanceof String pattern) || !(valueObj instanceof Map<?, ?> mapValue)) {
                continue;
            }

            if (pattern.equalsIgnoreCase(worldId)) {
                continue;
            }

            String normalizedPattern = normalizeWorldPattern(pattern);
            if (normalizedPattern == null || normalizedPattern.isBlank()) {
                continue;
            }

            if (!matchesWildcard(normalizedWorldId, normalizedPattern)) {
                continue;
            }

            int specificity = wildcardSpecificity(normalizedPattern);
            int length = normalizedPattern.length();
            if (specificity > bestSpecificity || (specificity == bestSpecificity && length > bestLength)) {
                bestPatternMap = mapValue;
                bestSpecificity = specificity;
                bestLength = length;
            }
        }

        return bestPatternMap;
    }

    private String normalizeWorldPattern(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private int wildcardSpecificity(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        int stars = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                stars++;
            }
        }
        return Math.max(0, pattern.length() - stars);
    }

    private Object resolveNestedValue(Map<?, ?> source, String dottedPath) {
        if (source == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }

        String[] parts = dottedPath.split("\\.");
        Object current = source;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = resolveCaseInsensitive(map, part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object resolveCaseInsensitive(Map<?, ?> source, String key) {
        if (source == null || key == null) {
            return null;
        }

        Object direct = source.get(key);
        if (direct != null) {
            return direct;
        }

        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object entryKey = entry.getKey();
            if (!(entryKey instanceof String textKey)) {
                continue;
            }
            if (textKey.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // --------------------
    // External override API
    // --------------------

    /**
     * Register an area- or radius-based override. If worldId is null, applies to
     * all
     * worlds. Radius is in blocks; min/max can be equal for a flat level. Returns
     * false on bad input.
     */
    public boolean registerAreaLevelOverride(String id, String worldId,
            double centerX, double centerZ, double radius, int minLevel, int maxLevel) {
        if (id == null || id.isBlank() || radius <= 0.0D || minLevel <= 0 || maxLevel <= 0) {
            return false;
        }
        double r = radius <= 0.0D ? 1.0D : radius;
        AreaOverride override = new AreaOverride(
                id.trim(),
                worldId != null && !worldId.isBlank() ? worldId.trim() : null,
                centerX,
                centerZ,
                r * r,
                minLevel,
                maxLevel);
        areaOverrides.put(override.id, override);
        return true;
    }

    /** Register a world-wide override (min/max or flat if equal). */
    public boolean registerWorldLevelOverride(String id, String worldId, int minLevel, int maxLevel) {
        if (id == null || id.isBlank() || minLevel <= 0 || maxLevel <= 0) {
            return false;
        }
        double huge = Double.MAX_VALUE / 4.0D;
        return registerAreaLevelOverride(id, worldId, 0.0D, 0.0D, huge, minLevel, maxLevel);
    }

    /** Remove a previously registered area/world override by id. */
    public boolean removeAreaLevelOverride(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return areaOverrides.remove(id.trim()) != null;
    }

    /** Clear all area/world overrides. */
    public void clearAreaLevelOverrides() {
        areaOverrides.clear();
    }

    /** Set a fixed level override for a specific entity index. */
    public void setEntityLevelOverride(int entityIndex, int level) {
        if (entityIndex < 0 || level <= 0) {
            return;
        }
        entityLevelOverrides.put(entityIndex, level);
    }

    public void setEntityLevelOverride(Store<EntityStore> store, int entityIndex, int level) {
        if (store == null || entityIndex < 0 || level <= 0) {
            return;
        }
        entityLevelOverridesScoped.put(toEntityKey(store, entityIndex), level);
    }

    public Integer getEntityLevelOverride(Store<EntityStore> store, int entityIndex) {
        if (entityIndex < 0) {
            return null;
        }

        if (store != null) {
            Integer scoped = entityLevelOverridesScoped.get(toEntityKey(store, entityIndex));
            if (scoped != null && scoped > 0) {
                return scoped;
            }
        }

        Integer direct = entityLevelOverrides.get(entityIndex);
        if (direct != null && direct > 0) {
            return direct;
        }

        return null;
    }

    public boolean setEntityLevelOverrideIfChanged(Store<EntityStore> store, int entityIndex, int level) {
        if (store == null || entityIndex < 0 || level <= 0) {
            return false;
        }

        long entityKey = toEntityKey(store, entityIndex);
        Integer previous = entityLevelOverridesScoped.get(entityKey);
        if (previous != null && previous == level) {
            return false;
        }

        entityLevelOverridesScoped.put(entityKey, level);
        return true;
    }

    /** Remove a specific entity override. */
    public void clearEntityLevelOverride(int entityIndex) {
        entityLevelOverrides.remove(entityIndex);
        entityPartyOverrides.remove(entityIndex);
        cachedPlayerDiffs.remove(entityIndex);
        entityMaxHealthSnapshots.remove(entityIndex);
        clearMixedChoicesByEntityIndex(entityIndex);
    }

    public void clearEntityLevelOverride(Store<EntityStore> store, int entityIndex) {
        if (store == null || entityIndex < 0) {
            return;
        }
        long entityKey = toEntityKey(store, entityIndex);
        entityLevelOverridesScoped.remove(entityKey);
        mixedSourceChoiceByEntityKey.remove(entityKey);
        clearEntityLevelOverride(entityIndex);
    }

    /** Clear all per-entity overrides. */
    public void clearAllEntityLevelOverrides() {
        entityLevelOverrides.clear();
        entityLevelOverridesScoped.clear();
        mixedSourceChoiceByEntityKey.clear();
        entityPartyOverrides.clear();
        cachedPlayerDiffs.clear();
        entityMaxHealthSnapshots.clear();
        tierLockByStoreKey.clear();
        tierLockSourcePlayerByStoreKey.clear();
    }

    private long toEntityKey(Store<EntityStore> store, int entityId) {
        long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
        long entityPart = Integer.toUnsignedLong(entityId);
        return (storePart << 32) | entityPart;
    }

    private long toStoreKey(Store<EntityStore> store) {
        return store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
    }

    private record AreaOverride(String id, String worldId,
            double centerX, double centerZ, double radiusSq,
            int minLevel, int maxLevel) {
    }

    private record WorldMobOverride(Integer level,
            Double healthMultiplier,
            Double damageMultiplier,
            Double defenseReduction,
            MobOverrideLinearScaling healthScaling,
            MobOverrideLinearScaling damageScaling,
            MobOverrideDefenseScaling defenseScaling,
            List<String> augmentIds,
            MobOverrideAugmentModifiers augmentModifiers) {
    }

    private record MobOverrideAugmentModifiers(double healthMultiplier,
            double damageMultiplier) {
    }

    private record MobOverrideLinearScaling(boolean enabled,
            Double baseMultiplier,
            Double perLevel) {
    }

    private record MobOverrideDefenseScaling(boolean enabled,
            Double atNegativeMaxDifference,
            Double atPositiveMaxDifference,
            Double belowNegativeMaxDifference,
            Double abovePositiveMaxDifference) {
    }

}
