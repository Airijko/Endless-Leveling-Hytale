package com.airijko.endlessleveling.player;

import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.SwiftnessSettings;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.passives.PassiveManager;

/**
 * Handles all skill points and modifiers.
 */
public class SkillManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final double DEFENSE_MAX_REDUCTION = 80.0D;
    private static final double PRECISION_MAX_PERCENT = 100.0;
    private static final double DEFENSE_CURVE_START = 25.0;
    private static final double DEFENSE_SHARP_CURVE_START = 70.0;
    private static final double DEFENSE_MID_SEGMENT_SLOPE = 20.0 / 45.0;
    private static final double DEFENSE_FINAL_SEGMENT_SLOPE = 0.2;
    private static final double DEFAULT_DISCIPLINE_XP_BONUS_PER_LEVEL_PERCENT = 0.5D;
    private static final double DEFAULT_FLOW_PER_LEVEL = 0.5D;
    private static final String COMMON_AUGMENT_SOURCE_PREFIX = "common_";
    private static final String VANGUARD_BASE_CLASS_ID = "vanguard";
    private static final String CLASS_INNATE_CAPS_PATH = "classes.innate_attribute_gain_level_caps";
    private static final String DEFENSE_CAPS_PATH = "defense_caps";
    private static final String DEFENSE_CAPS_DEFAULT_CATEGORY_PATH = DEFENSE_CAPS_PATH + ".default_category";
    private static final String DEFENSE_CAPS_CATEGORIES_PATH = DEFENSE_CAPS_PATH + ".categories";
    private static final String DEFENSE_CAPS_MAX_REDUCTION_KEY = "max_reduction";
    private static final String DEFENSE_CAPS_DEFAULT_CATEGORY = "default";
    private static final int DEFAULT_CLASS_INNATE_LEVEL_CAP = 100;

    private final ConfigManager levelingConfig;
    private final ConfigManager config;
    private final ClassManager classManager;
    private final PlayerAttributeManager attributeManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final PassiveManager passiveManager;
    private final AugmentRuntimeManager augmentRuntimeManager;

    private int baseSkillPoints;
    private int skillPointsPerLevel;
    private volatile Map<String, Double> defenseCapByCategory = Map.of(
            DEFENSE_CAPS_DEFAULT_CATEGORY,
            DEFENSE_MAX_REDUCTION);
    private volatile String defaultDefenseCapCategory = DEFENSE_CAPS_DEFAULT_CATEGORY;
    private final Map<SkillAttributeType, Integer> classInnateAttributeLevelCaps = new EnumMap<>(
            SkillAttributeType.class);

    public SkillManager(PluginFilesManager filesManager,
            ClassManager classManager,
            PlayerAttributeManager attributeManager,
            ArchetypePassiveManager archetypePassiveManager,
            PassiveManager passiveManager,
            AugmentRuntimeManager augmentRuntimeManager) {
        this.levelingConfig = new ConfigManager(filesManager, filesManager.getLevelingFile());
        this.config = new ConfigManager(filesManager, filesManager.getConfigFile());
        this.classManager = classManager;
        this.attributeManager = attributeManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.passiveManager = passiveManager;
        this.augmentRuntimeManager = augmentRuntimeManager;
        ensureFlowConfigLine();
        ensureDefenseCapConfigSection();
        loadConfigValues();
    }

    /** Reload both leveling.yml and config.yml-backed skill settings. */
    public synchronized void reload() {
        levelingConfig.load();
        config.load();
        ensureFlowConfigLine();
        ensureDefenseCapConfigSection();
        loadConfigValues();
    }

    /** Load skill point values from leveling.yml */
    public void loadConfigValues() {
        try {
            baseSkillPoints = getIntFromLevelingConfig("baseSkillPoints", 8);
            skillPointsPerLevel = getIntFromLevelingConfig("skillPointsPerLevel", 4);
            loadClassInnateAttributeLevelCaps();
            loadDefenseCapCategories();
            LOGGER.atInfo().log("SkillManager loaded: baseSkillPoints=%d, skillPointsPerLevel=%d",
                    baseSkillPoints, skillPointsPerLevel);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to load skill points: %s", e.getMessage());
            baseSkillPoints = 8;
            skillPointsPerLevel = 4;
            loadDefaultClassInnateAttributeLevelCaps();
            loadDefaultDefenseCapCategories();
        }
    }

    private void loadDefaultDefenseCapCategories() {
        defenseCapByCategory = Map.of(
                DEFENSE_CAPS_DEFAULT_CATEGORY,
                DEFENSE_MAX_REDUCTION,
                "glass_cannon",
                50.0D,
                "fighter",
                65.0D,
                "tank",
                80.0D);
        defaultDefenseCapCategory = DEFENSE_CAPS_DEFAULT_CATEGORY;
    }

    private void loadDefenseCapCategories() {
        Object rawCategories = config.get(DEFENSE_CAPS_CATEGORIES_PATH, null, false);
        if (!(rawCategories instanceof Map<?, ?> categoriesNode)) {
            loadDefaultDefenseCapCategories();
            return;
        }

        Map<String, Double> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : categoriesNode.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            String normalizedKey = normalizeCategoryKey(key);
            if (normalizedKey == null) {
                continue;
            }
            double maxReduction = parseCategoryMaxReduction(entry.getValue(), DEFENSE_MAX_REDUCTION,
                    DEFENSE_CAPS_CATEGORIES_PATH + "." + key);
            parsed.put(normalizedKey, maxReduction);
        }

        if (parsed.isEmpty()) {
            loadDefaultDefenseCapCategories();
            return;
        }

        String configuredDefault = normalizeCategoryKey(config.get(DEFENSE_CAPS_DEFAULT_CATEGORY_PATH,
                DEFENSE_CAPS_DEFAULT_CATEGORY,
                false));
        if (configuredDefault == null || !parsed.containsKey(configuredDefault)) {
            configuredDefault = parsed.containsKey(DEFENSE_CAPS_DEFAULT_CATEGORY)
                    ? DEFENSE_CAPS_DEFAULT_CATEGORY
                    : parsed.keySet().iterator().next();
        }

        defenseCapByCategory = Map.copyOf(parsed);
        defaultDefenseCapCategory = configuredDefault;
    }

    private double parseCategoryMaxReduction(Object rawValue, double fallback, String path) {
        double parsed;
        if (rawValue instanceof Number number) {
            parsed = number.doubleValue();
        } else if (rawValue instanceof Map<?, ?> map) {
            Object maxReduction = map.get(DEFENSE_CAPS_MAX_REDUCTION_KEY);
            if (!(maxReduction instanceof Number number)) {
                LOGGER.atWarning().log("Missing numeric %s in %s; using fallback %.2f",
                        DEFENSE_CAPS_MAX_REDUCTION_KEY,
                        path,
                        fallback);
                return fallback;
            }
            parsed = number.doubleValue();
        } else {
            LOGGER.atWarning().log("Invalid defense cap category node at %s; using fallback %.2f", path, fallback);
            return fallback;
        }
        return Math.max(0.0D, Math.min(100.0D, parsed));
    }

    private String normalizeCategoryKey(Object rawCategory) {
        if (!(rawCategory instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private void ensureDefenseCapConfigSection() {
        boolean changed = false;
        changed |= config.ensurePath(DEFENSE_CAPS_DEFAULT_CATEGORY_PATH, DEFENSE_CAPS_DEFAULT_CATEGORY);
        changed |= config.ensurePath(DEFENSE_CAPS_CATEGORIES_PATH + ".default." + DEFENSE_CAPS_MAX_REDUCTION_KEY,
                DEFENSE_MAX_REDUCTION);
        changed |= config.ensurePath(DEFENSE_CAPS_CATEGORIES_PATH + ".glass_cannon." + DEFENSE_CAPS_MAX_REDUCTION_KEY,
                50.0D);
        changed |= config.ensurePath(DEFENSE_CAPS_CATEGORIES_PATH + ".fighter." + DEFENSE_CAPS_MAX_REDUCTION_KEY,
                65.0D);
        changed |= config.ensurePath(DEFENSE_CAPS_CATEGORIES_PATH + ".tank." + DEFENSE_CAPS_MAX_REDUCTION_KEY,
                80.0D);
        if (changed) {
            config.save();
        }
    }

    private void loadDefaultClassInnateAttributeLevelCaps() {
        classInnateAttributeLevelCaps.clear();
        for (SkillAttributeType type : SkillAttributeType.values()) {
            classInnateAttributeLevelCaps.put(type,
                    type == SkillAttributeType.LIFE_FORCE ? null : DEFAULT_CLASS_INNATE_LEVEL_CAP);
        }
    }

    private void loadClassInnateAttributeLevelCaps() {
        classInnateAttributeLevelCaps.clear();

        Integer defaultCap = parseOptionalLevelCap(
                levelingConfig.get(CLASS_INNATE_CAPS_PATH + ".default", DEFAULT_CLASS_INNATE_LEVEL_CAP, false),
                DEFAULT_CLASS_INNATE_LEVEL_CAP,
                CLASS_INNATE_CAPS_PATH + ".default");

        for (SkillAttributeType type : SkillAttributeType.values()) {
            String path = CLASS_INNATE_CAPS_PATH + "." + type.getConfigKey();
            Object raw = levelingConfig.get(path, null, false);
            Integer fallback = type == SkillAttributeType.LIFE_FORCE ? null : defaultCap;
            Integer cap = raw == null ? fallback : parseOptionalLevelCap(raw, fallback, path);
            classInnateAttributeLevelCaps.put(type, cap);
        }
    }

    private Integer parseOptionalLevelCap(Object raw, Integer fallback, String path) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            int cap = number.intValue();
            return cap <= 0 ? null : cap;
        }
        if (raw instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return fallback;
            }
            if ("none".equals(normalized) || "unlimited".equals(normalized) || "uncapped".equals(normalized)
                    || "endless".equals(normalized) || "off".equals(normalized)
                    || "disabled".equals(normalized) || "-1".equals(normalized)) {
                return null;
            }
            try {
                int cap = Integer.parseInt(normalized);
                return cap <= 0 ? null : cap;
            } catch (NumberFormatException ignored) {
                LOGGER.atWarning().log("Invalid class innate cap at %s='%s'; using fallback=%s", path, text,
                        fallback == null ? "NONE" : String.valueOf(fallback));
                return fallback;
            }
        }

        LOGGER.atWarning().log("Unsupported class innate cap type at %s (%s); using fallback=%s", path,
                raw.getClass().getSimpleName(), fallback == null ? "NONE" : String.valueOf(fallback));
        return fallback;
    }

    public int applyClassInnateAttributeLevelCap(SkillAttributeType attributeType, int level) {
        int safeLevel = Math.max(1, level);
        if (attributeType == null) {
            return safeLevel;
        }
        Integer cap = classInnateAttributeLevelCaps.get(attributeType);
        if (cap == null || cap <= 0) {
            return safeLevel;
        }
        return Math.min(safeLevel, cap);
    }

    private boolean isClassInnateDefinition(RacePassiveDefinition definition) {
        if (definition == null || definition.properties() == null) {
            return false;
        }
        Object source = definition.properties().get(ArchetypePassiveManager.PASSIVE_SOURCE_PROPERTY);
        if (!(source instanceof String sourceText)) {
            return false;
        }
        return ArchetypePassiveManager.PASSIVE_SOURCE_CLASS.equalsIgnoreCase(sourceText.trim());
    }

    private int getIntFromLevelingConfig(String path, int defaultValue) {
        Object raw = levelingConfig.get(path, defaultValue, false);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** Grant skill points to a player */
    public void addSkillPoints(PlayerData player) {
        player.setSkillPoints(player.getSkillPoints() + skillPointsPerLevel);
    }

    /** Calculate total skill points for a given level */
    public int calculateTotalSkillPoints(int level) {
        return baseSkillPoints + skillPointsPerLevel * (level - 1);
    }

    public int getSkillPointsPerLevel() {
        return skillPointsPerLevel;
    }

    public int getBaseSkillPoints() {
        return baseSkillPoints;
    }

    public double getSkillAttributeConfigValue(SkillAttributeType type) {
        String path = "skill_attributes." + type.getConfigKey();
        Object value = config.get(path, 0.0);

        AttributeConfig parsed = parseAttributeConfig(type, value, path);
        if (!parsed.enabled()) {
            return 0.0;
        }
        return parsed.perLevel();
    }

    private AttributeConfig parseAttributeConfig(SkillAttributeType type, Object rawValue, String path) {
        // Support legacy scalar values
        if (rawValue instanceof Number number) {
            return new AttributeConfig(true, number.doubleValue());
        }

        if (rawValue instanceof Map<?, ?> map) {
            boolean enabled = true;
            double perLevel = 0.0;

            Object enabledNode = map.get("enabled");
            if (enabledNode instanceof Boolean bool) {
                enabled = bool;
            }

            Object perLevelNode = map.get("per_level");
            if (!(perLevelNode instanceof Number)) {
                perLevelNode = map.get("value"); // fallback key
            }
            if (perLevelNode instanceof Number num) {
                perLevel = num.doubleValue();
            }

            if (perLevelNode == null) {
                LOGGER.atWarning().log("parseAttributeConfig: Missing per_level/value for %s", path);
            }
            return new AttributeConfig(enabled, perLevel);
        }

        LOGGER.atWarning().log("parseAttributeConfig: Invalid node at %s; defaulting to enabled=false", path);
        return new AttributeConfig(false, 0.0);
    }

    private double getAugmentAttributeBonus(PlayerData playerData, SkillAttributeType attributeType) {
        if (augmentRuntimeManager == null || playerData == null || attributeType == null) {
            return 0.0D;
        }
        var runtime = augmentRuntimeManager.getRuntimeState(playerData.getUuid());
        if (runtime == null) {
            return 0.0D;
        }
        double bonus = runtime.getAttributeBonus(attributeType, System.currentTimeMillis());
        LOGGER.atFine().log("Augment bonus query: type=%s bonus=%.2f player=%s", attributeType, bonus,
                playerData.getPlayerName());
        return bonus;
    }

    private double getCommonAugmentAttributeBonus(PlayerData playerData, SkillAttributeType attributeType) {
        if (augmentRuntimeManager == null || playerData == null || attributeType == null) {
            return 0.0D;
        }
        var runtime = augmentRuntimeManager.getRuntimeState(playerData.getUuid());
        if (runtime == null) {
            return 0.0D;
        }
        return runtime.getAttributeBonusBySourcePrefix(attributeType,
                System.currentTimeMillis(),
                COMMON_AUGMENT_SOURCE_PREFIX);
    }

    private double getVanguardBlockedCommonCritBonus(PlayerData playerData, SkillAttributeType attributeType) {
        if (!isVanguardCritAttributeLocked(playerData, attributeType)) {
            return 0.0D;
        }
        return getCommonAugmentAttributeBonus(playerData, attributeType);
    }

    public boolean isVanguardCritAttributeLocked(PlayerData playerData, SkillAttributeType attributeType) {
        if (playerData == null || attributeType == null) {
            return false;
        }
        if (attributeType != SkillAttributeType.PRECISION && attributeType != SkillAttributeType.FEROCITY) {
            return false;
        }
        return isVanguardPrimaryClass(playerData);
    }

    public VanguardCritRestrictionResult enforceVanguardCritRestrictions(PlayerData playerData) {
        if (playerData == null || !isVanguardPrimaryClass(playerData)) {
            return VanguardCritRestrictionResult.none();
        }

        int precisionLevel = Math.max(0, playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION));
        int ferocityLevel = Math.max(0, playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY));
        if (precisionLevel <= 0 && ferocityLevel <= 0) {
            return VanguardCritRestrictionResult.none();
        }

        playerData.setPlayerSkillAttributeLevel(SkillAttributeType.PRECISION, 0);
        playerData.setPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY, 0);

        int refunded = precisionLevel + ferocityLevel;
        if (refunded > 0) {
            playerData.setSkillPoints(playerData.getSkillPoints() + refunded);
        }

        return new VanguardCritRestrictionResult(refunded, precisionLevel, ferocityLevel);
    }

    private boolean isVanguardPrimaryClass(PlayerData playerData) {
        String basePrimaryClassId = normalizePrimaryClassBaseId(
                playerData == null ? null : playerData.getPrimaryClassId());
        return VANGUARD_BASE_CLASS_ID.equals(basePrimaryClassId);
    }

    private String normalizePrimaryClassBaseId(String primaryClassId) {
        if (primaryClassId == null || primaryClassId.isBlank()) {
            return null;
        }
        String normalized = primaryClassId.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('_');
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    private record AttributeConfig(boolean enabled, double perLevel) {
    }

    /** Ensure flow config exists after renaming intelligence -> flow. */
    private void ensureFlowConfigLine() {
        try {
            if (config.hasPath("skill_attributes.flow")) {
                return;
            }

            Object legacyValue = config.get("skill_attributes.intelligence", null, false);
            Object valueToUse = DEFAULT_FLOW_PER_LEVEL;
            if (legacyValue instanceof Number number) {
                valueToUse = number.doubleValue();
            }

            if (config.ensurePath("skill_attributes.flow", valueToUse)) {
                config.save();
                double loggedValue = valueToUse instanceof Number num ? num.doubleValue() : DEFAULT_FLOW_PER_LEVEL;
                LOGGER.atInfo().log("Added missing config entry skill_attributes.flow with value %.2f", loggedValue);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Unable to ensure skill_attributes.flow exists: %s", e.getMessage());
        }
    }

    public void resetSkillAttributes(PlayerData player) {
        if (player == null)
            return;

        // Reset all skill attributes to 1 (or your chosen base value)
        for (SkillAttributeType attribute : SkillAttributeType.values()) {
            player.setPlayerSkillAttributeLevel(attribute, 0);
        }

        // Recalculate skill points for the current level
        int totalSkillPoints = calculateTotalSkillPoints(player.getLevel());
        player.setSkillPoints(totalSkillPoints);

        LOGGER.atInfo().log("Reset skill attributes for %s. Total skill points set to %d",
                player.getPlayerName(), totalSkillPoints);
    }

    /**
     * Refunds attribute points that no longer provide value due hard caps:
     * Precision crit chance capped at 100%, Defense reduction capped at 80%.
     */
    public CapRefundResult autoRefundCappedAttributes(PlayerData playerData) {
        if (playerData == null) {
            return CapRefundResult.none();
        }

        int precisionLevel = Math.max(0, playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION));
        int defenseLevel = Math.max(0, playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE));

        int precisionCapLevel = findFirstPrecisionCapLevel(playerData, precisionLevel);
        int defenseCapLevel = findFirstDefenseCapLevel(playerData, defenseLevel);

        int precisionRefund = 0;
        if (precisionCapLevel >= 0 && precisionLevel > precisionCapLevel) {
            precisionRefund = precisionLevel - precisionCapLevel;
            playerData.setPlayerSkillAttributeLevel(SkillAttributeType.PRECISION, precisionCapLevel);
        }

        int defenseRefund = 0;
        if (defenseCapLevel >= 0 && defenseLevel > defenseCapLevel) {
            defenseRefund = defenseLevel - defenseCapLevel;
            playerData.setPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE, defenseCapLevel);
        }

        int totalRefund = precisionRefund + defenseRefund;
        if (totalRefund <= 0) {
            return CapRefundResult.none();
        }

        playerData.setSkillPoints(playerData.getSkillPoints() + totalRefund);
        return new CapRefundResult(totalRefund, precisionRefund, defenseRefund);
    }

    public int getOverflowRefundablePoints(PlayerData playerData, SkillAttributeType attributeType, int currentLevel) {
        if (playerData == null || attributeType == null || currentLevel <= 0) {
            return 0;
        }

        return switch (attributeType) {
            case PRECISION -> computeOverflowRefund(currentLevel, findFirstPrecisionCapLevel(playerData, currentLevel));
            case DEFENSE -> computeOverflowRefund(currentLevel, findFirstDefenseCapLevel(playerData, currentLevel));
            default -> 0;
        };
    }

    private int computeOverflowRefund(int currentLevel, int capLevel) {
        if (capLevel < 0 || currentLevel <= capLevel) {
            return 0;
        }
        return currentLevel - capLevel;
    }

    private int findFirstPrecisionCapLevel(PlayerData playerData, int currentLevel) {
        if (currentLevel <= 0) {
            return -1;
        }

        double currentPercent = getPrecisionPercentWithoutAugment(playerData, currentLevel);
        if (currentPercent < PRECISION_MAX_PERCENT - 1e-6D) {
            return -1;
        }

        int low = 0;
        int high = currentLevel;
        while (low < high) {
            int mid = low + ((high - low) / 2);
            double midPercent = getPrecisionPercentWithoutAugment(playerData, mid);
            if (midPercent >= PRECISION_MAX_PERCENT - 1e-6D) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return Math.max(0, low);
    }

    private double getPrecisionPercentWithoutAugment(PlayerData playerData, int level) {
        double racePercent = attributeManager != null
                ? attributeManager.getRaceAttribute(playerData, SkillAttributeType.PRECISION, 0.0D)
                : 0.0D;
        double perPoint = getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
        double innate = getInnateAttributeBonus(playerData, SkillAttributeType.PRECISION);
        return racePercent + (Math.max(0, level) * perPoint) + innate;
    }

    private int findFirstDefenseCapLevel(PlayerData playerData, int currentLevel) {
        if (currentLevel <= 0) {
            return -1;
        }

        double defenseCap = resolvePrimaryDefenseMaxReduction(playerData);
        double currentReduction = getDefenseReductionPercentWithoutAugment(playerData, currentLevel);
        if (currentReduction < defenseCap - 1e-6D) {
            return -1;
        }

        int low = 0;
        int high = currentLevel;
        while (low < high) {
            int mid = low + ((high - low) / 2);
            double midReduction = getDefenseReductionPercentWithoutAugment(playerData, mid);
            if (midReduction >= defenseCap - 1e-6D) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return Math.max(0, low);
    }

    private double getDefenseReductionPercentWithoutAugment(PlayerData playerData, int level) {
        double raceMultiplier = attributeManager != null
                ? attributeManager.getRaceAttribute(playerData, SkillAttributeType.DEFENSE, 1.0D)
                : 1.0D;
        if (raceMultiplier < 0.0D) {
            raceMultiplier = 0.0D;
        }

        double perPoint = getSkillAttributeConfigValue(SkillAttributeType.DEFENSE);
        double innate = getInnateAttributeBonus(playerData, SkillAttributeType.DEFENSE);
        double rawDefenseValue = (Math.max(0, level) * perPoint) + innate;
        double scaledValue = rawDefenseValue * raceMultiplier;
        return applyDefenseCurve(scaledValue, resolvePrimaryDefenseMaxReduction(playerData));
    }

    // Sorcery / skill modifiers
    public float calculatePlayerSorcery(PlayerData playerData) {
        if (playerData == null)
            return 0f;

        int sorceryLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.SORCERY);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.SORCERY);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.SORCERY);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.SORCERY);
        float totalBonusSorcery = (float) ((sorceryLevel * perPointValue) + innateBonus + augmentBonus);

        LOGGER.atFine().log(
                "calculatePlayerSorcery: SORCERY level=%d, perPointValue=%.2f, innate=%.2f, augment=%.2f, totalBonusSorcery=%.2f for player %s",
                sorceryLevel, perPointValue, innateBonus, augmentBonus, totalBonusSorcery, playerData.getPlayerName());

        return totalBonusSorcery;
    }

    /**
     * Returns the damage modifier to apply based on sorcery.
     * For staff items only. If base spell damage is D and this returns 1.5, use D *
     * 1.5.
     */
    public float getSorceryDamageModifier(PlayerData playerData) {
        float bonus = calculatePlayerSorcery(playerData);
        LOGGER.atFine().log("getSorceryDamageModifier: bonus=%.2f for player %s", bonus,
                playerData.getPlayerName());
        return bonus;
    }

    public float applySorceryModifier(float baseSpellDamage, PlayerData playerData) {
        float sorceryBonus = getSorceryDamageModifier(playerData);
        return baseSpellDamage * (1.0f + (sorceryBonus / 100.0f));
    }

    // Health / skill modifiers
    public float calculatePlayerHealth(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int lifeForceLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.LIFE_FORCE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.LIFE_FORCE);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.LIFE_FORCE);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.LIFE_FORCE);
        float totalBonusHealth = (float) ((lifeForceLevel * perPointValue) + innateBonus + augmentBonus);

        return totalBonusHealth;
    }

    public boolean applyHealthModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        float skillBonus = calculatePlayerHealth(playerData);
        return attributeManager.applyAttribute(PlayerAttributeManager.AttributeSlot.LIFE_FORCE, ref, componentAccessor,
                playerData, skillBonus);
    }

    // Stamina / skill modifiers
    public float calculatePlayerStamina(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int staminaLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STAMINA);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.STAMINA);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.STAMINA);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.STAMINA);
        float totalBonusStamina = (float) ((staminaLevel * perPointValue) + innateBonus + augmentBonus);

        return totalBonusStamina;
    }

    public boolean applyStaminaModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        float skillBonus = calculatePlayerStamina(playerData);
        return attributeManager.applyAttribute(PlayerAttributeManager.AttributeSlot.STAMINA, ref, componentAccessor,
                playerData, skillBonus);
    }

    // Movement / skill modifiers
    public boolean applyMovementSpeedModifier(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        if (playerData == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: playerData null for entity %s", ref);
            return false;
        }

        MovementManager movementManager = componentAccessor.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: MovementManager missing for %s", ref);
            return false;
        }

        MovementSettings defaults = movementManager.getDefaultSettings();
        MovementSettings settings = movementManager.getSettings();
        if (defaults == null || settings == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: Missing MovementSettings for %s", ref);
            return false;
        }

        HasteBreakdown hasteBreakdown = getHasteBreakdown(playerData);
        float requestedMultiplier = hasteBreakdown.totalMultiplier();
        float swiftnessMultiplier = getSwiftnessMultiplier(playerData);
        requestedMultiplier *= swiftnessMultiplier;

        float clampedMultiplier = requestedMultiplier;
        if (settings.maxSpeedMultiplier > 0.0F) {
            clampedMultiplier = Math.min(clampedMultiplier, settings.maxSpeedMultiplier);
        }
        if (settings.minSpeedMultiplier > 0.0F) {
            clampedMultiplier = Math.max(clampedMultiplier, settings.minSpeedMultiplier);
        }

        settings.forwardWalkSpeedMultiplier = defaults.forwardWalkSpeedMultiplier * clampedMultiplier;
        settings.backwardWalkSpeedMultiplier = defaults.backwardWalkSpeedMultiplier * clampedMultiplier;
        settings.strafeWalkSpeedMultiplier = defaults.strafeWalkSpeedMultiplier * clampedMultiplier;
        settings.forwardRunSpeedMultiplier = defaults.forwardRunSpeedMultiplier * clampedMultiplier;
        settings.backwardRunSpeedMultiplier = defaults.backwardRunSpeedMultiplier * clampedMultiplier;
        settings.strafeRunSpeedMultiplier = defaults.strafeRunSpeedMultiplier * clampedMultiplier;
        settings.forwardCrouchSpeedMultiplier = defaults.forwardCrouchSpeedMultiplier * clampedMultiplier;
        settings.backwardCrouchSpeedMultiplier = defaults.backwardCrouchSpeedMultiplier * clampedMultiplier;
        settings.strafeCrouchSpeedMultiplier = defaults.strafeCrouchSpeedMultiplier * clampedMultiplier;
        settings.forwardSprintSpeedMultiplier = defaults.forwardSprintSpeedMultiplier * clampedMultiplier;

        PlayerRef playerRef = componentAccessor.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null) {
            movementManager.update(playerRef.getPacketHandler());
            return true;
        } else {
            LOGGER.atWarning().log("applyMovementSpeedModifier: PlayerRef missing for %s", ref);
            return false;
        }
    }

    // Flow (mana) / skill modifiers
    public float calculatePlayerFlow(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int flowLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FLOW);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.FLOW);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.FLOW);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.FLOW);
        float totalBonusFlow = (float) ((flowLevel * perPointValue) + innateBonus + augmentBonus);

        return totalBonusFlow;
    }

    /**
     * Computes the additive skill contribution (including innate gains) for the
     * supplied attribute, optionally overriding the player's current attribute
     * level.
     */
    public double calculateSkillAttributeBonus(PlayerData playerData,
            SkillAttributeType attributeType,
            int overrideLevel) {
        if (playerData == null || attributeType == null) {
            return 0.0D;
        }
        int effectiveLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(attributeType);
        double perPointValue = getSkillAttributeConfigValue(attributeType);
        double innateBonus = getInnateAttributeBonus(playerData, attributeType);
        double contribution = (effectiveLevel * perPointValue) + innateBonus;
        return contribution > 0.0D ? contribution : 0.0D;
    }

    /**
     * Computes the additive total contribution for the supplied attribute,
     * including level scaling, innate gains, and runtime augment bonuses.
     */
    public double calculateSkillAttributeTotalBonus(PlayerData playerData,
            SkillAttributeType attributeType,
            int overrideLevel) {
        if (playerData == null || attributeType == null) {
            return 0.0D;
        }
        int effectiveLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(attributeType);
        double perPointValue = getSkillAttributeConfigValue(attributeType);
        double innateBonus = getInnateAttributeBonus(playerData, attributeType);
        double augmentBonus = getAugmentAttributeBonus(playerData, attributeType);
        double contribution = (effectiveLevel * perPointValue) + innateBonus + augmentBonus;
        return contribution > 0.0D ? contribution : 0.0D;
    }

    public boolean applyFlowModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        float skillBonus = calculatePlayerFlow(playerData);
        return attributeManager.applyAttribute(PlayerAttributeManager.AttributeSlot.FLOW, ref,
                componentAccessor, playerData, skillBonus);
    }

    public double getDisciplineXpBonusPercent(int disciplineLevel) {
        if (disciplineLevel <= 0) {
            return 0.0D;
        }
        double perLevelPercent = getSkillAttributeConfigValue(SkillAttributeType.DISCIPLINE);
        if (perLevelPercent <= 0.0D) {
            perLevelPercent = DEFAULT_DISCIPLINE_XP_BONUS_PER_LEVEL_PERCENT;
        }
        return Math.max(0.0D, disciplineLevel * perLevelPercent);
    }

    public double getDisciplineXpBonusPercent(PlayerData playerData) {
        if (playerData == null) {
            return 0.0D;
        }
        int disciplineLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DISCIPLINE);
        double baseBonusPercent = getDisciplineXpBonusPercent(disciplineLevel);
        double augmentBonusPercent = getAugmentAttributeBonus(playerData, SkillAttributeType.DISCIPLINE);
        return Math.max(0.0D, baseBonusPercent + augmentBonusPercent);
    }

    public double getXpGainMultiplier(PlayerData playerData) {
        if (playerData == null) {
            return 1.0D;
        }
        int disciplineLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DISCIPLINE);
        double bonusPercent = getDisciplineXpBonusPercent(disciplineLevel);
        return 1.0D + (bonusPercent / 100.0D);
    }

    // Strength / skill modifiers
    public float calculatePlayerStrength(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        StrengthBreakdown breakdown = getStrengthBreakdown(playerData);
        LOGGER.atFine().log(
                "calculatePlayerStrength: raceMultiplier=%.2f, skill=%.2f, total=%.2f for player %s",
                breakdown.raceMultiplier(), breakdown.skillValue(), breakdown.totalValue(), playerData.getPlayerName());

        return breakdown.totalValue();
    }

    /**
     * Returns the damage modifier to apply based on strength.
     * For example, if base damage is D and this returns 1.5, use D * 1.5.
     * If you want additive, just return the bonus.
     */
    public float getStrengthDamageModifier(PlayerData playerData) {
        float bonus = calculatePlayerStrength(playerData);
        LOGGER.atFine().log("getStrengthDamageModifier: bonus=%.2f for player %s", bonus,
                playerData.getPlayerName());
        return bonus;
    }

    public float applyStrengthModifier(float baseDamage, PlayerData playerData) {
        float strengthBonus = getStrengthDamageModifier(playerData);
        return baseDamage * (1.0f + (strengthBonus / 100.0f));
    }

    public StrengthBreakdown getStrengthBreakdown(PlayerData playerData) {
        return getStrengthBreakdown(playerData, -1);
    }

    public StrengthBreakdown getStrengthBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new StrengthBreakdown(1.0f, 0.0f, 0.0f);
        }
        int strengthLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STRENGTH);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.STRENGTH);
        float raceMultiplier = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.STRENGTH, 1.0D);
        if (raceMultiplier < 0.0f) {
            raceMultiplier = 0.0f;
        }
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.STRENGTH);
        float skillValue = (float) ((strengthLevel * perPointValue) + innateBonus);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.STRENGTH);
        float totalValue = (float) (skillValue * raceMultiplier + augmentBonus);
        return new StrengthBreakdown(raceMultiplier, skillValue, totalValue);
    }

    /**
     * Returns the player's precision crit chance as a float between 0.0 and 1.0.
     */
    public float calculatePlayerPrecision(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        PrecisionBreakdown breakdown = getPrecisionBreakdown(playerData);
        LOGGER.atFine().log(
                "getPrecisionCritChance: basePercent=%.4f, skillPercent=%.4f, totalPercent=%.4f, critChance=%.4f for player %s",
                breakdown.racePercent(), breakdown.skillPercent(), breakdown.totalPercent(), breakdown.critChance(),
                playerData.getPlayerName());
        return breakdown.critChance();
    }

    public PrecisionBreakdown getPrecisionBreakdown(PlayerData playerData) {
        return getPrecisionBreakdown(playerData, -1);
    }

    public PrecisionBreakdown getPrecisionBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new PrecisionBreakdown(0.0f, 0.0f, 0.0f, 0.0f);
        }
        boolean critLocked = isVanguardCritAttributeLocked(playerData, SkillAttributeType.PRECISION);
        int precisionLevel = critLocked
                ? 0
                : (overrideLevel >= 0 ? overrideLevel
                        : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION));
        double perPointChance = getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
        float racePercent = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.PRECISION, 0.0D);
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.PRECISION);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.PRECISION)
                - getVanguardBlockedCommonCritBonus(playerData, SkillAttributeType.PRECISION);
        float skillPercent = (float) ((precisionLevel * perPointChance) + innateBonus + augmentBonus);
        float rawTotalPercent = racePercent + skillPercent;
        float totalPercent = Math.max(0.0f, Math.min(100.0f, rawTotalPercent));
        float critChance = totalPercent / 100.0f;
        return new PrecisionBreakdown(racePercent, skillPercent, totalPercent, critChance);
    }

    /**
     * Returns the player's ferocity stat for critical damage calculations.
     */
    public float calculatePlayerFerocity(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        FerocityBreakdown breakdown = getFerocityBreakdown(playerData);
        LOGGER.atFine().log(
                "getPlayerFerocity: base=%.2f, skill=%.2f, total=%.2f for player %s",
                breakdown.raceValue(), breakdown.skillValue(), breakdown.totalValue(), playerData.getPlayerName());
        return breakdown.totalValue();
    }

    public FerocityBreakdown getFerocityBreakdown(PlayerData playerData) {
        return getFerocityBreakdown(playerData, -1);
    }

    public FerocityBreakdown getFerocityBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new FerocityBreakdown(0.0f, 0.0f, 0.0f);
        }
        boolean critLocked = isVanguardCritAttributeLocked(playerData, SkillAttributeType.FEROCITY);
        int ferocityLevel = critLocked
                ? 0
                : (overrideLevel >= 0 ? overrideLevel
                        : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY));
        double perPointFerocity = getSkillAttributeConfigValue(SkillAttributeType.FEROCITY);
        float raceValue = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.FEROCITY, 0.0D);
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.FEROCITY);
        double augmentBonus = getAugmentAttributeBonus(playerData, SkillAttributeType.FEROCITY)
                - getVanguardBlockedCommonCritBonus(playerData, SkillAttributeType.FEROCITY);
        float skillValue = (float) Math.max(0.0D, (ferocityLevel * perPointFerocity) + innateBonus + augmentBonus);
        return new FerocityBreakdown(raceValue, skillValue, Math.max(0.0f, raceValue + skillValue));
    }

    public float calculatePlayerHasteMultiplier(PlayerData playerData) {
        return getHasteBreakdown(playerData).totalMultiplier();
    }

    public HasteBreakdown getHasteBreakdown(PlayerData playerData) {
        return getHasteBreakdown(playerData, -1);
    }

    public HasteBreakdown getHasteBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new HasteBreakdown(1.0f, 0.0f, 1.0f);
        }
        int hasteLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.HASTE);
        double perPointPercent = getSkillAttributeConfigValue(SkillAttributeType.HASTE);
        double perPointValue = perPointPercent / 100.0D;
        double raceMultiplier = attributeManager.getRaceAttribute(playerData, SkillAttributeType.HASTE, 1.0D);
        if (raceMultiplier <= 0.0D) {
            raceMultiplier = 1.0D;
        }
        double innatePercent = getInnateAttributeBonus(playerData, SkillAttributeType.HASTE);
        double innateRatio = innatePercent / 100.0D;
        double augmentPercent = getAugmentAttributeBonus(playerData, SkillAttributeType.HASTE);
        double augmentRatio = augmentPercent / 100.0D;
        float skillBonus = (float) ((hasteLevel * perPointValue) + innateRatio + augmentRatio);
        float total = (float) (raceMultiplier * (1.0D + skillBonus));
        return new HasteBreakdown((float) raceMultiplier, skillBonus, total);
    }

    /**
     * Checks if the attack is a critical hit and applies ferocity-based crit
     * damage.
     * Returns a CritResult object containing the new damage and whether it was a
     * crit.
     */
    public CritResult applyCriticalHit(PlayerData playerData, float baseDamage) {
        float critChance = calculatePlayerPrecision(playerData);
        boolean isCrit = Math.random() < critChance;
        float finalDamage = baseDamage;
        if (isCrit) {
            float ferocity = calculatePlayerFerocity(playerData); // e.g., 50 = +50%
            float multiplier = 1.0F + (ferocity / 100.0F);
            finalDamage = baseDamage * multiplier;
            LOGGER.atFine().log("CRITICAL HIT! base=%.2f, ferocity=%.2f%%, multiplier=%.2f, final=%.2f for player %s",
                    baseDamage, ferocity, multiplier, finalDamage, playerData.getPlayerName());

            // Notify player of critical hit using Hytale's notification system
            if (playerData.isCriticalNotifEnabled()) {
                try {
                    PlayerRef playerRef = Universe.get()
                            .getPlayer(playerData.getUuid());
                    if (playerRef != null) {
                        var packetHandler = playerRef.getPacketHandler();
                        var primaryMessage = Message.raw("CRITICAL HIT!").color("#FF0000");
                        var icon = new ItemStack("Weapon_Longsword_Flame", 1).toPacket();
                        NotificationUtil.sendNotification(packetHandler, primaryMessage, null, icon);
                    }
                } catch (Exception e) {
                    LOGGER.atSevere().log("Failed to send critical hit notification: %s", e.getMessage());
                }
            }
        }
        return new CritResult(finalDamage, isCrit);
    }

    /**
     * Simple result class for crit calculation.
     */
    public static class CritResult {
        public final float damage;
        public final boolean isCrit;

        public CritResult(float damage, boolean isCrit) {
            this.damage = damage;
            this.isCrit = isCrit;
        }
    }

    public record StrengthBreakdown(float raceMultiplier, float skillValue, float totalValue) {
    }

    public record FerocityBreakdown(float raceValue, float skillValue, float totalValue) {
    }

    public record PrecisionBreakdown(float racePercent, float skillPercent, float totalPercent, float critChance) {
    }

    public record HasteBreakdown(float raceMultiplier, float skillBonus, float totalMultiplier) {
    }

    public record DefenseBreakdown(float raceMultiplier,
            float skillValue,
            float innateValue,
            float totalValue,
            float resistance,
            float curvedResistance,
            float commonLinearResistance) {
    }

    public record CapRefundResult(int totalRefunded, int precisionRefunded, int defenseRefunded) {
        public static CapRefundResult none() {
            return new CapRefundResult(0, 0, 0);
        }

        public boolean refunded() {
            return totalRefunded > 0;
        }
    }

    public record VanguardCritRestrictionResult(int totalRefunded, int precisionRefunded, int ferocityRefunded) {
        public static VanguardCritRestrictionResult none() {
            return new VanguardCritRestrictionResult(0, 0, 0);
        }

        public boolean adjusted() {
            return totalRefunded > 0;
        }
    }

    // Defense / skill modifiers
    public float calculatePlayerDefense(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        DefenseBreakdown breakdown = getDefenseBreakdown(playerData);
        LOGGER.atInfo().log(
                "calculatePlayerDefense: raceMultiplier=%.2f, skill=%.2f, innate=%.2f, total=%.2f, curvedRes=%.2f%%, commonRes=%.2f%%, resistance=%.2f%% for player %s",
                breakdown.raceMultiplier(),
                breakdown.skillValue(),
                breakdown.innateValue(),
                breakdown.totalValue(),
                breakdown.curvedResistance() * 100.0f,
                breakdown.commonLinearResistance() * 100.0f,
                breakdown.resistance() * 100.0f,
                playerData.getPlayerName());

        return breakdown.resistance();
    }

    public DefenseBreakdown getDefenseBreakdown(PlayerData playerData) {
        return getDefenseBreakdown(playerData, -1);
    }

    public DefenseBreakdown getDefenseBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new DefenseBreakdown(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }
        int defenseLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.DEFENSE);
        float raceMultiplier = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.DEFENSE, 1.0D);
        if (raceMultiplier < 0.0f) {
            raceMultiplier = 0.0f;
        }

        float skillValue = (float) (defenseLevel * perPointValue);
        float innateValue = (float) getInnateAttributeBonus(playerData, SkillAttributeType.DEFENSE);
        double augmentBonusTotal = getAugmentAttributeBonus(playerData, SkillAttributeType.DEFENSE);
        double commonLinearDefenseBonus = Math.max(0.0D,
                getCommonAugmentAttributeBonus(playerData, SkillAttributeType.DEFENSE));
        double nonCommonAugmentBonus = augmentBonusTotal - commonLinearDefenseBonus;
        float defenseAttributeValue = (float) (skillValue + innateValue + nonCommonAugmentBonus);
        float scaledValue = defenseAttributeValue * raceMultiplier;
        double classCapPercent = resolvePrimaryDefenseMaxReduction(playerData);
        double curvedResistancePercent = applyDefenseCurve(scaledValue, classCapPercent);
        double commonLinearResistancePercent = Math.min(DEFENSE_MAX_REDUCTION, commonLinearDefenseBonus);
        float curvedResistance = (float) (curvedResistancePercent / 100.0D);
        float commonLinearResistance = (float) (commonLinearResistancePercent / 100.0D);
        float classCapResistance = (float) Math.max(0.0D, Math.min(1.0D, classCapPercent / 100.0D));
        float combinedResistance = curvedResistance + commonLinearResistance;
        float resistance = Math.max(0.0f, Math.min(classCapResistance, combinedResistance));
        return new DefenseBreakdown(raceMultiplier,
                skillValue,
                innateValue,
                scaledValue,
                resistance,
                curvedResistance,
                commonLinearResistance);
    }

    private double applyDefenseCurve(double defenseValue, double maxReduction) {
        double reductionAtSharpStart = DEFENSE_CURVE_START
                + (DEFENSE_SHARP_CURVE_START - DEFENSE_CURVE_START) * DEFENSE_MID_SEGMENT_SLOPE;

        double reduction;
        if (defenseValue <= DEFENSE_CURVE_START) {
            reduction = defenseValue;
        } else if (defenseValue <= DEFENSE_SHARP_CURVE_START) {
            reduction = DEFENSE_CURVE_START
                    + (defenseValue - DEFENSE_CURVE_START) * DEFENSE_MID_SEGMENT_SLOPE;
        } else {
            reduction = reductionAtSharpStart
                    + (defenseValue - DEFENSE_SHARP_CURVE_START) * DEFENSE_FINAL_SEGMENT_SLOPE;
        }

        return Math.min(reduction, Math.max(0.0D, maxReduction));
    }

    private double resolvePrimaryDefenseMaxReduction(PlayerData playerData) {
        if (playerData == null) {
            return resolveDefenseCapByCategory(null);
        }

        String classCategory = null;
        if (classManager != null) {
            CharacterClassDefinition primaryClass = classManager.getClass(playerData.getPrimaryClassId());
            if (primaryClass != null) {
                classCategory = primaryClass.getCategory();
            }
        }
        return resolveDefenseCapByCategory(classCategory);
    }

    private double resolveDefenseCapByCategory(String classCategory) {
        String normalized = normalizeCategoryKey(classCategory);
        Map<String, Double> configuredCaps = defenseCapByCategory;
        if (normalized != null) {
            Double direct = configuredCaps.get(normalized);
            if (direct != null) {
                return direct;
            }
        }

        Double fallback = configuredCaps.get(defaultDefenseCapCategory);
        if (fallback != null) {
            return fallback;
        }

        Double defaultCap = configuredCaps.get(DEFENSE_CAPS_DEFAULT_CATEGORY);
        return defaultCap != null ? defaultCap : DEFENSE_MAX_REDUCTION;
    }

    private float getSwiftnessMultiplier(PlayerData playerData) {
        if (playerData == null || archetypePassiveManager == null) {
            return 1.0F;
        }
        ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(playerData);
        SwiftnessSettings settings = SwiftnessSettings.fromSnapshot(snapshot);
        if (!settings.enabled()) {
            return 1.0F;
        }

        int stacks = 0;
        if (passiveManager != null) {
            PassiveManager.PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
            if (runtimeState != null && runtimeState.getSwiftnessStacks() > 0) {
                long activeUntil = runtimeState.getSwiftnessActiveUntil();
                if (activeUntil > 0L && System.currentTimeMillis() <= activeUntil) {
                    stacks = runtimeState.getSwiftnessStacks();
                } else {
                    runtimeState.clearSwiftness();
                }
            }
        }

        if (stacks <= 0) {
            return 1.0F;
        }

        double multiplier = settings.multiplierForStacks(stacks);
        return multiplier > 0.0D ? (float) multiplier : 1.0F;
    }

    private double getInnateAttributeBonus(PlayerData playerData, SkillAttributeType attributeType) {
        if (playerData == null || attributeType == null || archetypePassiveManager == null) {
            return 0.0D;
        }
        ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(playerData);
        if (snapshot == null || snapshot.isEmpty()) {
            return 0.0D;
        }
        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN);
        if (definitions.isEmpty()) {
            return 0.0D;
        }
        double classPerLevelValue = 0.0D;
        double uncappedPerLevelValue = 0.0D;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            if (definition.attributeType() != attributeType) {
                continue;
            }
            double candidate = Math.max(0.0D, definition.value());
            if (candidate == 0.0D) {
                continue;
            }
            if (isClassInnateDefinition(definition)) {
                classPerLevelValue += candidate;
            } else {
                uncappedPerLevelValue += candidate;
            }
        }
        if (classPerLevelValue == 0.0D && uncappedPerLevelValue == 0.0D) {
            return 0.0D;
        }
        int playerLevel = Math.max(1, playerData.getLevel());
        int classEffectiveLevels = applyClassInnateAttributeLevelCap(attributeType, playerLevel);
        return (uncappedPerLevelValue * playerLevel) + (classPerLevelValue * classEffectiveLevels);
    }

    public void clearAugmentAttributeBonuses(PlayerData playerData) {
        if (augmentRuntimeManager == null || playerData == null || playerData.getUuid() == null) {
            return;
        }
        var runtime = augmentRuntimeManager.getRuntimeState(playerData.getUuid());
        if (runtime != null) {
            runtime.clearAttributeBonuses();
        }
    }

    /**
     * Apply all skill-based modifiers to an entity (health, stamina, etc.)
     */
    public boolean applyAllSkillModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        boolean healthApplied = applyHealthModifiers(ref, componentAccessor, playerData);
        boolean staminaApplied = applyStaminaModifiers(ref, componentAccessor, playerData);
        boolean movementApplied = applyMovementSpeedModifier(ref, componentAccessor, playerData);
        boolean flowApplied = applyFlowModifiers(ref, componentAccessor, playerData);
        boolean success = healthApplied && staminaApplied && movementApplied && flowApplied;
        if (!success) {
            LOGGER.atFine().log(
                    "applyAllSkillModifiers: deferred or partial application for player %s (health=%s, stamina=%s, movement=%s, flow=%s)",
                    playerData.getPlayerName(), healthApplied, staminaApplied, movementApplied, flowApplied);
        }
        return success;
    }
}
