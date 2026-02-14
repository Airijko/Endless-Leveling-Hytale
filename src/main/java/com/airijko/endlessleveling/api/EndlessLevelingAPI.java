package com.airijko.endlessleveling.api;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight public API surface for other mods to query EndlessLeveling state
 * without touching internal manager classes.
 */
public final class EndlessLevelingAPI {

    private static final EndlessLevelingAPI INSTANCE = new EndlessLevelingAPI();

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final LevelingManager levelingManager;
    private final RaceManager raceManager;
    private final PlayerAttributeManager attributeManager;
    private final MobLevelingManager mobLevelingManager;
    private final PartyManager partyManager;

    private EndlessLevelingAPI() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.skillManager = plugin != null ? plugin.getSkillManager() : null;
        this.levelingManager = plugin != null ? plugin.getLevelingManager() : null;
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.attributeManager = plugin != null ? plugin.getPlayerAttributeManager() : null;
        this.mobLevelingManager = plugin != null ? plugin.getMobLevelingManager() : null;
        this.partyManager = plugin != null ? plugin.getPartyManager() : null;
    }

    /** Global access point. */
    public static EndlessLevelingAPI get() {
        return INSTANCE;
    }

    /** Snapshot basic player info; returns null if player data is not loaded. */
    public PlayerSnapshot getPlayerSnapshot(UUID uuid) {
        PlayerData data = getData(uuid);
        if (data == null) {
            return null;
        }

        Map<SkillAttributeType, Integer> levels = new EnumMap<>(SkillAttributeType.class);
        for (SkillAttributeType type : SkillAttributeType.values()) {
            levels.put(type, data.getPlayerSkillAttributeLevel(type));
        }

        double xpMultiplier = skillManager != null ? skillManager.getXpGainMultiplier(data) : 1.0D;

        return new PlayerSnapshot(
                data.getUuid(),
                data.getPlayerName(),
                data.getLevel(),
                data.getXp(),
                data.getSkillPoints(),
                data.getRaceId(),
                data.getPrimaryClassId(),
                data.getSecondaryClassId(),
                Collections.unmodifiableMap(levels),
                xpMultiplier);
    }

    /** Raw skill attribute level from EndlessLeveling (0 if missing). */
    public int getSkillAttributeLevel(UUID uuid, SkillAttributeType type) {
        PlayerData data = getData(uuid);
        if (data == null || type == null) {
            return 0;
        }
        return data.getPlayerSkillAttributeLevel(type);
    }

    /**
     * Additive bonus value contributed by the player's skill levels (and innate
     * race bonuses) for the given attribute. Returns 0 when unavailable.
     */
    public double getSkillAttributeBonus(UUID uuid, SkillAttributeType type) {
        PlayerData data = getData(uuid);
        if (data == null || skillManager == null || type == null) {
            return 0.0D;
        }
        return skillManager.calculateSkillAttributeBonus(data, type, -1);
    }

    /**
     * Race base value + skill bonus for the requested attribute (ignores runtime
     * gear/buffs). Useful for UI and external scaling.
     */
    public double getCombinedAttribute(UUID uuid, SkillAttributeType type, double fallback) {
        PlayerData data = getData(uuid);
        if (data == null || attributeManager == null || type == null) {
            return fallback;
        }
        double skillBonus = getSkillAttributeBonus(uuid, type);
        return attributeManager.combineAttribute(data, type, skillBonus, fallback);
    }

    /**
     * Returns an attribute breakdown with an optional external additive bonus
     * (e.g., other mods' health) applied on top of EndlessLeveling race + skill
     * values. Use this to keep third-party stats in sync.
     */
    public AttributeBreakdown getAttributeBreakdown(UUID uuid, SkillAttributeType type,
            double externalBonus, double fallback) {
        PlayerData data = getData(uuid);
        if (data == null || type == null) {
            return new AttributeBreakdown(fallback, 0.0D, externalBonus, fallback + externalBonus);
        }

        double raceBase = raceManager != null ? raceManager.getAttribute(data, type, fallback) : fallback;
        double skillBonus = getSkillAttributeBonus(uuid, type);
        double total = raceBase + skillBonus + externalBonus;
        return new AttributeBreakdown(raceBase, skillBonus, externalBonus, total);
    }

    /** Convenience overload with no external bonus. */
    public AttributeBreakdown getAttributeBreakdown(UUID uuid, SkillAttributeType type, double fallback) {
        return getAttributeBreakdown(uuid, type, 0.0D, fallback);
    }

    /** Current player level (0 if missing). */
    public int getPlayerLevel(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getLevel() : 0;
    }

    /** Current XP (0 if missing). */
    public double getPlayerXp(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getXp() : 0.0D;
    }

    /** Maximum configured level cap. */
    public int getLevelCap() {
        return levelingManager != null ? levelingManager.getLevelCap() : 0;
    }

    /**
     * Level needed XP; returns POSITIVE_INFINITY if at/above cap or unavailable.
     */
    public double getXpForNextLevel(int level) {
        return levelingManager != null ? levelingManager.getXpForNextLevel(level) : Double.POSITIVE_INFINITY;
    }

    public String getRaceId(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getRaceId() : null;
    }

    public String getPrimaryClassId(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getPrimaryClassId() : null;
    }

    public String getSecondaryClassId(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getSecondaryClassId() : null;
    }

    /**
     * Underlying per-point config value for a skill attribute (from config.yml).
     */
    public double getSkillAttributeConfigValue(SkillAttributeType type) {
        if (skillManager == null || type == null) {
            return 0.0D;
        }
        return skillManager.getSkillAttributeConfigValue(type);
    }

    // ------------
    // XP helpers
    // ------------

    /** Grant raw XP to a player (passes through EL's XP bonuses and level cap). */
    public void grantXp(UUID playerUuid, double xpAmount) {
        if (playerUuid == null || levelingManager == null || xpAmount <= 0) {
            return;
        }
        levelingManager.addXp(playerUuid, xpAmount);
    }

    /**
     * Grant XP and share it with the source's party members within maxDistance
     * (same world). If no party or no one in range, only the source receives XP.
     */
    public void grantSharedXpInRange(UUID sourcePlayerUuid, double totalXp, double maxDistance) {
        if (sourcePlayerUuid == null || totalXp <= 0) {
            return;
        }
        if (partyManager != null) {
            partyManager.handleXpGainInRange(sourcePlayerUuid, totalXp, maxDistance);
            return;
        }
        if (levelingManager != null) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
        }
    }

    // ------------
    // Mob overrides
    // ------------

    /**
     * Register a radius/area override (min/max or flat) for mob levels. This is
     * applied before Level_Source (player/distance/fixed), so overrides are not
     * modified by the normal resolver.
     */
    public boolean registerMobAreaLevelOverride(String id, String worldId,
            double centerX, double centerZ, double radius, int minLevel, int maxLevel) {
        return mobLevelingManager != null
                && mobLevelingManager.registerAreaLevelOverride(id, worldId, centerX, centerZ, radius, minLevel,
                        maxLevel);
    }

    /**
     * Register a world-wide override; min/max equal means flat. Also bypasses the
     * normal Level_Source resolver.
     */
    public boolean registerMobWorldLevelOverride(String id, String worldId, int minLevel, int maxLevel) {
        return mobLevelingManager != null
                && mobLevelingManager.registerWorldLevelOverride(id, worldId, minLevel, maxLevel);
    }

    /** Remove a previously registered area/world override. */
    public boolean removeMobAreaLevelOverride(String id) {
        return mobLevelingManager != null && mobLevelingManager.removeAreaLevelOverride(id);
    }

    /** Clear all area/world overrides. */
    public void clearMobAreaLevelOverrides() {
        if (mobLevelingManager != null) {
            mobLevelingManager.clearAreaLevelOverrides();
        }
    }

    /**
     * Set a fixed level for a specific entity index (e.g., a spawned boss). This
     * is checked before any Level_Source logic.
     */
    public void setMobEntityLevelOverride(int entityIndex, int level) {
        if (mobLevelingManager != null) {
            mobLevelingManager.setEntityLevelOverride(entityIndex, level);
        }
    }

    /** Remove a specific entity override. */
    public void clearMobEntityLevelOverride(int entityIndex) {
        if (mobLevelingManager != null) {
            mobLevelingManager.clearEntityLevelOverride(entityIndex);
        }
    }

    /** Clear all per-entity overrides. */
    public void clearAllMobEntityLevelOverrides() {
        if (mobLevelingManager != null) {
            mobLevelingManager.clearAllEntityLevelOverrides();
        }
    }

    private PlayerData getData(UUID uuid) {
        return uuid == null || playerDataManager == null ? null : playerDataManager.get(uuid);
    }
}
