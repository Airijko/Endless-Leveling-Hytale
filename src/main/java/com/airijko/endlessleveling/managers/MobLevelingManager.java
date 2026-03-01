package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
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

    private final Set<Integer> applied = new HashSet<>();
    private final Map<Integer, Integer> cachedPlayerDiffs = new ConcurrentHashMap<>();
    private final Map<Long, Integer> cachedPosDiffs = new ConcurrentHashMap<>();
    private final Map<String, AreaOverride> areaOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> entityLevelOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> entityPartyOverrides = new ConcurrentHashMap<>();
    private final Map<Integer, Float> entityBaseHpMax = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> entityAppliedLevel = new ConcurrentHashMap<>();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final ConfigManager configManager;
    private final PlayerDataManager playerDataManager;
    private static final Field HP_MAX_FIELD;
    private static volatile boolean loggedMaxWriteFailure;

    private enum LevelSourceMode {
        PLAYER,
        MIXED,
        DISTANCE,
        FIXED
    }

    static {
        Field maxField = null;
        try {
            maxField = EntityStatValue.class.getDeclaredField("max");
            maxField.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException ex) {
            LOGGER.atSevere().log("MobLeveling: unable to access EntityStatValue.max via reflection: %s",
                    ex.toString());
        }
        HP_MAX_FIELD = maxField;
    }

    public MobLevelingManager(PluginFilesManager filesManager, PlayerDataManager playerDataManager) {
        this.configManager = new ConfigManager(filesManager.getLevelingFile(), false);
        this.playerDataManager = playerDataManager;
    }

    /** Reload mob leveling config and clear cached diffs. */
    public void reloadConfig() {
        configManager.load();
        cachedPlayerDiffs.clear();
        cachedPosDiffs.clear();
    }

    /**
     * Attempts to apply mob-leveling (health scaling) to the entity referenced by
     * {@code ref}.
     * Returns true if the entity was modified and marked as applied.
     */
    public boolean applyLeveling(Ref<EntityStore> ref, Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null)
            return false;
        if (!isMobLevelingEnabled() || !isMobHealthScalingEnabled())
            return false;
        if (store == null && commandBuffer == null)
            return false;

        int idx = ref.getIndex();

        // skip players
        PlayerRef playerRef = resolveComponent(ref, store, commandBuffer, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid())
            return false;

        // check blacklist
        if (isEntityBlacklisted(ref, store, commandBuffer))
            return false;

        // If passive mob leveling is disabled, skip non-NPCs
        if (!allowPassiveMobLeveling()) {
            Object npcComp = resolveComponent(ref, store, commandBuffer, NPCEntity.getComponentType());
            if (npcComp == null)
                return false;
        }

        int mobLevel = resolveMobLevel(ref, commandBuffer);
        int lastAppliedLevel = entityAppliedLevel.getOrDefault(idx, Integer.MIN_VALUE);
        boolean alreadyApplied = applied.contains(idx);
        if (alreadyApplied && lastAppliedLevel == mobLevel)
            return false;

        EntityStatMap statMap = resolveComponent(ref, store, commandBuffer, EntityStatMap.getComponentType());
        if (statMap == null)
            return false;

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null)
            return false;

        try {
            double mult = getMobHealthMultiplierForLevel(mobLevel);
            float oldMax = hp.getMax();
            if (!alreadyApplied) {
                entityBaseHpMax.put(idx, oldMax);
            }
            float baseMax = entityBaseHpMax.getOrDefault(idx, oldMax);
            if (baseMax <= 0.0f) {
                baseMax = oldMax;
                entityBaseHpMax.put(idx, baseMax);
            }

            float newMax = (float) Math.max(1.0, baseMax * mult);
            if (!setHpMax(hp, newMax))
                return false;

            float cur = hp.get();
            float newCur;
            if (oldMax > 0.0f) {
                newCur = (cur / oldMax) * newMax;
            } else {
                newCur = Math.min(newMax, cur * (float) mult);
            }
            newCur = Math.max(0.01f, Math.min(newMax, newCur));
            statMap.setStatValue(DefaultEntityStatTypes.getHealth(), newCur);
            applied.add(idx);
            entityAppliedLevel.put(idx, mobLevel);
            return true;
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobLeveling: failed to scale entity %d: %s", idx, t.toString());
            return false;
        }
    }

    /**
     * Back-compat shim for older callers that did not pass Store.
     */
    public boolean applyLeveling(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        return applyLeveling(ref, store, commandBuffer);
    }

    public void forgetEntity(int entityIndex) {
        applied.remove(entityIndex);
        entityLevelOverrides.remove(entityIndex);
        entityPartyOverrides.remove(entityIndex);
        cachedPlayerDiffs.remove(entityIndex);
        entityBaseHpMax.remove(entityIndex);
        entityAppliedLevel.remove(entityIndex);
    }

    private boolean setHpMax(EntityStatValue hp, float newMax) {
        if (HP_MAX_FIELD == null)
            return false;
        try {
            HP_MAX_FIELD.setFloat(hp, newMax);
            return true;
        } catch (IllegalAccessException ex) {
            if (!loggedMaxWriteFailure) {
                loggedMaxWriteFailure = true;
                LOGGER.atSevere().log("MobLeveling: unable to mutate EntityStatValue.max: %s", ex.toString());
            }
            return false;
        }
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
        return resolveMobLevel(store, position, entityId);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition) {
        return resolveMobLevel(store, mobPosition, null);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition, Integer entityId) {
        Integer override = resolveExternalOverride(store, mobPosition, entityId);
        if (override != null && override > 0) {
            return override;
        }

        LevelSourceMode mode = getLevelSourceMode();
        int level;
        try {
            level = switch (mode) {
                case PLAYER -> resolvePlayerBasedLevel(store, mobPosition, entityId);
                case MIXED -> resolveMixedLevel(store, mobPosition, entityId);
                case DISTANCE -> resolveDistanceLevel(mobPosition);
                case FIXED -> getFixedLevel();
            };
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobLeveling: failed to resolve level via mode %s: %s", mode, t.toString());
            level = getFixedLevel();
        }
        return clampToConfiguredRange(level);
    }

    /** True when mob levels are derived from nearby player levels. */
    public boolean isPlayerBasedMode() {
        LevelSourceMode mode = getLevelSourceMode();
        return mode == LevelSourceMode.PLAYER || mode == LevelSourceMode.MIXED;
    }

    /**
     * Expected mob level range for the provided player level in player-based mode.
     */
    public LevelRange getPlayerBasedLevelRange(int playerLevel) {
        int level = Math.max(1, playerLevel);
        int offset = getPlayerBasedOffset();
        int minDiff = getPlayerBasedMinDifference();
        int maxDiff = getPlayerBasedMaxDifference();
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int min = clampToConfiguredRange(level + minDiff + offset);
        int max = clampToConfiguredRange(level + maxDiff + offset);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return new LevelRange(min, max);
    }

    private Integer resolveExternalOverride(Store<EntityStore> store, Vector3d mobPosition, Integer entityId) {
        if (entityId != null) {
            Integer direct = entityLevelOverrides.get(entityId);
            if (direct != null) {
                return Math.max(1, direct);
            }
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
        Object modeObj = configManager.get("Mob_Leveling.Level_Source.Mode", "FIXED", false);
        if (modeObj == null)
            return LevelSourceMode.FIXED;
        String normalized = modeObj.toString().trim().toUpperCase();
        if (normalized.isEmpty())
            return LevelSourceMode.FIXED;
        try {
            return LevelSourceMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return LevelSourceMode.FIXED;
        }
    }

    private int resolveDistanceLevel(Vector3d position) {
        if (position == null)
            return getFixedLevel();

        double x = position.getX();
        double z = position.getZ();
        double distance = Math.sqrt((x * x) + (z * z));

        double blocksPerLevel = Math.max(1.0,
                getConfigDouble("Mob_Leveling.Level_Source.Distance_Level.Blocks_Per_Level", 100.0));
        int startLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Start_Level", 1);
        int minLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Min_Level", 1);
        int maxLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Max_Level", 200);

        int computed = startLevel + (int) Math.floor(distance / blocksPerLevel);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }
        return Math.max(minLevel, Math.min(maxLevel, computed));
    }

    private int resolvePlayerBasedLevel(Store<EntityStore> store, Vector3d mobPos, Integer entityId) {
        if (mobPos == null)
            return getFixedLevel();

        int resolved = resolvePlayerBasedLevelWithoutFallback(store, mobPos, entityId, true);
        if (resolved > 0) {
            if (entityId != null && shouldLockPlayerLevelOnSpawn()) {
                setEntityLevelOverride(entityId, resolved);
            }
            return resolved;
        }
        return fallbackPlayerSourceLevel(mobPos);
    }

    private int resolvePlayerBasedLevelWithoutFallback(Store<EntityStore> store, Vector3d mobPos, Integer entityId,
            boolean allowPartyLock) {
        if (mobPos == null)
            return -1;

        if (isPartyPlayerSourceEnabled()) {
            int partyResolved = resolvePlayerBasedLevelWithPartySystem(store, mobPos, entityId, allowPartyLock);
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

        int offset = getPlayerBasedOffset();
        int minDiff = getPlayerBasedMinDifference();
        int maxDiff = getPlayerBasedMaxDifference();
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
            return getFixedLevel();
        }

        int distanceLevel = resolveDistanceLevel(mobPos);
        int playerLevel = resolvePlayerBasedLevelWithoutFallback(store, mobPos, entityId, false);
        if (playerLevel <= 0) {
            return distanceLevel;
        }

        int playerLowerBound = resolvePlayerLowerBoundForMixed(store, mobPos);
        if (playerLowerBound > 0 && distanceLevel < playerLowerBound) {
            int resolved = playerLevel;
            if (entityId != null && shouldLockPlayerLevelOnSpawn()) {
                setEntityLevelOverride(entityId, resolved);
            }
            return resolved;
        }

        double playerWeight = getMixedPlayerWeight();
        double distanceWeight = 1.0D - playerWeight;
        double blended = (playerLevel * playerWeight) + (distanceLevel * distanceWeight);
        int resolved = (int) Math.round(blended);
        if (entityId != null && shouldLockPlayerLevelOnSpawn()) {
            setEntityLevelOverride(entityId, resolved);
        }
        return resolved;
    }

    private int resolvePlayerLowerBoundForMixed(Store<EntityStore> store, Vector3d mobPos) {
        if (mobPos == null) {
            return -1;
        }

        int minDiff = getPlayerBasedMinDifference();
        int maxDiff = getPlayerBasedMaxDifference();
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        if (isPartyPlayerSourceEnabled()) {
            double radius = Math.max(0.0D, getPlayerPartyInfluenceRadius());
            List<PlayerContext> nearbyPlayers = getPlayersWithinRadius(store, mobPos, radius);
            if (nearbyPlayers.isEmpty()) {
                return -1;
            }
            PartyContext dominant = resolveDominantPartyContext(nearbyPlayers);
            if (dominant == null || dominant.members().isEmpty()) {
                return -1;
            }
            int vpl = computeVirtualPartyLevel(dominant.members());
            int floor = vpl + getPlayerBasedOffset() + minDiff;
            return clampToConfiguredRange(floor);
        }

        int nearestPlayerLevel = findNearestPlayerLevel(store, mobPos);
        if (nearestPlayerLevel <= 0) {
            return -1;
        }
        int floor = nearestPlayerLevel + minDiff;
        return clampToConfiguredRange(floor);
    }

    private int resolvePlayerBasedLevelWithPartySystem(Store<EntityStore> store, Vector3d mobPos, Integer entityId,
            boolean applyLock) {
        double radius = Math.max(0.0D, getPlayerPartyInfluenceRadius());
        List<PlayerContext> nearbyPlayers = getPlayersWithinRadius(store, mobPos, radius);
        if (nearbyPlayers.isEmpty()) {
            return -1;
        }

        PartyContext dominant = resolveDominantPartyContext(nearbyPlayers);
        if (dominant == null || dominant.members().isEmpty()) {
            return -1;
        }

        int vpl = computeVirtualPartyLevel(dominant.members());
        int offset = getPlayerBasedOffset();
        int minDiff = getPlayerBasedMinDifference();
        int maxDiff = getPlayerBasedMaxDifference();
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }

        int minLevel = clampToConfiguredRange(vpl + offset + minDiff);
        int maxLevel = clampToConfiguredRange(vpl + offset + maxDiff);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }

        int resolvedLevel = sampleLevel(minLevel, maxLevel, entityId, mobPos);
        if (applyLock && entityId != null) {
            setEntityLevelOverride(entityId, resolvedLevel);
            if (dominant.partyId() != null) {
                entityPartyOverrides.put(entityId, dominant.partyId());
            }
        }
        return resolvedLevel;
    }

    private int fallbackPlayerSourceLevel(Vector3d mobPos) {
        if (mobPos != null) {
            return resolveDistanceLevel(mobPos);
        }
        return getFixedLevel();
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
            if (mobStore != null && playerStore != null && mobStore != playerStore) {
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

    private PartyContext resolveDominantPartyContext(List<PlayerContext> nearbyPlayers) {
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

        DominantPartyMode mode = getDominantPartyResolutionMode();
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
            case CLOSEST_MEMBER -> {
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
            case HIGHEST_AVERAGE -> {
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

    private int computeVirtualPartyLevel(List<PlayerContext> members) {
        if (members == null || members.isEmpty()) {
            return 1;
        }

        PartyLevelCalculation calculation = getPartyLevelCalculationMode();
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
        return getPlayerBasedBoolean("Party_System.Enabled", false);
    }

    private double getPlayerPartyInfluenceRadius() {
        return Math.max(0.0D, getPlayerBasedDouble("Party_System.Influence_Radius", 25.0D));
    }

    private PartyLevelCalculation getPartyLevelCalculationMode() {
        String raw = getPlayerBasedString("Party_System.Level_Calculation", "AVERAGE");
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
        String raw = getPlayerBasedString("Party_System.Dominant_Party_Resolution.Mode", "MOST_MEMBERS");
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
        return getPlayerBasedInt("Offset", 0);
    }

    private int getPlayerBasedMinDifference() {
        return getPlayerBasedInt("Min_Difference", -3);
    }

    private int getPlayerBasedMaxDifference() {
        return getPlayerBasedInt("Max_Difference", 3);
    }

    private String getPlayerBasedString(String suffix, String defaultValue) {
        String primary = "Mob_Leveling.Level_Source.Player_Based." + suffix;
        String fallback = "Mob_Leveling.Player_Based." + suffix;
        if (configManager.hasPath(primary)) {
            Object raw = configManager.get(primary, defaultValue, false);
            return raw != null ? raw.toString() : defaultValue;
        }
        Object raw = configManager.get(fallback, defaultValue, false);
        return raw != null ? raw.toString() : defaultValue;
    }

    private int getPlayerBasedInt(String suffix, int defaultValue) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double getPlayerBasedDouble(String suffix, double defaultValue) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private boolean getPlayerBasedBoolean(String suffix, boolean defaultValue) {
        String raw = getPlayerBasedString(suffix, String.valueOf(defaultValue));
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private boolean shouldLockPlayerLevelOnSpawn() {
        return true;
    }

    private double getMixedPlayerWeight() {
        return clampWeight(getMixedDouble("Player_Weight", 0.5D));
    }

    private String getMixedString(String suffix, String defaultValue) {
        String primary = "Mob_Leveling.Level_Source.Mixed." + suffix;
        String fallback = "Mob_Leveling.Mixed." + suffix;
        if (configManager.hasPath(primary)) {
            Object raw = configManager.get(primary, defaultValue, false);
            return raw != null ? raw.toString() : defaultValue;
        }
        Object raw = configManager.get(fallback, defaultValue, false);
        return raw != null ? raw.toString() : defaultValue;
    }

    private double getMixedDouble(String suffix, double defaultValue) {
        String raw = getMixedString(suffix, String.valueOf(defaultValue));
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

    private record PlayerContext(UUID playerId, int level, double distanceSq, UUID partyId) {
    }

    private record PartyContext(UUID partyId, List<PlayerContext> members) {
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
        Universe universe = Universe.get();
        if (universe == null)
            return -1;
        double closestDistSq = Double.MAX_VALUE;
        int bestLevel = -1;

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid())
                continue;
            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null)
                continue;

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            if (mobStore != null && playerStore != null && mobStore != playerStore)
                continue;

            Vector3d playerPos = getWorldPosition(playerEntityRef, null);
            if (playerPos == null)
                continue;

            double distSq = horizontalDistanceSquared(mobPos, playerPos);
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

    private String resolveWorldId(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        try {
            Method getWorld = store.getClass().getMethod("getWorld");
            Object world = getWorld.invoke(store);
            if (world != null) {
                try {
                    Method getName = world.getClass().getMethod("getName");
                    Object name = getName.invoke(world);
                    if (name != null) {
                        return name.toString();
                    }
                } catch (Exception ignored) {
                }
                return world.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
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

    /** Whether mob leveling is enabled (Mob_Leveling.Enabled) */
    public boolean isMobLevelingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Enabled", Boolean.TRUE, false);
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
        Object raw = configManager.get("Mob_Leveling.allow_passive_mob_leveling", Boolean.FALSE, false);
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

        Object raw = configManager.get("Mob_Leveling.Blacklist_Mob_Types", null, false);
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
        double base = getConfigDouble("Mob_Leveling.Scaling.Health.Base_Multiplier", 1.0);
        double per = getConfigDouble("Mob_Leveling.Scaling.Health.Per_Level", 0.05);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    public double getMobDamageMultiplierForLevel(int level) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Damage.Base_Multiplier", 1.0);
        double per = getConfigDouble("Mob_Leveling.Scaling.Damage.Per_Level", 0.03);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    public boolean isMobDamageScalingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Scaling.Damage.Enabled", Boolean.FALSE, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    public boolean isMobHealthScalingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Scaling.Health.Enabled", Boolean.FALSE, false);
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
        Object raw = configManager.get("Mob_Leveling.Scaling.Defense.Enabled", Boolean.FALSE, false);
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
        int safeMobLevel = Math.max(1, mobLevel);
        int safePlayerLevel = Math.max(1, playerLevel);
        int levelDifference = safeMobLevel - safePlayerLevel;
        return getMobDefenseReductionForLevelDifference(levelDifference);
    }

    /**
     * Resolve defense reduction from relative level difference (mob - player).
     */
    public double getMobDefenseReductionForLevelDifference(int levelDifference) {
        if (!isMobDefenseScalingEnabled()) {
            return 0.0D;
        }

        int maxDifference = Math.max(0, getConfigInt("Mob_Leveling.Experience.XP_Level_Range.Max_Difference", 10));
        double atNegativeMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.At_Negative_Max_Difference", 0.0D));
        double atPositiveMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.At_Positive_Max_Difference", 0.75D));
        double belowNegativeMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.Below_Negative_Max_Difference", 0.0D));
        double abovePositiveMax = clampReduction(
                getConfigDouble("Mob_Leveling.Scaling.Defense.Above_Positive_Max_Difference", 0.90D));

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

    private double lerp(double start, double end, double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        return start + ((end - start) * clamped);
    }

    private int getFixedLevel() {
        return getConfigInt("Mob_Leveling.Level_Source.Fixed_Level.Level", 10);
    }

    private int clampToConfiguredRange(int level) {
        int min = getConfigInt("Mob_Leveling.Level_Range.Min", 1);
        int max = getConfigInt("Mob_Leveling.Level_Range.Max", 200);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return Math.max(min, Math.min(max, level));
    }

    /** Inclusive mob level range. */
    public record LevelRange(int min, int max) {
    }

    private int getConfigInt(String path, int defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
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
        Object raw = configManager.get(path, defaultValue, false);
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
        return getConfigBoolean("Mob_Leveling.UI.Show_Mob_Level", true);
    }

    public boolean shouldIncludeLevelInNameplate() {
        return getConfigBoolean("Mob_Leveling.UI.Show_Level_In_Name", true);
    }

    private boolean getConfigBoolean(String path, boolean defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return defaultValue;
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

    /** Remove a specific entity override. */
    public void clearEntityLevelOverride(int entityIndex) {
        entityLevelOverrides.remove(entityIndex);
        entityPartyOverrides.remove(entityIndex);
        cachedPlayerDiffs.remove(entityIndex);
    }

    /** Clear all per-entity overrides. */
    public void clearAllEntityLevelOverrides() {
        entityLevelOverrides.clear();
        entityPartyOverrides.clear();
        cachedPlayerDiffs.clear();
    }

    private record AreaOverride(String id, String worldId,
            double centerX, double centerZ, double radiusSq,
            int minLevel, int maxLevel) {
    }
}
