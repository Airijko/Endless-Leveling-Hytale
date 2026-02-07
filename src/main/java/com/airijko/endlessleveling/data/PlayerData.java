package com.airijko.endlessleveling.data;

import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single player's data: XP, level, skill points, and attributes.
 */
public class PlayerData {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public static final String DEFAULT_RACE_ID = "Human";

    private final UUID uuid;
    private final String playerName;

    private double xp;
    private int level;
    private int skillPoints;

    private final Map<SkillAttributeType, Integer> attributes;
    private final Map<PassiveType, Integer> passiveLevels;
    private String raceId;

    private boolean playerHudEnabled;
    private boolean criticalNotifEnabled;
    private boolean xpNotifEnabled;
    private boolean passiveLevelUpNotifEnabled;
    private boolean luckDoubleDropsNotifEnabled;
    private boolean healthRegenNotifEnabled;

    public PlayerData(UUID uuid, String playerName) {
        this(uuid, playerName, 0);
    }

    public PlayerData(UUID uuid, String playerName, int startingSkillPoints) {
        this.uuid = uuid;
        this.playerName = playerName;

        this.xp = 0.0;
        this.level = 1;
        this.skillPoints = Math.max(0, startingSkillPoints);
        this.playerHudEnabled = true;
        this.criticalNotifEnabled = true;
        this.xpNotifEnabled = true;
        this.passiveLevelUpNotifEnabled = true;
        this.luckDoubleDropsNotifEnabled = true;
        this.healthRegenNotifEnabled = true;
        this.raceId = DEFAULT_RACE_ID;

        this.attributes = new EnumMap<>(SkillAttributeType.class);
        for (SkillAttributeType type : SkillAttributeType.values()) {
            this.attributes.put(type, 0);
        }

        this.passiveLevels = new EnumMap<>(PassiveType.class);
        for (PassiveType passiveType : PassiveType.values()) {
            this.passiveLevels.put(passiveType, 0);
        }

        LOGGER.atInfo().log("PlayerData created for player: %s (UUID: %s)", playerName, uuid);
    }

    // --- Basic getters/setters ---
    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public double getXp() {
        return xp;
    }

    public void setXp(double xp) {
        LOGGER.atFine().log("Setting XP for %s (UUID: %s) to %f", playerName, uuid, xp);
        this.xp = xp;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        LOGGER.atFine().log("Setting Level for %s (UUID: %s) to %d", playerName, uuid, level);
        this.level = level;
    }

    public int getSkillPoints() {
        return skillPoints;
    }

    public void setSkillPoints(int skillPoints) {
        LOGGER.atFine().log("Setting SkillPoints for %s (UUID: %s) to %d", playerName, uuid, skillPoints);
        this.skillPoints = skillPoints;
    }

    public boolean isPlayerHudEnabled() {
        return playerHudEnabled;
    }

    public void setPlayerHudEnabled(boolean playerHudEnabled) {
        this.playerHudEnabled = playerHudEnabled;
    }

    public boolean isCriticalNotifEnabled() {
        return criticalNotifEnabled;
    }

    public void setCriticalNotifEnabled(boolean criticalNotifEnabled) {
        this.criticalNotifEnabled = criticalNotifEnabled;
    }

    public boolean isXpNotifEnabled() {
        return xpNotifEnabled;
    }

    public void setXpNotifEnabled(boolean xpNotifEnabled) {
        this.xpNotifEnabled = xpNotifEnabled;
    }

    public boolean isPassiveLevelUpNotifEnabled() {
        return passiveLevelUpNotifEnabled;
    }

    public void setPassiveLevelUpNotifEnabled(boolean passiveLevelUpNotifEnabled) {
        this.passiveLevelUpNotifEnabled = passiveLevelUpNotifEnabled;
    }

    public boolean isLuckDoubleDropsNotifEnabled() {
        return luckDoubleDropsNotifEnabled;
    }

    public void setLuckDoubleDropsNotifEnabled(boolean luckDoubleDropsNotifEnabled) {
        this.luckDoubleDropsNotifEnabled = luckDoubleDropsNotifEnabled;
    }

    public boolean isHealthRegenNotifEnabled() {
        return healthRegenNotifEnabled;
    }

    public void setHealthRegenNotifEnabled(boolean healthRegenNotifEnabled) {
        this.healthRegenNotifEnabled = healthRegenNotifEnabled;
    }

    // --- Skill Level handling ---
    public int getPlayerSkillAttributeLevel(SkillAttributeType type) {
        return attributes.getOrDefault(type, 0);
    }

    public void setPlayerSkillAttributeLevel(SkillAttributeType type, int value) {
        LOGGER.atFine().log("Setting attribute %s for %s (UUID: %s) to %d", type, playerName, uuid, value);
        attributes.put(type, value);
    }

    public Map<SkillAttributeType, Integer> getAttributes() {
        return attributes;
    }

    public int getPassiveLevel(PassiveType type) {
        return passiveLevels.getOrDefault(type, 0);
    }

    public void setPassiveLevel(PassiveType type, int level) {
        passiveLevels.put(type, Math.max(0, level));
    }

    public Map<PassiveType, Integer> getPassiveLevelsSnapshot() {
        return Collections.unmodifiableMap(passiveLevels);
    }

    public String getRaceId() {
        return raceId;
    }

    public void setRaceId(String raceId) {
        if (raceId == null || raceId.isBlank()) {
            this.raceId = DEFAULT_RACE_ID;
            return;
        }
        this.raceId = raceId.trim();
    }

    /**
     * Increase a skill attribute if the player has skill points.
     * Returns true if successful, false if not enough points.
     */
    public boolean increaseAttribute(SkillAttributeType type) {
        if (skillPoints > 0) {
            int newValue = attributes.get(type) + 1;
            attributes.put(type, newValue);
            skillPoints--;
            LOGGER.atInfo().log("Increased attribute %s for %s (UUID: %s) to %d. Remaining skill points: %d",
                    type, playerName, uuid, newValue, skillPoints);
            return true;
        } else {
            LOGGER.atWarning().log("Failed to increase attribute %s for %s (UUID: %s) - not enough skill points",
                    type, playerName, uuid);
            return false;
        }
    }
}
