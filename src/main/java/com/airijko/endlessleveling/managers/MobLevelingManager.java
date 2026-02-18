package com.airijko.endlessleveling.managers;

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
import java.util.HashSet;
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
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final ConfigManager configManager;
    private final PlayerDataManager playerDataManager;
    private static final Field HP_MAX_FIELD;
    private static volatile boolean loggedMaxWriteFailure;

    private enum LevelSourceMode {
        PLAYER,
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
        if (applied.contains(idx))
            return false;

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

        EntityStatMap statMap = resolveComponent(ref, store, commandBuffer, EntityStatMap.getComponentType());
        if (statMap == null)
            return false;

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null)
            return false;

        try {
            double mult = getMobHealthMultiplierForLevel(mobLevel);
            float oldMax = hp.getMax();
            float newMax = (float) Math.max(1.0, oldMax * mult);
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
        return getLevelSourceMode() == LevelSourceMode.PLAYER;
    }

    /** True when mob levels come from distance-based scaling. */
    public boolean isDistanceMode() {
        return getLevelSourceMode() == LevelSourceMode.DISTANCE;
    }

    /** True when mob levels are fixed. */
    public boolean isFixedMode() {
        return getLevelSourceMode() == LevelSourceMode.FIXED;
    }

    /**
     * Returns a single representative mob level for HUD display when the mode is
     * DISTANCE or FIXED. Returns null for player-based mode.
     */
    public Integer getHudLevelForPlayer(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid() || !isMobLevelingEnabled()) {
            return null;
        }

        LevelSourceMode mode = getLevelSourceMode();
        return switch (mode) {
            case DISTANCE -> {
                Ref<EntityStore> ref = playerRef.getReference();
                Store<EntityStore> store = ref != null ? ref.getStore() : null;
                Vector3d pos = getWorldPosition(ref, null);
                if (store == null || pos == null) {
                    yield null;
                }
                yield clampToConfiguredRange(resolveMobLevel(store, pos, null));
            }
            case FIXED -> clampToConfiguredRange(getFixedLevel());
            case PLAYER -> null;
        };
    }

    /**
     * Expected mob level range for the provided player level in player-based mode.
     */
    public LevelRange getPlayerBasedLevelRange(int playerLevel) {
        int level = Math.max(1, playerLevel);
        int offset = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0);
        int minDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Min_Difference", -3);
        int maxDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Max_Difference", 3);
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

        int nearestPlayerLevel = findNearestPlayerLevel(store, mobPos);
        if (nearestPlayerLevel <= 0)
            return getFixedLevel();

        int offset = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0);
        int minDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Min_Difference", -3);
        int maxDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Max_Difference", 3);
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
        Object raw = configManager.get("Mob_Leveling.Blacklist_Mob_Types", null, false);
        if (raw == null)
            return false;

        if (raw instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry == null)
                    continue;
                if (mobType.equalsIgnoreCase(entry.toString())) {
                    return true;
                }
            }
            return false;
        }

        String single = raw.toString();
        return mobType.equalsIgnoreCase(single);
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

    private int getFixedLevel() {
        return getConfigInt("Mob_Leveling.Level_Source.Fixed_Level.Level", 10);
    }

    /**
     * Expected mob level range near the provided player for HUD display. Uses the
     * configured Level_Source mode:
     * <ul>
     * <li>PLAYER: range derived from the player's level and configured min/max
     * diff.</li>
     * <li>DISTANCE: small band around the computed level at the player's current
     * position.</li>
     * <li>FIXED: flat level.</li>
     * </ul>
     */
    public LevelRange getHudLevelRangeForPlayer(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid() || !isMobLevelingEnabled()) {
            return null;
        }

        LevelSourceMode mode = getLevelSourceMode();
        return switch (mode) {
            case PLAYER -> {
                int playerLevel = getPlayerLevel(playerRef.getUuid());
                yield getPlayerBasedLevelRange(playerLevel);
            }
            case DISTANCE -> {
                Ref<EntityStore> ref = playerRef.getReference();
                Store<EntityStore> store = ref != null ? ref.getStore() : null;
                Vector3d pos = getWorldPosition(ref, null);
                if (store == null || pos == null) {
                    yield null;
                }

                int current = clampToConfiguredRange(resolveMobLevel(store, pos, null));
                int minConfig = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Min_Level", 1);
                int maxConfig = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Max_Level", 200);
                if (minConfig > maxConfig) {
                    int tmp = minConfig;
                    minConfig = maxConfig;
                    maxConfig = tmp;
                }

                // Show a narrow band around the local computed level so the HUD reflects the
                // nearby region instead of the whole world range.
                int window = 2; // +/- 2 levels around the computed value
                int low = Math.max(minConfig, current - window);
                int high = Math.min(maxConfig, current + window);
                yield new LevelRange(low, Math.max(low, high));
            }
            case FIXED -> {
                int fixed = clampToConfiguredRange(getFixedLevel());
                yield new LevelRange(fixed, fixed);
            }
        };
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
    }

    /** Clear all per-entity overrides. */
    public void clearAllEntityLevelOverrides() {
        entityLevelOverrides.clear();
    }

    private record AreaOverride(String id, String worldId,
            double centerX, double centerZ, double radiusSq,
            int minLevel, int maxLevel) {
    }
}
