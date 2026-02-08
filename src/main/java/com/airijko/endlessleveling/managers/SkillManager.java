package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.SwiftnessSettings;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Handles all skill points and modifiers.
 */
public class SkillManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final double DEFENSE_MAX_REDUCTION = 80.0;
    private static final double DEFENSE_CURVE_START = 30.0;
    private static final double DEFENSE_SHARP_CURVE_START = 100.0;
    private static final double DEFENSE_MID_SEGMENT_SLOPE = 0.5;
    private static final double DEFENSE_FINAL_SEGMENT_SLOPE = 0.2;

    private final LevelingConfigManager levelingConfig;
    private final ConfigManager config;
    private final PlayerAttributeManager attributeManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final PassiveManager passiveManager;

    private int baseSkillPoints;
    private int skillPointsPerLevel;

    public SkillManager(PluginFilesManager filesManager,
            PlayerAttributeManager attributeManager,
            ArchetypePassiveManager archetypePassiveManager,
            PassiveManager passiveManager) {
        this.levelingConfig = new LevelingConfigManager(filesManager.getLevelingFile());
        this.config = new ConfigManager(filesManager.getConfigFile());
        this.attributeManager = attributeManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.passiveManager = passiveManager;
        loadConfigValues();
    }

    /** Load skill point values from leveling.yml */
    public void loadConfigValues() {
        try {
            baseSkillPoints = levelingConfig.getInt("baseSkillPoints", 8);
            skillPointsPerLevel = levelingConfig.getInt("skillPointsPerLevel", 4);
            LOGGER.atInfo().log("SkillManager loaded: baseSkillPoints=%d, skillPointsPerLevel=%d",
                    baseSkillPoints, skillPointsPerLevel);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to load skill points: %s", e.getMessage());
            baseSkillPoints = 8;
            skillPointsPerLevel = 4;
        }
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

        if (value instanceof Number number) {
            double result = number.doubleValue();
            LOGGER.atInfo().log("getSkillAttributeConfigValue: path=%s, value=%.2f", path, result);
            return result;
        } else {
            LOGGER.atWarning().log("getSkillAttributeConfigValue: Invalid value at path=%s, defaulting to 0", path);
            return 0.0;
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

    // Health / skill modifiers
    public float calculatePlayerHealth(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int lifeForceLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.LIFE_FORCE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.LIFE_FORCE);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.LIFE_FORCE);
        float totalBonusHealth = (float) ((lifeForceLevel * perPointValue) + innateBonus);

        LOGGER.atInfo().log(
                "calculatePlayerHealth: LIFE_FORCE level=%d, perPointValue=%.2f, innate=%.2f, totalBonusHealth=%.2f for player %s",
                lifeForceLevel, perPointValue, innateBonus, totalBonusHealth, playerData.getPlayerName());

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
        float totalBonusStamina = (float) ((staminaLevel * perPointValue) + innateBonus);

        LOGGER.atInfo().log(
                "calculatePlayerStamina: STAMINA level=%d, perPointValue=%.2f, innate=%.2f, totalBonusStamina=%.2f for player %s",
                staminaLevel, perPointValue, innateBonus, totalBonusStamina, playerData.getPlayerName());

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
            LOGGER.atInfo().log(
                    "applyMovementSpeedModifier: base=%.3f, skill=%.3f, swiftness=%.3f, final=%.4f for player %s",
                    hasteBreakdown.raceMultiplier(), hasteBreakdown.skillBonus(), swiftnessMultiplier,
                    clampedMultiplier, playerData.getPlayerName());
            return true;
        } else {
            LOGGER.atWarning().log("applyMovementSpeedModifier: PlayerRef missing for %s", ref);
            return false;
        }
    }

    // Intelligence (mana) / skill modifiers
    public float calculatePlayerIntelligence(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int intelligenceLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.INTELLIGENCE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.INTELLIGENCE);

        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.INTELLIGENCE);
        float totalBonusIntelligence = (float) ((intelligenceLevel * perPointValue) + innateBonus);

        LOGGER.atInfo().log(
                "calculatePlayerIntelligence: INTELLIGENCE level=%d, perPointValue=%.2f, innate=%.2f, totalBonusIntelligence=%.2f for player %s",
                intelligenceLevel, perPointValue, innateBonus, totalBonusIntelligence, playerData.getPlayerName());

        return totalBonusIntelligence;
    }

    public boolean applyIntelligenceModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        float skillBonus = calculatePlayerIntelligence(playerData);
        return attributeManager.applyAttribute(PlayerAttributeManager.AttributeSlot.INTELLIGENCE, ref,
                componentAccessor, playerData, skillBonus);
    }

    // Strength / skill modifiers
    public float calculatePlayerStrength(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        StrengthBreakdown breakdown = getStrengthBreakdown(playerData);
        LOGGER.atInfo().log(
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
        LOGGER.atInfo().log("getStrengthDamageModifier: bonus=%.2f for player %s", bonus,
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
        float totalValue = skillValue * raceMultiplier;
        return new StrengthBreakdown(raceMultiplier, skillValue, totalValue);
    }

    /**
     * Returns the player's precision crit chance as a float between 0.0 and 1.0.
     */
    public float calculatePlayerPrecision(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        PrecisionBreakdown breakdown = getPrecisionBreakdown(playerData);
        LOGGER.atInfo().log(
                "getPrecisionCritChance: basePercent=%.4f, skillPercent=%.4f, totalPercent=%.4f, critChance=%.4f for player %s",
                breakdown.racePercent(), breakdown.skillPercent(), breakdown.totalPercent(), breakdown.critChance(),
                playerData.getPlayerName());
        return breakdown.critChance();
    }

    public PrecisionBreakdown getPrecisionBreakdown(PlayerData playerData) {
        if (playerData == null) {
            return new PrecisionBreakdown(0.0f, 0.0f, 0.0f, 0.0f);
        }
        int precisionLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION);
        double perPointChance = getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
        float racePercent = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.PRECISION, 0.0D);
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.PRECISION);
        float skillPercent = (float) ((precisionLevel * perPointChance) + innateBonus);
        float totalPercent = racePercent + skillPercent;
        float critChance = totalPercent / 100.0f;
        if (critChance > 1.0f) {
            critChance = 1.0f;
        }
        return new PrecisionBreakdown(racePercent, skillPercent, totalPercent, critChance);
    }

    /**
     * Returns the player's ferocity stat for critical damage calculations.
     */
    public float calculatePlayerFerocity(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        FerocityBreakdown breakdown = getFerocityBreakdown(playerData);
        LOGGER.atInfo().log(
                "getPlayerFerocity: base=%.2f, skill=%.2f, total=%.2f for player %s",
                breakdown.raceValue(), breakdown.skillValue(), breakdown.totalValue(), playerData.getPlayerName());
        return breakdown.totalValue();
    }

    public FerocityBreakdown getFerocityBreakdown(PlayerData playerData) {
        if (playerData == null) {
            return new FerocityBreakdown(0.0f, 0.0f, 0.0f);
        }
        int ferocityLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY);
        double perPointFerocity = getSkillAttributeConfigValue(SkillAttributeType.FEROCITY);
        float raceValue = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.FEROCITY, 0.0D);
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.FEROCITY);
        float skillValue = (float) ((ferocityLevel * perPointFerocity) + innateBonus);
        return new FerocityBreakdown(raceValue, skillValue, raceValue + skillValue);
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
        float skillBonus = (float) ((hasteLevel * perPointValue) + innateRatio);
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
            LOGGER.atInfo().log("CRITICAL HIT! base=%.2f, ferocity=%.2f%%, multiplier=%.2f, final=%.2f for player %s",
                    baseDamage, ferocity, multiplier, finalDamage, playerData.getPlayerName());

            // Notify player of critical hit using Hytale's notification system
            if (playerData.isCriticalNotifEnabled()) {
                try {
                    PlayerRef playerRef = com.hypixel.hytale.server.core.universe.Universe.get()
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

    public record DefenseBreakdown(float raceMultiplier, float skillValue, float totalValue, float resistance) {
    }

    // Defense / skill modifiers
    public float calculatePlayerDefense(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        DefenseBreakdown breakdown = getDefenseBreakdown(playerData);
        LOGGER.atInfo().log(
                "calculatePlayerDefense: raceMultiplier=%.2f, skill=%.2f, total=%.2f, resistance=%.2f%% for player %s",
                breakdown.raceMultiplier(), breakdown.skillValue(), breakdown.totalValue(),
                breakdown.resistance() * 100.0f,
                playerData.getPlayerName());

        return breakdown.resistance();
    }

    public DefenseBreakdown getDefenseBreakdown(PlayerData playerData) {
        return getDefenseBreakdown(playerData, -1);
    }

    public DefenseBreakdown getDefenseBreakdown(PlayerData playerData, int overrideLevel) {
        if (playerData == null) {
            return new DefenseBreakdown(1.0f, 0.0f, 0.0f, 0.0f);
        }
        int defenseLevel = overrideLevel >= 0 ? overrideLevel
                : playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.DEFENSE);
        float raceMultiplier = (float) attributeManager.getRaceAttribute(playerData, SkillAttributeType.DEFENSE, 1.0D);
        if (raceMultiplier < 0.0f) {
            raceMultiplier = 0.0f;
        }
        double innateBonus = getInnateAttributeBonus(playerData, SkillAttributeType.DEFENSE);
        float skillValue = (float) ((defenseLevel * perPointValue) + innateBonus);
        float totalValue = skillValue * raceMultiplier;
        float resistance = (float) (applyDefenseCurve(totalValue) / 100.0D);
        return new DefenseBreakdown(raceMultiplier, skillValue, totalValue, resistance);
    }

    private double applyDefenseCurve(double defenseValue) {
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

        return Math.min(reduction, DEFENSE_MAX_REDUCTION);
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
        double perLevelValue = 0.0D;
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
            perLevelValue += candidate;
        }
        if (perLevelValue == 0.0D) {
            return 0.0D;
        }
        int effectiveLevels = Math.max(0, playerData.getLevel() - 1);
        if (effectiveLevels <= 0) {
            return 0.0D;
        }
        return perLevelValue * effectiveLevels;
    }

    /**
     * Apply all skill-based modifiers to an entity (health, stamina, etc.)
     */
    public boolean applyAllSkillModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        boolean healthApplied = applyHealthModifiers(ref, componentAccessor, playerData);
        boolean staminaApplied = applyStaminaModifiers(ref, componentAccessor, playerData);
        boolean movementApplied = applyMovementSpeedModifier(ref, componentAccessor, playerData);
        boolean intelligenceApplied = applyIntelligenceModifiers(ref, componentAccessor, playerData);
        boolean success = healthApplied && staminaApplied && movementApplied && intelligenceApplied;
        if (success) {
            LOGGER.atInfo().log("applyAllSkillModifiers: Applied all skill modifiers for player %s",
                    playerData.getPlayerName());
        } else {
            LOGGER.atFine().log(
                    "applyAllSkillModifiers: deferred or partial application for player %s (health=%s, stamina=%s, movement=%s, intelligence=%s)",
                    playerData.getPlayerName(), healthApplied, staminaApplied, movementApplied, intelligenceApplied);
        }
        return success;
    }
}
