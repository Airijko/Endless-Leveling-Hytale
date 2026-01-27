package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

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

    private final File levelingFile;
    private final ConfigManager config;

    private int baseSkillPoints;
    private int skillPointsPerLevel;

    public SkillManager(PluginFilesManager filesManager) {
        this.levelingFile = filesManager.getLevelingFile();
        this.config = new ConfigManager(filesManager.getConfigFile());
        loadConfigValues();
    }

    /** Load skill point values from leveling.yml */
    public void loadConfigValues() {
        if (levelingFile == null || !levelingFile.exists()) {
            LOGGER.atWarning().log("leveling.yml not found, using default skill points.");
            baseSkillPoints = 8;
            skillPointsPerLevel = 4;
            return;
        }

        try (FileInputStream fis = new FileInputStream(levelingFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(fis);
            baseSkillPoints = ((Number) config.getOrDefault("baseSkillPoints", 8)).intValue();
            skillPointsPerLevel = ((Number) config.getOrDefault("skillPointsPerLevel", 4)).intValue();
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

        float totalBonusHealth = (float) (lifeForceLevel * perPointValue);

        LOGGER.atInfo().log(
                "calculatePlayerHealth: LIFE_FORCE level=%d, perPointValue=%.2f, totalBonusHealth=%.2f for player %s",
                lifeForceLevel, perPointValue, totalBonusHealth, playerData.getPlayerName());

        return totalBonusHealth;
    }

    public static float getPlayerHealth(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue value = statMap.get(healthIndex);
            if (value != null) {
                return value.get();
            }
        }
        return 0.0F;
    }

    public void applyHealthModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            float modifierAmount = calculatePlayerHealth(playerData);
            String key = "SKILL_BONUS_HEALTH";
            try {
                StaticModifier modifier = new StaticModifier(
                        ModifierTarget.MAX,
                        CalculationType.ADDITIVE,
                        modifierAmount);
                statMap.putModifier(healthIndex, key, modifier);
                LOGGER.atInfo().log("applyHealthModifiers: Applied StaticModifier to max health: +%.2f for entity %s",
                        modifierAmount, ref);
            } catch (Exception e) {
                LOGGER.atSevere().log("applyHealthModifiers: Failed to apply StaticModifier: %s", e.getMessage());
            }
        } else {
            LOGGER.atWarning().log("applyHealthModifiers: No EntityStatMap found for entity %s", ref);
        }
    }

    // Stamina / skill modifiers
    public float calculatePlayerStamina(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int staminaLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STAMINA);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.STAMINA);

        float totalBonusStamina = (float) (staminaLevel * perPointValue);

        LOGGER.atInfo().log(
                "calculatePlayerStamina: STAMINA level=%d, perPointValue=%.2f, totalBonusStamina=%.2f for player %s",
                staminaLevel, perPointValue, totalBonusStamina, playerData.getPlayerName());

        return totalBonusStamina;
    }

    public static float getPlayerStamina(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            // Replace with correct stamina stat index if available
            int staminaIndex = DefaultEntityStatTypes.getStamina();
            EntityStatValue value = statMap.get(staminaIndex);
            if (value != null) {
                return value.get();
            }
        }
        return 0.0F;
    }

    public void applyStaminaModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            // Replace with correct stamina stat index if available
            int staminaIndex = DefaultEntityStatTypes.getStamina();
            float modifierAmount = calculatePlayerStamina(playerData);
            String key = "SKILL_BONUS_STAMINA";
            try {
                StaticModifier modifier = new StaticModifier(
                        ModifierTarget.MAX,
                        CalculationType.ADDITIVE,
                        modifierAmount);
                statMap.putModifier(staminaIndex, key, modifier);
                LOGGER.atInfo().log("applyStaminaModifiers: Applied StaticModifier to max stamina: +%.2f for entity %s",
                        modifierAmount, ref);
            } catch (Exception e) {
                LOGGER.atSevere().log("applyStaminaModifiers: Failed to apply StaticModifier: %s", e.getMessage());
            }
        } else {
            LOGGER.atWarning().log("applyStaminaModifiers: No EntityStatMap found for entity %s", ref);
        }
    }

    // Movement / skill modifiers
    public void applyMovementSpeedModifier(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        if (playerData == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: playerData null for entity %s", ref);
            return;
        }

        MovementManager movementManager = componentAccessor.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: MovementManager missing for %s", ref);
            return;
        }

        MovementSettings defaults = movementManager.getDefaultSettings();
        MovementSettings settings = movementManager.getSettings();
        if (defaults == null || settings == null) {
            LOGGER.atWarning().log("applyMovementSpeedModifier: Missing MovementSettings for %s", ref);
            return;
        }

        int hasteLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.HASTE);
        double perPointPercent = getSkillAttributeConfigValue(SkillAttributeType.HASTE);
        double perPointValue = perPointPercent / 100.0D; // Convert percent config to multiplier increment
        float requestedMultiplier = 1.0F + (float) (hasteLevel * perPointValue);

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
                    "applyMovementSpeedModifier: hasteLevel=%d, perPointPercent=%.2f, multiplier=%.4f for player %s",
                    hasteLevel, perPointPercent, clampedMultiplier, playerData.getPlayerName());
        } else {
            LOGGER.atWarning().log("applyMovementSpeedModifier: PlayerRef missing for %s", ref);
        }
    }

    // Intelligence (mana) / skill modifiers
    public float calculatePlayerIntelligence(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int intelligenceLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.INTELLIGENCE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.INTELLIGENCE);

        float totalBonusIntelligence = (float) (intelligenceLevel * perPointValue);

        LOGGER.atInfo().log(
                "calculatePlayerIntelligence: INTELLIGENCE level=%d, perPointValue=%.2f, totalBonusIntelligence=%.2f for player %s",
                intelligenceLevel, perPointValue, totalBonusIntelligence, playerData.getPlayerName());

        return totalBonusIntelligence;
    }

    public static float getPlayerIntelligence(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            // Replace with correct mana/intelligence stat index if available
            int intelligenceIndex = DefaultEntityStatTypes.getMana();
            EntityStatValue value = statMap.get(intelligenceIndex);
            if (value != null) {
                return value.get();
            }
        }
        return 0.0F;
    }

    public void applyIntelligenceModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        EntityStatMap statMap = componentAccessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            // Replace with correct mana/intelligence stat index if available
            int intelligenceIndex = DefaultEntityStatTypes.getMana();
            float modifierAmount = calculatePlayerIntelligence(playerData);
            String key = "SKILL_BONUS_INTELLIGENCE";
            try {
                StaticModifier modifier = new StaticModifier(
                        ModifierTarget.MAX,
                        CalculationType.ADDITIVE,
                        modifierAmount);
                statMap.putModifier(intelligenceIndex, key, modifier);
                LOGGER.atInfo().log(
                        "applyIntelligenceModifiers: Applied StaticModifier to max intelligence: +%.2f for entity %s",
                        modifierAmount, ref);
            } catch (Exception e) {
                LOGGER.atSevere().log("applyIntelligenceModifiers: Failed to apply StaticModifier: %s", e.getMessage());
            }
        } else {
            LOGGER.atWarning().log("applyIntelligenceModifiers: No EntityStatMap found for entity %s", ref);
        }
    }

    // Strength / skill modifiers
    public float calculatePlayerStrength(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int strengthLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STRENGTH);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.STRENGTH);

        float totalBonusStrength = (float) (strengthLevel * perPointValue);

        LOGGER.atInfo().log(
                "calculatePlayerStrength: STRENGTH level=%d, perPointValue=%.2f, totalBonusStrength=%.2f for player %s",
                strengthLevel, perPointValue, totalBonusStrength, playerData.getPlayerName());

        return totalBonusStrength;
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

    /**
     * Returns the player's precision crit chance as a float between 0.0 and 1.0.
     */
    public float calculatePlayerPrecision(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int precisionLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION);
        LOGGER.atInfo().log("calculatePlayerPrecision: precisionLevel=%d for player %s", precisionLevel,
                playerData.getPlayerName());
        double perPointChance = getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
        LOGGER.atInfo().log("calculatePlayerPrecision: perPointChance=%.4f for player %s", perPointChance,
                playerData.getPlayerName());
        float critChance = (float) (precisionLevel * perPointChance / 100.0F);
        if (critChance > 1.0F)
            critChance = 1.0F;
        LOGGER.atInfo().log(
                "getPrecisionCritChance: PRECISION level=%d, perPointChance=%.4f, critChance=%.4f for player %s",
                precisionLevel, perPointChance, critChance, playerData.getPlayerName());
        return critChance;
    }

    /**
     * Returns the player's ferocity stat for critical damage calculations.
     */
    public float calculatePlayerFerocity(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        float baseFerocityDamage = 50.0F;
        int ferocityLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY);
        double perPointFerocity = getSkillAttributeConfigValue(SkillAttributeType.FEROCITY); // e.g., 1.0 per point
        float ferocity = baseFerocityDamage + (float) (ferocityLevel * perPointFerocity); // Base 50 + bonus
        LOGGER.atInfo().log(
                "getPlayerFerocity: FEROCITY level=%d, perPointFerocity=%.4f, ferocity=%.4f for player %s (includes base 50)",
                ferocityLevel, perPointFerocity, ferocity, playerData.getPlayerName());
        return ferocity;
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

    // Defense / skill modifiers
    public float calculatePlayerDefense(PlayerData playerData) {
        if (playerData == null)
            return 0.0F;

        int defenseLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE);
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.DEFENSE);
        double defenseValue = defenseLevel * perPointValue;
        double reduction = applyDefenseCurve(defenseValue);
        float resistance = (float) (reduction / 100.0);

        LOGGER.atInfo().log(
                "calculatePlayerDefense: DEFENSE level=%d, perPointValue=%.2f, defenseValue=%.2f for player %s",
                defenseLevel, perPointValue, defenseValue, playerData.getPlayerName());

        LOGGER.atInfo().log("calculatePlayerDefense: Applied curve - reduction=%.2f%%, defenseValue=%.4f for player %s",
                reduction, defenseValue, playerData.getPlayerName());

        return resistance;
    }

    public float calculateDefenseResistanceForLevel(int defenseLevel) {
        double perPointValue = getSkillAttributeConfigValue(SkillAttributeType.DEFENSE);
        double defenseValue = defenseLevel * perPointValue;
        double reduction = applyDefenseCurve(defenseValue);
        return (float) (reduction / 100.0);
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

    /**
     * Apply all skill-based modifiers to an entity (health, stamina, etc.)
     */
    public void applyAllSkillModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor, PlayerData playerData) {
        applyHealthModifiers(ref, componentAccessor, playerData);
        applyStaminaModifiers(ref, componentAccessor, playerData);
        applyMovementSpeedModifier(ref, componentAccessor, playerData);
        applyIntelligenceModifiers(ref, componentAccessor, playerData);
        // Add more as needed (e.g., strength, defense, etc.)
        LOGGER.atInfo().log("applyAllSkillModifiers: Applied all skill modifiers for player %s",
                playerData.getPlayerName());
    }
}
