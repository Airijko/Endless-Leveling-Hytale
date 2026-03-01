package com.airijko.endlessleveling.data;

import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a single player's data: XP, level, skill points, and attributes.
 */
public class PlayerData {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public static final String DEFAULT_RACE_ID = "Human";
    public static final String DEFAULT_PRIMARY_CLASS_ID = "Adventurer";
    public static final String DEFAULT_LANGUAGE = "en_US";
    public static final int MAX_PROFILES = 5;
    public static final int MAX_PROFILE_NAME_LENGTH = 32;

    private final UUID uuid;
    private final String playerName;
    private final int baseSkillPoints;

    private final Map<Integer, PlayerProfile> profiles;
    private int activeProfileIndex;

    private boolean playerHudEnabled;
    private boolean criticalNotifEnabled;
    private boolean xpNotifEnabled;
    private boolean passiveLevelUpNotifEnabled;
    private boolean luckDoubleDropsNotifEnabled;
    private boolean healthRegenNotifEnabled;
    private boolean useRaceModel;
    private String language;

    public PlayerData(UUID uuid, String playerName) {
        this(uuid, playerName, 0);
    }

    public PlayerData(UUID uuid, String playerName, int startingSkillPoints) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.baseSkillPoints = Math.max(0, startingSkillPoints);
        this.profiles = new LinkedHashMap<>();
        this.activeProfileIndex = 1;
        this.profiles.put(1, PlayerProfile.fresh(this.baseSkillPoints, defaultProfileName(1)));
        this.playerHudEnabled = true;
        this.criticalNotifEnabled = true;
        this.xpNotifEnabled = true;
        this.passiveLevelUpNotifEnabled = true;
        this.luckDoubleDropsNotifEnabled = true;
        this.healthRegenNotifEnabled = true;
        this.useRaceModel = false;
        this.language = DEFAULT_LANGUAGE;
        LOGGER.atInfo().log("PlayerData created for player: %s (UUID: %s) with profile slot 1", playerName, uuid);
    }

    private PlayerProfile getActiveProfile() {
        PlayerProfile profile = profiles.get(activeProfileIndex);
        if (profile == null) {
            profile = ensureProfile(activeProfileIndex);
        }
        return profile;
    }

    private PlayerProfile ensureProfile(int index) {
        if (!isValidProfileIndex(index)) {
            return null;
        }
        return profiles.computeIfAbsent(index,
                key -> PlayerProfile.fresh(baseSkillPoints, defaultProfileName(key)));
    }

    public Map<Integer, PlayerProfile> getProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    public boolean hasProfile(int index) {
        return profiles.containsKey(index);
    }

    public int getActiveProfileIndex() {
        return activeProfileIndex;
    }

    public boolean isProfileActive(int index) {
        return this.activeProfileIndex == index;
    }

    public int getProfileCount() {
        return profiles.size();
    }

    /**
     * Finds the lowest unused profile slot index, or -1 if all slots are taken.
     */
    public int findNextAvailableProfileSlot() {
        for (int slot = 1; slot <= MAX_PROFILES; slot++) {
            if (!profiles.containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    public String getProfileName(int index) {
        PlayerProfile profile = profiles.get(index);
        if (profile == null) {
            return defaultProfileName(index);
        }
        return profile.getName();
    }

    public boolean renameProfile(int index, String newName) {
        if (!isValidProfileIndex(index)) {
            return false;
        }
        PlayerProfile profile = profiles.get(index);
        if (profile == null) {
            return false;
        }
        String normalized = normalizeProfileName(newName, index);
        profile.setName(normalized);
        LOGGER.atInfo().log("Player %s renamed profile slot %d to %s", playerName, index, normalized);
        return true;
    }

    public boolean createProfile(int index, String name, boolean overwriteExisting, boolean switchToNew) {
        if (!isValidProfileIndex(index)) {
            return false;
        }
        if (!overwriteExisting && profiles.containsKey(index)) {
            return false;
        }
        String normalizedName = normalizeProfileName(name, index);
        profiles.put(index, PlayerProfile.fresh(baseSkillPoints, normalizedName));
        if (switchToNew) {
            this.activeProfileIndex = index;
        }
        LOGGER.atInfo().log("Player %s created profile slot %d (active=%s)",
                playerName, index, switchToNew);
        return true;
    }

    public boolean deleteProfile(int index) {
        if (!isValidProfileIndex(index)) {
            return false;
        }
        if (!profiles.containsKey(index)) {
            return false;
        }
        if (profiles.size() <= 1) {
            return false;
        }

        profiles.remove(index);
        if (this.activeProfileIndex == index) {
            this.activeProfileIndex = this.profiles.keySet().stream().min(Integer::compareTo).orElse(1);
        }
        LOGGER.atInfo().log("Player %s deleted profile slot %d", playerName, index);
        return true;
    }

    public ProfileSwitchResult switchProfile(int index) {
        if (!isValidProfileIndex(index)) {
            return ProfileSwitchResult.INVALID_INDEX;
        }

        if (!profiles.containsKey(index)) {
            return ProfileSwitchResult.MISSING_PROFILE;
        }

        if (this.activeProfileIndex == index) {
            return ProfileSwitchResult.ALREADY_ACTIVE;
        }

        this.activeProfileIndex = index;
        LOGGER.atInfo().log("Player %s switched to profile slot %d", playerName, index);
        return ProfileSwitchResult.SWITCHED_EXISTING;
    }

    public void loadProfilesFromStorage(Map<Integer, PlayerProfile> loadedProfiles, int requestedActiveIndex) {
        this.profiles.clear();
        if (loadedProfiles != null) {
            loadedProfiles.entrySet().stream()
                    .filter(entry -> isValidProfileIndex(entry.getKey()))
                    .limit(MAX_PROFILES)
                    .forEach(entry -> this.profiles.put(entry.getKey(),
                            entry.getValue() != null
                                    ? ensureProfileName(entry.getKey(), entry.getValue())
                                    : PlayerProfile.fresh(baseSkillPoints, defaultProfileName(entry.getKey()))));
        }

        if (this.profiles.isEmpty()) {
            this.activeProfileIndex = 1;
            this.profiles.put(1, PlayerProfile.fresh(baseSkillPoints, defaultProfileName(1)));
            return;
        }

        if (!this.profiles.containsKey(requestedActiveIndex)) {
            this.activeProfileIndex = this.profiles.keySet().stream().min(Integer::compareTo).orElse(1);
        } else {
            this.activeProfileIndex = requestedActiveIndex;
        }
    }

    public int getBaseSkillPoints() {
        return baseSkillPoints;
    }

    public static boolean isValidProfileIndex(int index) {
        return index >= 1 && index <= MAX_PROFILES;
    }

    public static String defaultProfileName(int slot) {
        return "Profile " + slot;
    }

    public static String normalizeProfileName(String rawName, int slot) {
        String fallback = defaultProfileName(slot);
        if (rawName == null) {
            return fallback;
        }
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        if (trimmed.length() > MAX_PROFILE_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_PROFILE_NAME_LENGTH);
        }
        return trimmed;
    }

    private PlayerProfile ensureProfileName(int index, PlayerProfile profile) {
        if (profile == null) {
            return PlayerProfile.fresh(baseSkillPoints, defaultProfileName(index));
        }
        profile.setName(normalizeProfileName(profile.getName(), index));
        return profile;
    }

    // --- Basic getters/setters ---
    public UUID getUuid() {
        return uuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public double getXp() {
        return getActiveProfile().getXp();
    }

    public void setXp(double xp) {
        LOGGER.atFine().log("Setting XP for %s (UUID: %s) profile %d to %f", playerName, uuid,
                activeProfileIndex, xp);
        getActiveProfile().setXp(xp);
    }

    public int getLevel() {
        return getActiveProfile().getLevel();
    }

    public void setLevel(int level) {
        LOGGER.atFine().log("Setting Level for %s (UUID: %s) profile %d to %d", playerName, uuid,
                activeProfileIndex, level);
        getActiveProfile().setLevel(level);
    }

    public int getSkillPoints() {
        return getActiveProfile().getSkillPoints();
    }

    public void setSkillPoints(int skillPoints) {
        LOGGER.atFine().log("Setting SkillPoints for %s (UUID: %s) profile %d to %d",
                playerName, uuid, activeProfileIndex, skillPoints);
        getActiveProfile().setSkillPoints(skillPoints);
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

    public boolean isUseRaceModel() {
        return useRaceModel;
    }

    public void setUseRaceModel(boolean useRaceModel) {
        this.useRaceModel = useRaceModel;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        if (language == null || language.isBlank()) {
            this.language = DEFAULT_LANGUAGE;
            return;
        }

        String normalized = language.trim().replace('-', '_');
        String[] parts = normalized.split("_");
        if (parts.length == 1) {
            this.language = parts[0].toLowerCase(Locale.ROOT);
            return;
        }

        this.language = parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }

    // --- Skill Level handling ---
    public int getPlayerSkillAttributeLevel(SkillAttributeType type) {
        return getActiveProfile().getAttributes().getOrDefault(type, 0);
    }

    public void setPlayerSkillAttributeLevel(SkillAttributeType type, int value) {
        LOGGER.atFine().log("Setting attribute %s for %s (UUID: %s) profile %d to %d",
                type, playerName, uuid, activeProfileIndex, value);
        getActiveProfile().getAttributes().put(type, value);
    }

    public Map<SkillAttributeType, Integer> getAttributes() {
        return getActiveProfile().getAttributes();
    }

    public Map<String, List<String>> getAugmentOffersSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(getActiveProfile().getAugmentOffers()));
    }

    public List<String> getAugmentOffersForTier(String tierKey) {
        return getActiveProfile().getAugmentOffers(tierKey);
    }

    public void setAugmentOffersForTier(String tierKey, List<String> offers) {
        getActiveProfile().setAugmentOffers(tierKey, offers);
    }

    public void clearAugmentOffers() {
        getActiveProfile().clearAugmentOffers();
    }

    public String getSelectedAugmentForTier(String tierKey) {
        return getActiveProfile().getSelectedAugment(tierKey);
    }

    public void setSelectedAugmentForTier(String tierKey, String augmentId) {
        getActiveProfile().setSelectedAugment(tierKey, augmentId);
    }

    public Map<String, String> getSelectedAugmentsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(getActiveProfile().getSelectedAugments()));
    }

    public void clearSelectedAugments() {
        getActiveProfile().clearSelectedAugments();
    }

    public int getPassiveLevel(PassiveType type) {
        return getActiveProfile().getPassiveLevel(type);
    }

    public void setPassiveLevel(PassiveType type, int level) {
        getActiveProfile().setPassiveLevel(type, level);
    }

    public Map<PassiveType, Integer> getPassiveLevelsSnapshot() {
        return Collections.unmodifiableMap(getActiveProfile().getPassiveLevels());
    }

    public String getRaceId() {
        return getActiveProfile().getRaceId();
    }

    public void setRaceId(String raceId) {
        getActiveProfile().setRaceId(raceId);
    }

    public long getLastRaceChangeEpochSeconds() {
        return getActiveProfile().getLastRaceChangeEpochSeconds();
    }

    public void setLastRaceChangeEpochSeconds(long epochSeconds) {
        getActiveProfile().setLastRaceChangeEpochSeconds(epochSeconds);
    }

    public int getRaceSwitchCount() {
        return getActiveProfile().getRaceSwitchCount();
    }

    public void setRaceSwitchCount(int count) {
        getActiveProfile().setRaceSwitchCount(count);
    }

    public void incrementRaceSwitchCount() {
        getActiveProfile().incrementRaceSwitchCount();
    }

    public String getPrimaryClassId() {
        return getActiveProfile().getPrimaryClassId();
    }

    public void setPrimaryClassId(String classId) {
        getActiveProfile().setPrimaryClassId(classId);
    }

    public String getSecondaryClassId() {
        return getActiveProfile().getSecondaryClassId();
    }

    public void setSecondaryClassId(String classId) {
        getActiveProfile().setSecondaryClassId(classId);
    }

    public long getLastPrimaryClassChangeEpochSeconds() {
        return getActiveProfile().getLastPrimaryClassChangeEpochSeconds();
    }

    public void setLastPrimaryClassChangeEpochSeconds(long epochSeconds) {
        getActiveProfile().setLastPrimaryClassChangeEpochSeconds(epochSeconds);
    }

    public long getLastSecondaryClassChangeEpochSeconds() {
        return getActiveProfile().getLastSecondaryClassChangeEpochSeconds();
    }

    public void setLastSecondaryClassChangeEpochSeconds(long epochSeconds) {
        getActiveProfile().setLastSecondaryClassChangeEpochSeconds(epochSeconds);
    }

    public int getClassSwitchCount() {
        return getActiveProfile().getClassSwitchCount();
    }

    public void setClassSwitchCount(int count) {
        getActiveProfile().setClassSwitchCount(count);
    }

    public void incrementClassSwitchCount() {
        getActiveProfile().incrementClassSwitchCount();
    }

    /**
     * Increase a skill attribute if the player has skill points.
     * Returns true if successful, false if not enough points.
     */
    public boolean increaseAttribute(SkillAttributeType type) {
        PlayerProfile profile = getActiveProfile();
        if (profile.getSkillPoints() > 0) {
            int newValue = profile.getAttributes().get(type) + 1;
            profile.getAttributes().put(type, newValue);
            profile.setSkillPoints(profile.getSkillPoints() - 1);
            LOGGER.atInfo().log(
                    "Increased attribute %s for %s (UUID: %s) profile %d to %d. Remaining skill points: %d",
                    type, playerName, uuid, activeProfileIndex, newValue, profile.getSkillPoints());
            return true;
        }

        LOGGER.atWarning().log(
                "Failed to increase attribute %s for %s (UUID: %s) profile %d - not enough skill points",
                type, playerName, uuid, activeProfileIndex);
        return false;
    }

    public enum ProfileSwitchResult {
        INVALID_INDEX,
        MISSING_PROFILE,
        ALREADY_ACTIVE,
        SWITCHED_EXISTING
    }

    public static final class PlayerProfile {
        private double xp;
        private int level;
        private int skillPoints;
        private final Map<SkillAttributeType, Integer> attributes;
        private final Map<PassiveType, Integer> passiveLevels;
        private final Map<String, List<String>> augmentOffers;
        private final Map<String, String> selectedAugments;
        private String raceId;
        private long lastRaceChangeEpochSeconds;
        private int raceSwitchCount;
        private String primaryClassId;
        private String secondaryClassId;
        private long lastPrimaryClassChangeEpochSeconds;
        private long lastSecondaryClassChangeEpochSeconds;
        private int classSwitchCount;

        private String name;

        private PlayerProfile(int startingSkillPoints, String name) {
            this.xp = 0.0;
            this.level = 1;
            this.skillPoints = Math.max(0, startingSkillPoints);
            this.attributes = new EnumMap<>(SkillAttributeType.class);
            for (SkillAttributeType type : SkillAttributeType.values()) {
                this.attributes.put(type, 0);
            }
            this.passiveLevels = new EnumMap<>(PassiveType.class);
            for (PassiveType passiveType : PassiveType.values()) {
                this.passiveLevels.put(passiveType, 0);
            }
            this.augmentOffers = new LinkedHashMap<>();
            this.selectedAugments = new LinkedHashMap<>();
            this.raceId = null;
            this.lastRaceChangeEpochSeconds = 0L;
            this.raceSwitchCount = 0;
            this.primaryClassId = null;
            this.secondaryClassId = null;
            this.lastPrimaryClassChangeEpochSeconds = 0L;
            this.lastSecondaryClassChangeEpochSeconds = 0L;
            this.classSwitchCount = 0;
            this.name = (name == null || name.isBlank()) ? "Profile" : name;
        }

        public static PlayerProfile fresh(int startingSkillPoints, String name) {
            return new PlayerProfile(startingSkillPoints, name);
        }

        public double getXp() {
            return xp;
        }

        public void setXp(double xp) {
            this.xp = xp;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = Math.max(1, level);
        }

        public int getSkillPoints() {
            return skillPoints;
        }

        public void setSkillPoints(int skillPoints) {
            this.skillPoints = Math.max(0, skillPoints);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public Map<PassiveType, Integer> getPassiveLevels() {
            return passiveLevels;
        }

        public Map<String, List<String>> getAugmentOffers() {
            return augmentOffers;
        }

        public Map<String, String> getSelectedAugments() {
            return selectedAugments;
        }

        public List<String> getAugmentOffers(String tierKey) {
            if (tierKey == null || tierKey.isBlank()) {
                return List.of();
            }
            List<String> offers = augmentOffers.get(normalizeTierKey(tierKey));
            return offers == null ? List.of() : List.copyOf(offers);
        }

        public void setAugmentOffers(String tierKey, List<String> offers) {
            String key = normalizeTierKey(tierKey);
            if (key == null) {
                return;
            }
            if (offers == null || offers.isEmpty()) {
                augmentOffers.remove(key);
                return;
            }
            augmentOffers.put(key, new ArrayList<>(offers));
        }

        public void clearAugmentOffers() {
            augmentOffers.clear();
        }

        public String getSelectedAugment(String tierKey) {
            if (tierKey == null || tierKey.isBlank()) {
                return null;
            }
            return selectedAugments.get(normalizeTierKey(tierKey));
        }

        public void setSelectedAugment(String tierKey, String augmentId) {
            String key = normalizeTierKey(tierKey);
            if (key == null) {
                return;
            }
            if (augmentId == null || augmentId.isBlank()) {
                selectedAugments.remove(key);
                return;
            }
            selectedAugments.put(key, augmentId.trim());
        }

        public void clearSelectedAugments() {
            selectedAugments.clear();
        }

        private String normalizeTierKey(String tierKey) {
            if (tierKey == null) {
                return null;
            }
            String trimmed = tierKey.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return trimmed.toUpperCase(Locale.ROOT);
        }

        public String getRaceId() {
            return raceId;
        }

        public void setRaceId(String raceId) {
            if (raceId == null || raceId.isBlank()) {
                this.raceId = null;
                return;
            }
            this.raceId = raceId.trim();
        }

        public long getLastRaceChangeEpochSeconds() {
            return lastRaceChangeEpochSeconds;
        }

        public void setLastRaceChangeEpochSeconds(long epochSeconds) {
            this.lastRaceChangeEpochSeconds = Math.max(0L, epochSeconds);
        }

        public int getRaceSwitchCount() {
            return raceSwitchCount;
        }

        public void setRaceSwitchCount(int count) {
            this.raceSwitchCount = Math.max(0, count);
        }

        public void incrementRaceSwitchCount() {
            this.raceSwitchCount = Math.max(0, this.raceSwitchCount + 1);
        }

        public String getPrimaryClassId() {
            return primaryClassId;
        }

        public void setPrimaryClassId(String classId) {
            if (classId == null || classId.isBlank()) {
                this.primaryClassId = null;
                return;
            }
            this.primaryClassId = classId.trim();
            if (secondaryClassId != null && this.primaryClassId != null
                    && secondaryClassId.equalsIgnoreCase(this.primaryClassId)) {
                this.secondaryClassId = null;
            }
        }

        public String getSecondaryClassId() {
            return secondaryClassId;
        }

        public void setSecondaryClassId(String classId) {
            if (classId == null || classId.isBlank()) {
                this.secondaryClassId = null;
                return;
            }
            String trimmed = classId.trim();
            if (primaryClassId != null && trimmed.equalsIgnoreCase(primaryClassId)) {
                this.secondaryClassId = null;
                return;
            }
            this.secondaryClassId = trimmed;
        }

        public long getLastPrimaryClassChangeEpochSeconds() {
            return lastPrimaryClassChangeEpochSeconds;
        }

        public void setLastPrimaryClassChangeEpochSeconds(long epochSeconds) {
            this.lastPrimaryClassChangeEpochSeconds = Math.max(0L, epochSeconds);
        }

        public long getLastSecondaryClassChangeEpochSeconds() {
            return lastSecondaryClassChangeEpochSeconds;
        }

        public void setLastSecondaryClassChangeEpochSeconds(long epochSeconds) {
            this.lastSecondaryClassChangeEpochSeconds = Math.max(0L, epochSeconds);
        }

        public int getClassSwitchCount() {
            return classSwitchCount;
        }

        public void setClassSwitchCount(int count) {
            this.classSwitchCount = Math.max(0, count);
        }

        public void incrementClassSwitchCount() {
            this.classSwitchCount = Math.max(0, this.classSwitchCount + 1);
        }
    }
}
