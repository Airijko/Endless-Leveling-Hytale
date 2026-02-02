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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Encapsulates mob leveling logic previously in `MobLevelingSystem`.
 */
public class MobLevelingManager {

    private final Set<Integer> applied = new HashSet<>();
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
        String mobType = resolveMobType(ref, store, commandBuffer);
        if (mobType != null && isMobTypeBlacklisted(mobType))
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
        return resolveMobLevel(store, position);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition) {
        LevelSourceMode mode = getLevelSourceMode();
        int level;
        try {
            level = switch (mode) {
                case PLAYER -> resolvePlayerBasedLevel(store, mobPosition);
                case DISTANCE -> resolveDistanceLevel(mobPosition);
                case FIXED -> getFixedLevel();
            };
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobLeveling: failed to resolve level via mode %s: %s", mode, t.toString());
            level = getFixedLevel();
        }
        return clampToConfiguredRange(level);
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
        int maxLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Max_Level", 100);

        int computed = startLevel + (int) Math.floor(distance / blocksPerLevel);
        if (minLevel > maxLevel) {
            int tmp = minLevel;
            minLevel = maxLevel;
            maxLevel = tmp;
        }
        return Math.max(minLevel, Math.min(maxLevel, computed));
    }

    private int resolvePlayerBasedLevel(Store<EntityStore> store, Vector3d mobPos) {
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
        int target = nearestPlayerLevel + offset;
        int clamped = Math.max(minAllowed, Math.min(maxAllowed, target));
        return clamped;
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

    private int clampToConfiguredRange(int level) {
        int min = getConfigInt("Mob_Leveling.Level_Range.Min", 1);
        int max = getConfigInt("Mob_Leveling.Level_Range.Max", 100);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return Math.max(min, Math.min(max, level));
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
}
