package com.airijko.endlessleveling.api;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.player.PlayerAttributeManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSource;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.player.SkillManager;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lightweight public API surface for other mods to query EndlessLeveling state
 * without touching internal manager classes.
 */
public final class EndlessLevelingAPI {

    private static final EndlessLevelingAPI INSTANCE = new EndlessLevelingAPI();

    private EndlessLevelingAPI() {
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

        SkillManager skillManager = skillManager();
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
        SkillManager skillManager = skillManager();
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
        PlayerAttributeManager attributeManager = attributeManager();
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
        RaceManager raceManager = raceManager();
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

    /** Current prestige level (0 if missing). */
    public int getPlayerPrestigeLevel(UUID uuid) {
        PlayerData data = getData(uuid);
        return data != null ? data.getPrestigeLevel() : 0;
    }

    /** Maximum configured level cap. */
    public int getLevelCap() {
        LevelingManager levelingManager = levelingManager();
        return levelingManager != null ? levelingManager.getLevelCap() : 0;
    }

    /** Player-specific level cap (includes prestige scaling). */
    public int getLevelCap(UUID uuid) {
        LevelingManager levelingManager = levelingManager();
        if (levelingManager == null) {
            return 0;
        }
        PlayerData data = getData(uuid);
        return data != null ? levelingManager.getLevelCap(data) : levelingManager.getLevelCap();
    }

    /**
     * Level needed XP; returns POSITIVE_INFINITY if at/above cap or unavailable.
     */
    public double getXpForNextLevel(int level) {
        LevelingManager levelingManager = levelingManager();
        return levelingManager != null ? levelingManager.getXpForNextLevel(level) : Double.POSITIVE_INFINITY;
    }

    /**
     * Player-specific next-level XP; includes prestige base XP scaling.
     * Returns POSITIVE_INFINITY if unavailable or at/above cap.
     */
    public double getXpForNextLevel(UUID uuid, int level) {
        LevelingManager levelingManager = levelingManager();
        if (levelingManager == null) {
            return Double.POSITIVE_INFINITY;
        }
        PlayerData data = getData(uuid);
        return levelingManager.getXpForNextLevel(data, level);
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

    /** Resolve a registered race definition by id; returns null if missing. */
    public RaceDefinition getRaceDefinition(String id) {
        RaceManager raceManager = raceManager();
        return raceManager == null ? null : raceManager.getRace(id);
    }

    /** Resolve a registered class definition by id; returns null if missing. */
    public CharacterClassDefinition getClassDefinition(String id) {
        ClassManager classManager = classManager();
        return classManager == null ? null : classManager.getClass(id);
    }

    /**
     * Return all currently loaded race definitions, including API-registered ones.
     */
    public Collection<RaceDefinition> getRaceDefinitions() {
        RaceManager raceManager = raceManager();
        return raceManager == null ? List.of() : List.copyOf(raceManager.getLoadedRaces());
    }

    /**
     * Return all currently loaded class definitions, including API-registered ones.
     */
    public Collection<CharacterClassDefinition> getClassDefinitions() {
        ClassManager classManager = classManager();
        return classManager == null ? List.of() : List.copyOf(classManager.getLoadedClasses());
    }

    /**
     * Underlying per-point config value for a skill attribute (from config.yml).
     */
    public double getSkillAttributeConfigValue(SkillAttributeType type) {
        SkillManager skillManager = skillManager();
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
        LevelingManager levelingManager = levelingManager();
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
        PartyManager partyManager = partyManager();
        LevelingManager levelingManager = levelingManager();
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
     * applied before Level_Source (player/distance/fixed/tiers), so overrides are
     * not
     * modified by the normal resolver.
     */
    public boolean registerMobAreaLevelOverride(String id, String worldId,
            double centerX, double centerZ, double radius, int minLevel, int maxLevel) {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        return mobLevelingManager != null
                && mobLevelingManager.registerAreaLevelOverride(id, worldId, centerX, centerZ, radius, minLevel,
                        maxLevel);
    }

    /**
     * Register a world-wide override; min/max equal means flat. Also bypasses the
     * normal Level_Source resolver.
     */
    public boolean registerMobWorldLevelOverride(String id, String worldId, int minLevel, int maxLevel) {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        return mobLevelingManager != null
                && mobLevelingManager.registerWorldLevelOverride(id, worldId, minLevel, maxLevel);
    }

    /** Remove a previously registered area/world override. */
    public boolean removeMobAreaLevelOverride(String id) {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        return mobLevelingManager != null && mobLevelingManager.removeAreaLevelOverride(id);
    }

    /** Clear all area/world overrides. */
    public void clearMobAreaLevelOverrides() {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        if (mobLevelingManager != null) {
            mobLevelingManager.clearAreaLevelOverrides();
        }
    }

    /**
     * Set a fixed level for a specific entity index (e.g., a spawned boss). This
     * is checked before any Level_Source logic.
     */
    public void setMobEntityLevelOverride(int entityIndex, int level) {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        if (mobLevelingManager != null) {
            mobLevelingManager.setEntityLevelOverride(entityIndex, level);
        }
    }

    /** Remove a specific entity override. */
    public void clearMobEntityLevelOverride(int entityIndex) {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        if (mobLevelingManager != null) {
            mobLevelingManager.clearEntityLevelOverride(entityIndex);
        }
    }

    /** Clear all per-entity overrides. */
    public void clearAllMobEntityLevelOverrides() {
        MobLevelingManager mobLevelingManager = mobLevelingManager();
        if (mobLevelingManager != null) {
            mobLevelingManager.clearAllEntityLevelOverrides();
        }
    }

    /** Resolve a registered augment definition by id; returns null if missing. */
    public AugmentDefinition getAugmentDefinition(String id) {
        AugmentManager augmentManager = augmentManager();
        return augmentManager == null ? null : augmentManager.getAugment(id);
    }

    /**
     * Register a custom augment definition backed by EndlessLeveling's default
     * augment fallback unless a custom factory is also registered.
     */
    public boolean registerAugment(AugmentDefinition definition) {
        return registerAugment(definition, null, false);
    }

    /** Register a custom augment definition and Java factory. */
    public boolean registerAugment(AugmentDefinition definition,
            Function<AugmentDefinition, Augment> factory) {
        return registerAugment(definition, factory, false);
    }

    /**
     * Register a custom augment definition and optional Java factory.
     * When replaceExisting is true, external registrations may override built-in
     * or file-backed augments using the same id.
     */
    public boolean registerAugment(AugmentDefinition definition,
            Function<AugmentDefinition, Augment> factory,
            boolean replaceExisting) {
        if (definition == null) {
            return false;
        }

        AugmentManager augmentManager = augmentManager();
        String augmentId = definition.getId();
        if (augmentManager == null || augmentId == null || augmentId.isBlank()) {
            return false;
        }
        if (!augmentManager.canRegisterExternalAugment(augmentId, replaceExisting)) {
            return false;
        }
        if (factory != null && !AugmentManager.canRegisterFactory(augmentId, replaceExisting)) {
            return false;
        }

        augmentManager.registerExternalAugment(definition);
        if (factory != null) {
            AugmentManager.registerFactory(augmentId, factory);
        }
        return true;
    }

    /** Remove a previously registered external augment definition and factory. */
    public boolean unregisterAugment(String id) {
        AugmentManager augmentManager = augmentManager();
        boolean definitionRemoved = augmentManager != null && augmentManager.unregisterExternalAugment(id);
        boolean factoryRemoved = AugmentManager.unregisterFactory(id) != null;
        return definitionRemoved || factoryRemoved;
    }

    /** Register a custom race definition. */
    public boolean registerRace(RaceDefinition definition) {
        return registerRace(definition, false);
    }

    /**
     * Register a custom race definition.
     * When replaceExisting is true, external registrations may override built-in
     * or file-backed races using the same id.
     */
    public boolean registerRace(RaceDefinition definition, boolean replaceExisting) {
        if (definition == null) {
            return false;
        }
        RaceManager raceManager = raceManager();
        String raceId = definition.getId();
        if (raceManager == null || raceId == null || raceId.isBlank()) {
            return false;
        }
        if (!raceManager.canRegisterExternalRace(raceId, replaceExisting)) {
            return false;
        }
        raceManager.registerExternalRace(definition);
        return true;
    }

    /** Remove a previously registered external race definition. */
    public boolean unregisterRace(String id) {
        RaceManager raceManager = raceManager();
        return raceManager != null && raceManager.unregisterExternalRace(id);
    }

    /** Register a custom class definition. */
    public boolean registerClass(CharacterClassDefinition definition) {
        return registerClass(definition, false);
    }

    /**
     * Register a custom class definition.
     * When replaceExisting is true, external registrations may override built-in
     * or file-backed classes using the same id.
     */
    public boolean registerClass(CharacterClassDefinition definition, boolean replaceExisting) {
        if (definition == null) {
            return false;
        }
        ClassManager classManager = classManager();
        String classId = definition.getId();
        if (classManager == null || classId == null || classId.isBlank()) {
            return false;
        }
        if (!classManager.canRegisterExternalClass(classId, replaceExisting)) {
            return false;
        }
        classManager.registerExternalClass(definition);
        return true;
    }

    /** Remove a previously registered external class definition. */
    public boolean unregisterClass(String id) {
        ClassManager classManager = classManager();
        return classManager != null && classManager.unregisterExternalClass(id);
    }

    // ----------------------
    // Archetype Passive APIs
    // ----------------------

    /**
     * Register a custom archetype passive source.
     * The source will be called during snapshot generation for each player to
     * provide
     * additional passives. Use this to add conditional passives based on external
     * criteria.
     */
    public boolean registerArchetypePassiveSource(ArchetypePassiveSource source) {
        if (source == null) {
            return false;
        }
        ArchetypePassiveManager manager = archetypePassiveManager();
        return manager != null && manager.registerArchetypePassiveSource(source);
    }

    /**
     * Unregister a previously registered custom archetype passive source.
     */
    public boolean unregisterArchetypePassiveSource(ArchetypePassiveSource source) {
        if (source == null) {
            return false;
        }
        ArchetypePassiveManager manager = archetypePassiveManager();
        return manager != null && manager.unregisterArchetypePassiveSource(source);
    }

    private PlayerData getData(UUID uuid) {
        PlayerDataManager playerDataManager = playerDataManager();
        return uuid == null || playerDataManager == null ? null : playerDataManager.get(uuid);
    }

    private EndlessLeveling plugin() {
        return EndlessLeveling.getInstance();
    }

    private PlayerDataManager playerDataManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getPlayerDataManager() : null;
    }

    private SkillManager skillManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getSkillManager() : null;
    }

    private LevelingManager levelingManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getLevelingManager() : null;
    }

    private RaceManager raceManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getRaceManager() : null;
    }

    private ClassManager classManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getClassManager() : null;
    }

    private PlayerAttributeManager attributeManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getPlayerAttributeManager() : null;
    }

    private MobLevelingManager mobLevelingManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getMobLevelingManager() : null;
    }

    private PartyManager partyManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    private AugmentManager augmentManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getAugmentManager() : null;
    }

    private ArchetypePassiveManager archetypePassiveManager() {
        EndlessLeveling plugin = plugin();
        return plugin != null ? plugin.getArchetypePassiveManager() : null;
    }
}
