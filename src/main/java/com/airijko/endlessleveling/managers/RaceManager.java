package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceAscensionPathLink;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.passives.util.PassiveDefinitionParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import org.yaml.snakeyaml.Yaml;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class RaceManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT = 10;
    private static final int SWAP_CONSUME_COUNT = 1;
    private final ConcurrentHashMap<UUID, Long> modelApplyTimestamps = new ConcurrentHashMap<>();

    private final PluginFilesManager filesManager;
    private final ConfigManager configManager;
    private final boolean racesEnabled;
    private final boolean forceBuiltinRaces;
    private final Map<String, RaceDefinition> racesByKey = new HashMap<>();
    private final Map<String, RaceDefinition> racesByAscensionId = new HashMap<>();
    private final Map<String, List<String>> ascensionParentsByChild = new HashMap<>();
    private final Yaml yaml = new Yaml();
    private final RaceModelDefaultMode raceModelDefaultMode;
    private final int maxRaceSwitches;
    private final Map<UUID, Boolean> modelApplyGuard = new ConcurrentHashMap<>();

    private String defaultRaceId = PlayerData.DEFAULT_RACE_ID;
    private boolean hasConfiguredDefaultRace = true;
    private final long chooseRaceCooldownSeconds;

    public RaceManager(ConfigManager configManager, PluginFilesManager filesManager) {
        Objects.requireNonNull(configManager, "ConfigManager is required");
        this.filesManager = Objects.requireNonNull(filesManager, "PluginFilesManager is required");
        this.configManager = configManager;
        this.racesEnabled = parseBoolean(configManager.get("enable_races", Boolean.TRUE, false), true);
        this.forceBuiltinRaces = parseBoolean(configManager.get("force_builtin_races", Boolean.FALSE, false),
                false);
        Object raceModelDefaultConfig = configManager.get("global_race_visuals_setting", "off", false);
        this.raceModelDefaultMode = parseRaceModelDefault(raceModelDefaultConfig);
        Object defaultRaceConfig = configManager.get("default_race", PlayerData.DEFAULT_RACE_ID, false);
        this.maxRaceSwitches = parseMaxSwitches(configManager.get("race_max_switches", -1, false));
        if (isNoneLiteral(defaultRaceConfig)) {
            this.defaultRaceId = null;
            this.hasConfiguredDefaultRace = false;
        } else {
            String configuredDefault = parseConfiguredDefaultIdentifier(defaultRaceConfig);
            if (configuredDefault != null) {
                this.defaultRaceId = configuredDefault;
                this.hasConfiguredDefaultRace = true;
            }
        }
        Object cooldownConfig = configManager.get("choose_race_cooldown", 0, false);
        this.chooseRaceCooldownSeconds = parseCooldownSeconds(cooldownConfig);

        if (!racesEnabled) {
            LOGGER.atInfo().log("Race system disabled via config.yml (enable_races=false).");
            return;
        }

        syncBuiltinRacesIfNeeded();
        loadRaces();
    }

    private void reloadDefaultsFromConfig() {
        if (configManager == null) {
            return;
        }
        Object configuredNode = configManager.get("default_race", defaultRaceId, false);
        if (isNoneLiteral(configuredNode)) {
            defaultRaceId = null;
            hasConfiguredDefaultRace = false;
            return;
        }
        String configuredDefault = parseConfiguredDefaultIdentifier(configuredNode);
        if (configuredDefault != null) {
            this.defaultRaceId = configuredDefault;
            this.hasConfiguredDefaultRace = true;
        }
    }

    /** Reload race definitions and refresh defaults from config.yml. */
    public synchronized void reload() {
        if (!racesEnabled) {
            LOGGER.atInfo().log("Race system is disabled; skipping reload.");
            return;
        }

        reloadDefaultsFromConfig();
        racesByKey.clear();
        racesByAscensionId.clear();
        ascensionParentsByChild.clear();
        syncBuiltinRacesIfNeeded();
        loadRaces();
    }

    public boolean isEnabled() {
        return racesEnabled && !racesByKey.isEmpty();
    }

    public RaceDefinition getRace(String raceId) {
        if (raceId == null) {
            return null;
        }
        return racesByKey.get(normalizeKey(raceId));
    }

    public RaceDefinition getDefaultRace() {
        if (!hasConfiguredDefaultRace) {
            return null;
        }
        if (defaultRaceId == null || defaultRaceId.isBlank()) {
            return null;
        }
        RaceDefinition configuredDefault = racesByKey.get(normalizeKey(defaultRaceId));
        if (configuredDefault != null) {
            return configuredDefault;
        }
        return racesByKey.values().stream().findFirst().orElse(null);
    }

    public long getChooseRaceCooldownSeconds() {
        return Math.max(0L, chooseRaceCooldownSeconds);
    }

    public String getDefaultRaceId() {
        RaceDefinition defaultRace = getDefaultRace();
        return defaultRace != null ? defaultRace.getId() : null;
    }

    public boolean hasConfiguredDefaultRace() {
        return hasConfiguredDefaultRace;
    }

    public RaceModelDefaultMode getRaceModelDefaultMode() {
        return raceModelDefaultMode;
    }

    public boolean isRaceModelDefaultEnabled() {
        return raceModelDefaultMode.isEnabledByDefault();
    }

    public int getMaxRaceSwitches() {
        return maxRaceSwitches;
    }

    public boolean hasRaceSwitchesRemaining(PlayerData data) {
        if (data == null)
            return false;
        if (maxRaceSwitches < 0)
            return true;
        applyLevelThresholdSwapConsumption(data);
        return data.getRemainingRaceSwitches() > 0;
    }

    public int getRemainingRaceSwitches(PlayerData data) {
        if (maxRaceSwitches < 0 || data == null)
            return Integer.MAX_VALUE;
        applyLevelThresholdSwapConsumption(data);
        return Math.max(0, data.getRemainingRaceSwitches());
    }

    private void applyLevelThresholdSwapConsumption(PlayerData data) {
        if (data == null || maxRaceSwitches < 0) {
            return;
        }
        if (!isSwapAntiExploitEnabled()) {
            return;
        }
        if (data.getLevel() < getSwapConsumeLevelThreshold()) {
            return;
        }

        int consumeFloor = Math.max(0, maxRaceSwitches - SWAP_CONSUME_COUNT);
        if (hasAssignedRace(data) && data.getRemainingRaceSwitches() > consumeFloor) {
            data.setRemainingRaceSwitches(consumeFloor);
        }

        // If race is unassigned (None) and swaps are exhausted, grant exactly one
        // emergency swap to recover from the None state.
        grantEmergencyRaceSwapIfNoneAndExhausted(data);
    }

    private boolean hasAssignedRace(PlayerData data) {
        if (data == null) {
            return false;
        }
        String raceId = data.getRaceId();
        return raceId != null && !raceId.isBlank() && !"none".equalsIgnoreCase(raceId.trim());
    }

    private void grantEmergencyRaceSwapIfNoneAndExhausted(PlayerData data) {
        if (data == null || maxRaceSwitches <= 0) {
            return;
        }
        if (hasAssignedRace(data)) {
            return;
        }
        if (data.getRemainingRaceSwitches() > 0) {
            return;
        }

        data.setRemainingRaceSwitches(1);
        LOGGER.atInfo().log("Granted one emergency race swap because race is None and swaps were exhausted.");
    }

    private boolean isSwapAntiExploitEnabled() {
        Object raw = configManager.get("swap_anti_exploit.consume_at_level_enabled", Boolean.TRUE, false);
        return parseBoolean(raw, true);
    }

    private int getSwapConsumeLevelThreshold() {
        Object raw = configManager.get(
                "swap_anti_exploit.consume_at_level_threshold",
                SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT,
                false);
        if (raw instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (raw instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT;
            }
        }
        return SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT;
    }

    public boolean isRaceModelGloballyDisabled() {
        return raceModelDefaultMode.isGloballyDisabled();
    }

    public String resolveRaceId(String requestedId) {
        return resolveRaceIdentifier(requestedId);
    }

    public String resolveRaceIdentifier(String requestedValue) {
        if (!isEnabled()) {
            return null;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            if (!hasConfiguredDefaultRace) {
                return null;
            }
            RaceDefinition fallback = getDefaultRace();
            return fallback != null ? fallback.getId() : null;
        }

        RaceDefinition requested = findRaceByUserInput(requestedValue);
        if (requested != null) {
            return requested.getId();
        }

        RaceDefinition fallback = getDefaultRace();
        return fallback != null ? fallback.getId() : null;
    }

    public RaceDefinition getPlayerRace(PlayerData data) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolveRaceIdentifier(data.getRaceId());
        RaceDefinition resolved = getRace(resolvedId);
        if (resolved != null) {
            if (!resolved.getId().equals(data.getRaceId())) {
                data.setRaceId(resolved.getId());
            }
            return resolved;
        }

        RaceDefinition fallback = getDefaultRace();
        if (fallback != null) {
            if (!fallback.getId().equals(data.getRaceId())) {
                data.setRaceId(fallback.getId());
            }
            return fallback;
        }

        data.setRaceId(null);
        return null;
    }

    public RaceDefinition setPlayerRace(PlayerData data, String requestedValue) {
        return setPlayerRace(data, requestedValue, true);
    }

    public RaceDefinition setPlayerRaceSilently(PlayerData data, String requestedValue) {
        return setPlayerRace(data, requestedValue, false);
    }

    private RaceDefinition setPlayerRace(PlayerData data, String requestedValue, boolean applyModel) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolveRaceIdentifier(requestedValue);
        RaceDefinition resolved = getRace(resolvedId);
        if (resolved == null && hasConfiguredDefaultRace) {
            resolved = getDefaultRace();
        }
        if (resolved != null) {
            data.setRaceId(resolved.getId());
            if (applyModel) {
                applyRaceModelIfEnabled(data);
            }
        } else {
            data.setRaceId(null);
        }
        return resolved;
    }

    public void markRaceChange(PlayerData data) {
        if (data == null)
            return;
        applyLevelThresholdSwapConsumption(data);
        data.decrementRemainingRaceSwitches();
        data.setLastRaceChangeEpochSeconds(Instant.now().getEpochSecond());
    }

    public boolean isSwapAntiExploitConsumeEnabled() {
        return isSwapAntiExploitEnabled();
    }

    public int getSwapAntiExploitConsumeLevelThreshold() {
        return getSwapConsumeLevelThreshold();
    }

    public Collection<RaceDefinition> getLoadedRaces() {
        return Collections.unmodifiableCollection(racesByKey.values());
    }

    public String resolveAscensionPathId(String raceInput) {
        if (raceInput == null || raceInput.isBlank()) {
            return null;
        }
        RaceDefinition race = findRaceByUserInput(raceInput);
        if (race == null) {
            race = getRace(raceInput);
        }
        if (race != null) {
            return race.getAscension().getId();
        }
        return normalizeKey(raceInput);
    }

    public RaceAscensionDefinition getAscensionDefinition(String raceInput) {
        if (raceInput == null || raceInput.isBlank()) {
            return null;
        }
        RaceDefinition race = findRaceByUserInput(raceInput);
        if (race == null) {
            race = getRace(raceInput);
        }
        return race != null ? race.getAscension() : null;
    }

    public List<RaceDefinition> getNextAscensionRaces(String raceInput) {
        RaceDefinition sourceRace = findRaceByUserInput(raceInput);
        if (sourceRace == null) {
            sourceRace = getRace(raceInput);
        }
        if (sourceRace == null) {
            return Collections.emptyList();
        }
        List<RaceDefinition> results = new ArrayList<>();
        for (RaceAscensionPathLink link : sourceRace.getAscension().getNextPaths()) {
            RaceDefinition child = getRaceByAscensionPathId(link.getId());
            if (child != null) {
                results.add(child);
            }
        }
        return Collections.unmodifiableList(results);
    }

    public List<RaceDefinition> getEligibleNextAscensionRaces(PlayerData data) {
        if (data == null) {
            return Collections.emptyList();
        }
        RaceDefinition currentRace = getPlayerRace(data);
        if (currentRace == null) {
            return Collections.emptyList();
        }
        List<RaceDefinition> eligible = new ArrayList<>();
        for (RaceDefinition candidate : getNextAscensionRaces(currentRace.getId())) {
            RaceAscensionEligibility check = evaluateAscensionEligibility(data, currentRace.getId(),
                    candidate.getId(), true);
            if (check.isEligible()) {
                eligible.add(candidate);
            }
        }
        return Collections.unmodifiableList(eligible);
    }

    public RaceAscensionEligibility evaluateAscensionEligibility(PlayerData data, String targetRaceInput) {
        String sourceRace = data != null ? data.getRaceId() : null;
        return evaluateAscensionEligibility(data, sourceRace, targetRaceInput, true);
    }

    public RaceAscensionEligibility evaluateAscensionEligibility(PlayerData data,
            String sourceRaceInput,
            String targetRaceInput,
            boolean requireDirectPath) {
        List<String> blockers = new ArrayList<>();
        if (data == null) {
            blockers.add("Player data is unavailable.");
            return RaceAscensionEligibility.denied(blockers);
        }

        RaceDefinition targetRace = findRaceByUserInput(targetRaceInput);
        if (targetRace == null) {
            targetRace = getRace(targetRaceInput);
        }
        if (targetRace == null) {
            blockers.add("Target race was not found.");
            return RaceAscensionEligibility.denied(blockers);
        }

        RaceDefinition sourceRace = findRaceByUserInput(sourceRaceInput);
        if (sourceRace == null) {
            sourceRace = getRace(sourceRaceInput);
        }

        if (sourceRace != null && sourceRace.getId().equalsIgnoreCase(targetRace.getId())) {
            blockers.add("You are already in that race form.");
            return RaceAscensionEligibility.denied(blockers);
        }

        if (requireDirectPath && !isDirectAscensionTransition(sourceRace, targetRace)) {
            blockers.add("Target race is not in your current ascension path options.");
        }

        RaceAscensionRequirements requirements = targetRace.getAscension().getRequirements();
        if (data.getPrestigeLevel() < requirements.getRequiredPrestige()) {
            blockers.add("Requires prestige " + requirements.getRequiredPrestige() + ".");
        }

        for (Map.Entry<SkillAttributeType, Integer> requirement : requirements.getMinSkillLevels().entrySet()) {
            int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
            if (current < requirement.getValue()) {
                blockers.add("Requires " + requirement.getKey().getConfigKey() + " >= " + requirement.getValue());
            }
        }

        for (Map.Entry<SkillAttributeType, Integer> requirement : requirements.getMaxSkillLevels().entrySet()) {
            int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
            if (current > requirement.getValue()) {
                blockers.add("Requires " + requirement.getKey().getConfigKey() + " <= " + requirement.getValue());
            }
        }

        if (!requirements.getMinAnySkillLevels().isEmpty()) {
            boolean anySatisfied = false;
            for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                boolean groupSatisfied = true;
                for (Map.Entry<SkillAttributeType, Integer> requirement : group.entrySet()) {
                    int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
                    if (current < requirement.getValue()) {
                        groupSatisfied = false;
                        break;
                    }
                }
                if (groupSatisfied) {
                    anySatisfied = true;
                    break;
                }
            }
            if (!anySatisfied) {
                blockers.add("Requires at least one OR skill requirement set to be met.");
            }
        }

        if (!requirements.getRequiredAugments().isEmpty()) {
            Set<String> selectedAugments = new HashSet<>();
            data.getSelectedAugmentsSnapshot().values().forEach(augment -> {
                if (augment != null && !augment.isBlank()) {
                    selectedAugments.add(normalizeKey(augment));
                }
            });
            for (String augment : requirements.getRequiredAugments()) {
                if (!selectedAugments.contains(normalizeKey(augment))) {
                    blockers.add("Requires augment: " + augment);
                }
            }
        }

        if (!requirements.getRequiredForms().isEmpty()) {
            Set<String> completed = new LinkedHashSet<>(data.getCompletedRaceFormsSnapshot());
            if (sourceRace != null) {
                completed.add(normalizeKey(sourceRace.getAscension().getId()));
            }

            for (String form : requirements.getRequiredForms()) {
                if (!completed.contains(normalizeKey(form))) {
                    blockers.add("Requires completed form: " + form);
                }
            }
        }

        if (blockers.isEmpty()) {
            return RaceAscensionEligibility.allowed();
        }
        return RaceAscensionEligibility.denied(blockers);
    }

    public boolean canAscend(PlayerData data, String targetRaceInput) {
        return evaluateAscensionEligibility(data, targetRaceInput).isEligible();
    }

    /**
     * Resets the player's model to default if they are online.
     */
    public void resetRaceModelIfOnline(PlayerData data) {
        if (data == null) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(data.getUuid());
        if (playerRef == null) {
            return;
        }
        resetPlayerModel(playerRef);
    }

    public RaceDefinition findRaceByUserInput(String userInput) {
        if (!isEnabled() || userInput == null) {
            return null;
        }
        RaceDefinition byId = getRace(userInput);
        if (byId != null) {
            return byId;
        }

        String normalizedName = normalizeKey(userInput);
        if (normalizedName.isEmpty()) {
            return null;
        }

        for (RaceDefinition definition : racesByKey.values()) {
            String displayName = definition.getDisplayName();
            if (displayName != null && normalizeKey(displayName).equals(normalizedName)) {
                return definition;
            }
        }
        return null;
    }

    public double getAttribute(PlayerData playerData, SkillAttributeType attributeType, double fallback) {
        if (!isEnabled() || playerData == null || attributeType == null) {
            return fallback;
        }
        RaceDefinition race = getPlayerRace(playerData);
        if (race == null) {
            return fallback;
        }
        return race.getBaseAttribute(attributeType, fallback);
    }

    /**
     * Apply the configured race model if the player has the option enabled and the
     * race defines one.
     */
    public void applyRaceModelIfEnabled(PlayerData data) {
        if (data == null) {
            return;
        }
        if (isRaceModelGloballyDisabled()) {
            resetRaceModelIfOnline(data);
            return;
        }
        if (!data.isUseRaceModel()) {
            return;
        }
        RaceDefinition race = getPlayerRace(data);
        if (race == null) {
            return;
        }
        applyRaceModelToPlayer(data, race);
    }

    private boolean shouldThrottleModelApply(UUID uuid, long windowMillis) {
        if (uuid == null || windowMillis <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = modelApplyTimestamps.get(uuid);
        if (last != null && (now - last) < windowMillis) {
            return true;
        }
        modelApplyTimestamps.put(uuid, now);
        return false;
    }

    /**
     * Apply the race model once on login with a session guard and a short throttle
     * window to collapse duplicate triggers fired during join.
     */
    public void applyRaceModelOnLogin(PlayerData data) {
        if (data == null) {
            return;
        }
        UUID uuid = data.getUuid();
        if (uuid == null) {
            return;
        }

        if (modelApplyGuard.putIfAbsent(uuid, Boolean.TRUE) != null) {
            LOGGER.atFine().log("RaceManager: skipping duplicate model apply for %s due to guard", uuid);
            return;
        }

        if (shouldThrottleModelApply(uuid, 1500L)) {
            LOGGER.atFine().log("RaceManager: throttled model apply for %s during login window", uuid);
            return;
        }

        applyRaceModelIfEnabled(data);
    }

    public void clearModelApplyGuard(UUID uuid) {
        if (uuid != null) {
            modelApplyGuard.remove(uuid);
            modelApplyTimestamps.remove(uuid);
        }
    }

    private void loadRaces() {
        File racesFolder = filesManager.getRacesFolder();
        if (racesFolder == null || !racesFolder.exists()) {
            LOGGER.atWarning().log("Races folder is missing; cannot load race definitions.");
            return;
        }

        try (Stream<Path> files = Files.walk(racesFolder.toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(this::loadRaceFromFile);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to walk races directory: %s", e.getMessage());
        }

        rebuildAscensionIndexes();

        if (!racesByKey.containsKey(normalizeKey(defaultRaceId)) && !racesByKey.isEmpty()) {
            defaultRaceId = racesByKey.values().iterator().next().getId();
            LOGGER.atInfo().log("Default race set to %s", defaultRaceId);
        }

        LOGGER.atInfo().log("Loaded %d race definition(s).", racesByKey.size());
    }

    private RaceModelDefaultMode parseRaceModelDefault(Object rawValue) {
        if (rawValue instanceof RaceModelDefaultMode mode) {
            return mode;
        }

        String normalized = null;
        if (rawValue instanceof String str) {
            normalized = str.trim().toLowerCase(Locale.ROOT);
        } else if (rawValue instanceof Boolean bool) {
            normalized = bool ? "on" : "off";
        } else if (rawValue instanceof Number number) {
            normalized = number.intValue() != 0 ? "on" : "off";
        }

        if (normalized != null) {
            switch (normalized) {
                case "on":
                case "true":
                case "enabled":
                    return RaceModelDefaultMode.ON;
                case "disabled":
                    return RaceModelDefaultMode.DISABLED;
                case "off":
                case "false":
                case "default":
                    return RaceModelDefaultMode.OFF;
                default:
                    LOGGER.atWarning().log("Invalid global_race_visuals_setting '%s'; defaulting to OFF.", normalized);
                    return RaceModelDefaultMode.OFF;
            }
        }

        return RaceModelDefaultMode.OFF;
    }

    private int parseMaxSwitches(Object configValue) {
        if (configValue == null) {
            return -1;
        }
        try {
            if (configValue instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(configValue.toString().trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    public enum RaceModelDefaultMode {
        ON,
        OFF,
        DISABLED;

        public boolean isEnabledByDefault() {
            return this == ON;
        }

        public boolean isGloballyDisabled() {
            return this == DISABLED;
        }
    }

    private void syncBuiltinRacesIfNeeded() {
        if (!forceBuiltinRaces) {
            return;
        }
        File racesFolder = filesManager.getRacesFolder();
        if (racesFolder == null) {
            LOGGER.atWarning().log("Races folder is null; cannot sync built-in races.");
            return;
        }

        int storedVersion = readRacesVersion(racesFolder);
        if (storedVersion == VersionRegistry.BUILTIN_RACES_VERSION) {
            return; // up to date
        }

        filesManager.archivePathIfExists(racesFolder.toPath(), "races", "races.version:" + storedVersion);
        clearDirectory(racesFolder.toPath());
        filesManager.exportResourceDirectory("races", racesFolder, true);
        writeRacesVersion(racesFolder, VersionRegistry.BUILTIN_RACES_VERSION);
        LOGGER.atInfo().log("Synced built-in races to version %d (force_builtin_races=true)",
                VersionRegistry.BUILTIN_RACES_VERSION);
    }

    private int readRacesVersion(File racesFolder) {
        Path versionPath = racesFolder.toPath().resolve(VersionRegistry.RACES_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read races version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeRacesVersion(File racesFolder, int version) {
        Path versionPath = racesFolder.toPath().resolve(VersionRegistry.RACES_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write races version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear races directory: %s", e.getMessage());
        }
    }

    private void loadRaceFromFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Object> yamlData = yaml.load(reader);
            if (yamlData == null) {
                LOGGER.atWarning().log("Race file %s was empty.", path.getFileName());
                return;
            }

            RaceDefinition definition = buildDefinition(path, yamlData);
            if (!definition.isEnabled()) {
                LOGGER.atInfo().log("Skipping disabled race %s from %s", definition.getId(), path.getFileName());
                return;
            }

            racesByKey.put(normalizeKey(definition.getId()), definition);
            LOGGER.atInfo().log("Loaded race %s", definition.getId());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to read race file %s: %s", path.getFileName(), e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.atSevere().log("Failed to parse race file %s: %s", path.getFileName(), e.getMessage());
        }
    }

    private RaceDefinition buildDefinition(Path file, Map<String, Object> yamlData) {
        String raceId = deriveRaceId(file, yamlData);
        String displayName = safeString(yamlData.getOrDefault("race_name", raceId));
        String description = safeString(yamlData.get("description"));
        String iconItemId = safeString(yamlData.get("icon"));
        String modelId = safeString(yamlData.get("model"));
        double modelScale = parseDouble(yamlData.getOrDefault("model_scale", 1.0));
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        EnumMap<SkillAttributeType, Double> attributes = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> attributeSection = castToStringObjectMap(yamlData.get("attributes"));
        for (SkillAttributeType type : SkillAttributeType.values()) {
            if (attributeSection == null || !attributeSection.containsKey(type.getConfigKey())) {
                continue;
            }
            double value = parseDouble(attributeSection.get(type.getConfigKey()));
            attributes.put(type, value);
        }

        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(raceId, passives);
        RaceAscensionDefinition ascension = parseAscensionDefinition(raceId, yamlData.get("ascension"));

        return new RaceDefinition(raceId,
                displayName,
                description,
                iconItemId,
                modelId,
                modelScale,
                enabled,
                attributes,
                passives,
                passiveDefinitions,
                ascension);
    }

    private RaceAscensionDefinition parseAscensionDefinition(String raceId, Object node) {
        Map<String, Object> ascensionNode = castToStringObjectMap(node);
        if (ascensionNode == null) {
            return RaceAscensionDefinition.baseFallback(normalizeKey(raceId));
        }

        String ascensionId = safeString(ascensionNode.get("id"));
        if (ascensionId == null) {
            ascensionId = normalizeKey(raceId);
        }
        String stage = safeString(ascensionNode.get("stage"));
        String path = safeString(ascensionNode.get("path"));
        boolean finalForm = parseBoolean(ascensionNode.get("final_form"), false);
        RaceAscensionRequirements requirements = parseAscensionRequirements(ascensionNode.get("requirements"));
        List<RaceAscensionPathLink> nextPaths = parseAscensionNextPaths(ascensionNode.get("next_paths"));

        return new RaceAscensionDefinition(ascensionId, stage, path, finalForm, requirements, nextPaths);
    }

    private RaceAscensionRequirements parseAscensionRequirements(Object node) {
        Map<String, Object> requirementsNode = castToStringObjectMap(node);
        if (requirementsNode == null) {
            return RaceAscensionRequirements.none();
        }

        int requiredPrestige = parseInt(requirementsNode.get("required_prestige"), 0);
        Map<SkillAttributeType, Integer> minSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("min_skill_levels"));
        Map<SkillAttributeType, Integer> maxSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("max_skill_levels"));
        List<Map<SkillAttributeType, Integer>> minAnySkillLevels = parseMinAnySkillLevels(
                requirementsNode.get("min_any_skill_levels"));
        List<String> requiredAugments = parseStringList(requirementsNode.get("required_augments"));
        List<String> requiredForms = parseStringList(requirementsNode.get("required_forms"));

        return new RaceAscensionRequirements(
                requiredPrestige,
                minSkillLevels,
                maxSkillLevels,
                minAnySkillLevels,
                requiredAugments,
                requiredForms);
    }

    private Map<SkillAttributeType, Integer> parseSkillLevelRequirements(Object node) {
        Map<SkillAttributeType, Integer> result = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> map = castToStringObjectMap(node);
        if (map == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            SkillAttributeType attributeType = SkillAttributeType.fromConfigKey(entry.getKey());
            if (attributeType == null) {
                continue;
            }
            int value = Math.max(0, parseInt(entry.getValue(), 0));
            result.put(attributeType, value);
        }
        return result;
    }

    private List<Map<SkillAttributeType, Integer>> parseMinAnySkillLevels(Object node) {
        List<Map<SkillAttributeType, Integer>> groups = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return groups;
        }

        for (Object entry : iterable) {
            Map<String, Object> groupNode = castToStringObjectMap(entry);
            if (groupNode == null || groupNode.isEmpty()) {
                continue;
            }
            Map<SkillAttributeType, Integer> group = new EnumMap<>(SkillAttributeType.class);
            for (Map.Entry<String, Object> requirement : groupNode.entrySet()) {
                SkillAttributeType type = SkillAttributeType.fromConfigKey(requirement.getKey());
                if (type == null) {
                    continue;
                }
                group.put(type, Math.max(0, parseInt(requirement.getValue(), 0)));
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    private List<RaceAscensionPathLink> parseAscensionNextPaths(Object node) {
        List<RaceAscensionPathLink> nextPaths = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return nextPaths;
        }

        for (Object entry : iterable) {
            Map<String, Object> linkMap = castToStringObjectMap(entry);
            if (linkMap == null) {
                continue;
            }
            String id = safeString(linkMap.get("id"));
            if (id == null) {
                continue;
            }
            String name = safeString(linkMap.get("name"));
            nextPaths.add(new RaceAscensionPathLink(id, name));
        }

        return nextPaths;
    }

    private void rebuildAscensionIndexes() {
        racesByAscensionId.clear();
        ascensionParentsByChild.clear();

        for (RaceDefinition race : racesByKey.values()) {
            if (race == null || race.getAscension() == null) {
                continue;
            }
            racesByAscensionId.put(normalizeKey(race.getAscension().getId()), race);
        }

        for (RaceDefinition race : racesByKey.values()) {
            if (race == null || race.getAscension() == null) {
                continue;
            }
            String parentPathId = normalizeKey(race.getAscension().getId());
            for (RaceAscensionPathLink link : race.getAscension().getNextPaths()) {
                if (link == null || link.getId() == null || link.getId().isBlank()) {
                    continue;
                }
                String childPathId = normalizeKey(link.getId());
                ascensionParentsByChild.computeIfAbsent(childPathId, key -> new ArrayList<>()).add(parentPathId);
            }
        }
    }

    private RaceDefinition getRaceByAscensionPathId(String ascensionPathId) {
        if (ascensionPathId == null || ascensionPathId.isBlank()) {
            return null;
        }
        RaceDefinition byAscensionId = racesByAscensionId.get(normalizeKey(ascensionPathId));
        if (byAscensionId != null) {
            return byAscensionId;
        }
        RaceDefinition byRaceId = getRace(ascensionPathId);
        if (byRaceId != null) {
            return byRaceId;
        }
        return findRaceByUserInput(ascensionPathId);
    }

    private boolean isDirectAscensionTransition(RaceDefinition sourceRace, RaceDefinition targetRace) {
        if (targetRace == null || targetRace.getAscension() == null) {
            return false;
        }
        String targetPathId = normalizeKey(targetRace.getAscension().getId());

        if (sourceRace == null || sourceRace.getAscension() == null) {
            List<String> parents = ascensionParentsByChild.get(targetPathId);
            return parents == null || parents.isEmpty();
        }

        for (RaceAscensionPathLink nextPath : sourceRace.getAscension().getNextPaths()) {
            if (nextPath == null) {
                continue;
            }
            if (normalizeKey(nextPath.getId()).equals(targetPathId)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> parsePassives(Object node) {
        List<Map<String, Object>> passives = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return passives;
        }
        for (Object entry : iterable) {
            Map<String, Object> passive = castToStringObjectMap(entry);
            if (passive != null && passive.containsKey("type")) {
                passives.add(passive);
            }
        }
        return passives;
    }

    private List<RacePassiveDefinition> buildPassiveDefinitions(String raceId, List<Map<String, Object>> passives) {
        List<RacePassiveDefinition> definitions = new ArrayList<>();
        if (passives == null) {
            return definitions;
        }

        for (int index = 0; index < passives.size(); index++) {
            Map<String, Object> passive = passives.get(index);
            if (passive == null) {
                continue;
            }

            String rawType = safeString(passive.get("type"));
            if (rawType == null) {
                LOGGER.atWarning().log("Race %s passive entry %d is missing a type", raceId, index + 1);
                continue;
            }

            ArchetypePassiveType type = ArchetypePassiveType.fromConfigKey(rawType);
            if (type == null) {
                LOGGER.atWarning().log("Race %s passive type '%s' is not recognized", raceId, rawType);
                continue;
            }

            double value = parseDouble(passive.get("value"));
            SkillAttributeType attributeType = null;
            if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                String attributeKey = safeString(passive.get("attribute"));
                attributeType = SkillAttributeType.fromConfigKey(attributeKey);
                if (attributeType == null) {
                    LOGGER.atWarning().log(
                            "Race %s passive entry %d has INNATE_ATTRIBUTE_GAIN without a valid attribute key",
                            raceId, index + 1);
                    continue;
                }
            }
            DamageLayer damageLayer = PassiveDefinitionParser.resolveDamageLayer(type, passive);
            String tag = PassiveDefinitionParser.resolveTag(type, passive);
            PassiveStackingStyle stacking = PassiveDefinitionParser.resolveStacking(type, passive);
            PassiveTier tier = PassiveTier.fromConfig(passive.get("tier"), PassiveTier.COMMON);
            PassiveCategory category = PassiveCategory.fromConfig(passive.get("category"), null);
            Map<String, Double> classValues = parseClassValues(passive.get("class_values"));
            definitions.add(new RacePassiveDefinition(type,
                    value,
                    passive,
                    attributeType,
                    damageLayer,
                    tag,
                    category,
                    stacking,
                    tier,
                    classValues));
        }
        return definitions;
    }

    private Map<String, Double> parseClassValues(Object node) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object rawKey = entry.getKey();
            Object rawVal = entry.getValue();
            if (!(rawKey instanceof String key)) {
                continue;
            }
            String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (normalizedKey.isEmpty()) {
                continue;
            }
            Double value = extractValue(rawVal);
            if (value != null) {
                result.put(normalizedKey, value);
            }
        }
        return result;
    }

    private Double extractValue(Object rawVal) {
        if (rawVal instanceof Number number) {
            return number.doubleValue();
        }
        if (rawVal instanceof Map<?, ?> map) {
            Object inner = map.get("value");
            if (inner instanceof Number number) {
                return number.doubleValue();
            }
            if (inner instanceof String str) {
                return parseNumericString(str);
            }
        }
        if (rawVal instanceof String str) {
            return parseNumericString(str);
        }
        return null;
    }

    private Double parseNumericString(String str) {
        if (str == null) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Apply the race model (if any) by issuing a command on behalf of the player.
     * Uses reflection to avoid hard coupling to the command API; logs a warning if
     * the command runner cannot be found.
     */
    private void applyRaceModelToPlayer(PlayerData data, RaceDefinition race) {
        if (data == null || race == null) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(data.getUuid());
        if (playerRef == null) {
            return; // player not online yet
        }

        String modelId = race.getModelId();
        double modelScale = race.getModelScale();
        if (modelId == null || modelId.isBlank()) {
            resetPlayerModel(playerRef);
            return;
        }

        StringBuilder commandBuilder = new StringBuilder("model set ")
                .append(modelId)
                .append(' ')
                .append(playerRef.getUsername());
        if (Double.isFinite(modelScale) && modelScale > 0.0 && Math.abs(modelScale - 1.0) > 1e-6) {
            String formattedScale = String.format(Locale.ROOT, "%.3f", modelScale);
            commandBuilder.append(" --scale=").append(formattedScale);
        }

        String baseCommand = commandBuilder.toString();
        LOGGER.atFine().log("RaceManager: applying model %s to %s using command '%s'", modelId,
                playerRef.getUsername(), baseCommand);
        if (!dispatchModelCommand(playerRef, baseCommand)) {
            LOGGER.atWarning().log("RaceManager: failed to dispatch model command '%s' for %s", baseCommand,
                    data.getPlayerName());
            logCommandIntrospection();
        }
    }

    private boolean resetPlayerModel(PlayerRef playerRef) {
        if (playerRef == null) {
            return false;
        }
        String command = "model reset " + playerRef.getUsername();
        LOGGER.atFine().log("RaceManager: resetting model for %s using command '%s'", playerRef.getUsername(), command);
        return dispatchModelCommand(playerRef, command);
    }

    private boolean dispatchModelCommand(PlayerRef playerRef, String commandWithOptionalSlash) {
        if (commandWithOptionalSlash == null || commandWithOptionalSlash.isBlank()) {
            return false;
        }
        String commandNoSlash = commandWithOptionalSlash.startsWith("/")
                ? commandWithOptionalSlash.substring(1)
                : commandWithOptionalSlash;

        boolean dispatched = runCommandAsConsole("/" + commandNoSlash);
        if (!dispatched) {
            dispatched = runCommandAsConsole(commandNoSlash);
        }

        // If we have a console sender, avoid falling back to player-executed paths to
        // prevent
        // permission failures for non-admins. Only use player fallbacks when console
        // sender is
        // unavailable so we at least attempt execution.
        boolean consoleAvailable = findConsoleSender() != null;
        if (!dispatched) {
            dispatched = runCommandViaCommandManager(playerRef, commandNoSlash, consoleAvailable);
        }
        if (!dispatched && !consoleAvailable) {
            dispatched = runCommandViaReflection(playerRef, "/" + commandNoSlash);
        }
        if (!dispatched && !consoleAvailable) {
            dispatched = runCommandViaReflection(playerRef, commandNoSlash);
        }
        return dispatched;
    }

    private boolean runCommandAsConsole(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Object universe = Universe.get();
        if (universe == null) {
            return false;
        }
        Object consoleSender = findConsoleSender();
        // Try universe command manager first
        Object commandManager = invokeNoArg(universe, "getCommandManager");
        if (commandManager != null) {
            if (consoleSender != null && invokeCommandAnySignature(commandManager, consoleSender, command)) {
                return true;
            }
            if (invokeConsoleCommand(commandManager, command)) {
                return true;
            }
        }
        // Try universe directly in case it exposes command execution
        if (consoleSender != null && invokeCommandAnySignature(universe, consoleSender, command)) {
            return true;
        }
        if (invokeConsoleCommand(universe, command)) {
            return true;
        }

        // Try plugin command manager if exposed
        Object plugin = EndlessLeveling.getInstance();
        if (plugin != null) {
            Object pluginCommandManager = invokeNoArg(plugin, "getCommandManager");
            if (pluginCommandManager != null) {
                if (consoleSender != null && invokeCommandAnySignature(pluginCommandManager, consoleSender, command)) {
                    return true;
                }
                if (invokeConsoleCommand(pluginCommandManager, command)) {
                    return true;
                }
            }
            if (consoleSender != null && invokeCommandAnySignature(plugin, consoleSender, command)) {
                return true;
            }
            if (invokeConsoleCommand(plugin, command)) {
                return true;
            }
        }

        // Try static methods on Universe class
        if (invokeStaticCommand(Universe.class, command)) {
            return true;
        }

        return false;
    }

    private boolean invokeStaticCommand(Class<?> type, String command) {
        if (type == null || command == null || command.isBlank()) {
            return false;
        }
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand", "executeConsole",
                "executeCommandString", "handleCommand" };
        for (String name : candidateNames) {
            Method m = findMethod(type, name, String.class);
            if (m == null) {
                continue;
            }
            try {
                m.setAccessible(true);
                m.invoke(null, command);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Try to dispatch a command string on behalf of a player using common method
     * names via reflection to keep compatibility with server API changes.
     */
    private boolean runCommandViaReflection(PlayerRef playerRef, String command) {
        if (playerRef == null || command == null || command.isBlank()) {
            return false;
        }
        try {
            Object world = invokeNoArg(playerRef, "getWorld");
            if (world != null) {
                if (invokeCommandAnySignature(world, playerRef, command)) {
                    return true;
                }
            }

            Object commandManager = invokeNoArg(playerRef, "getCommandManager");
            if (commandManager != null && invokeCommandAnySignature(commandManager, playerRef, command)) {
                return true;
            }

            Object universe = Universe.get();
            if (universe != null) {
                Object universeCommandManager = invokeNoArg(universe, "getCommandManager");
                if (universeCommandManager != null
                        && invokeCommandAnySignature(universeCommandManager, playerRef, command)) {
                    return true;
                }
            }

            if (invokeCommandAnySignature(playerRef, playerRef, command)) {
                return true;
            }

            return false;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: error dispatching model command: %s", ex.getMessage());
            return false;
        }
    }

    private boolean runCommandViaCommandManager(PlayerRef playerRef, String command, boolean consolePreferred) {
        if (playerRef == null || command == null || command.isBlank()) {
            return false;
        }
        try {
            CommandManager manager = CommandManager.get();
            if (manager == null) {
                return false;
            }
            Object consoleSender = findConsoleSender();
            if (consoleSender != null) {
                manager.handleCommand((com.hypixel.hytale.server.core.command.system.CommandSender) consoleSender,
                        command);
                return true;
            }
            if (consolePreferred) {
                return false; // console was expected; do not fall back to player to avoid permission blocks
            }
            manager.handleCommand(playerRef, command);
            return true;
        } catch (Throwable ex) {
            LOGGER.atWarning().log("RaceManager: CommandManager handleCommand failed: %s", ex.getMessage());
            return false;
        }
    }

    private Object findConsoleSender() {
        // cache not required; method is cheap and avoids stale state if server reloads
        Object universe = Universe.get();
        Object console = resolveConsoleSender(universe);
        if (console != null) {
            return console;
        }

        // Try ConsoleModule singleton:
        // com.hypixel.hytale.server.core.console.ConsoleModule#get()
        try {
            Class<?> consoleModule = Class.forName("com.hypixel.hytale.server.core.console.ConsoleModule");
            Method getMethod = consoleModule.getMethod("get");
            Object moduleInstance = getMethod.invoke(null);
            if (moduleInstance != null) {
                Object maybeConsole = invokeNoArg(moduleInstance, "getConsoleSender");
                if (maybeConsole != null) {
                    return maybeConsole;
                }
            }
        } catch (Exception ignored) {
        }

        // Last resort: instantiate ConsoleSender directly via reflection (constructor
        // is protected)
        try {
            Class<?> consoleSenderClass = Class.forName("com.hypixel.hytale.server.core.console.ConsoleSender");
            try {
                Field instanceField = consoleSenderClass.getDeclaredField("instance");
                instanceField.setAccessible(true);
                Object existing = instanceField.get(null);
                if (existing != null) {
                    return existing;
                }
            } catch (Exception ignored) {
            }

            Constructor<?> ctor = consoleSenderClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object newSender = ctor.newInstance();
            return newSender;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object resolveConsoleSender(Object universe) {
        if (universe == null) {
            return null;
        }
        String[] candidates = new String[] { "getConsole", "getConsoleSender", "getCommandSender",
                "getConsoleCommandSource", "getCommandSource", "getSystemConsole", "getSystemSender" };
        for (String name : candidates) {
            Object sender = invokeNoArg(universe, name);
            if (sender != null) {
                return sender;
            }
        }
        return null;
    }

    private boolean invokeConsoleCommand(Object target, String command) {
        return invokeCommandAnySignature(target, null, command);
    }

    private boolean invokeCommandAnySignature(Object target, Object sender, String command) {
        if (target == null || command == null || command.isBlank()) {
            return false;
        }
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "sendChatMessage", "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand",
                "executeConsole", "executeCommandString", "handleCommand" };
        Method best = null;
        for (Method method : target.getClass().getMethods()) {
            String name = method.getName();
            boolean nameMatch = false;
            for (String candidate : candidateNames) {
                if (candidate.equals(name)) {
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == String.class) {
                best = method;
                break;
            }
            if (params.length == 2 && params[1] == String.class) {
                best = method;
                // prefer string + sender variants too; keep searching for exact match
                if (sender != null && params[0].isInstance(sender)) {
                    break;
                }
            }
        }
        if (best == null) {
            return false;
        }
        try {
            best.setAccessible(true);
            Class<?>[] params = best.getParameterTypes();
            if (params.length == 1) {
                best.invoke(target, command);
            } else if (params.length == 2) {
                Object firstArg = null;
                if (sender != null && params[0].isInstance(sender)) {
                    firstArg = sender;
                }
                best.invoke(target, firstArg, command);
            } else {
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: failed to invoke command method %s on %s: %s", best.getName(),
                    target.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }

    private void logCommandIntrospection() {
        Object universe = Universe.get();
        Object universeCommandManager = universe != null ? invokeNoArg(universe, "getCommandManager") : null;
        Object plugin = EndlessLeveling.getInstance();
        Object pluginCommandManager = plugin != null ? invokeNoArg(plugin, "getCommandManager") : null;

        String universeMethods = describeCommandMethods(universe);
        String universeManagerMethods = describeCommandMethods(universeCommandManager);
        String pluginMethods = describeCommandMethods(plugin);
        String pluginManagerMethods = describeCommandMethods(pluginCommandManager);
        String universeStatic = describeCommandMethods(Universe.class);

        LOGGER.atWarning().log(
                "RaceManager: command introspection - Universe: %s | UniverseCmdMgr: %s | Plugin: %s | PluginCmdMgr: %s | Universe(static): %s",
                universeMethods, universeManagerMethods, pluginMethods, pluginManagerMethods, universeStatic);
    }

    private String describeCommandMethods(Object target) {
        if (target == null) {
            return "(null)";
        }
        Class<?> type = target instanceof Class<?> cls ? cls : target.getClass();
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "sendChatMessage", "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand",
                "executeConsole", "executeCommandString", "handleCommand" };
        StringBuilder sb = new StringBuilder();
        for (Method method : type.getMethods()) {
            for (String candidate : candidateNames) {
                if (candidate.equals(method.getName())) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(method.getName()).append('(');
                    Class<?>[] params = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(')');
                }
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private boolean invokeCommand(Object target, PlayerRef playerRef, String command) {
        Method m = findMethod(target.getClass(), "executeCommand", playerRef.getClass(), String.class);
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "executeCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", String.class, playerRef.getClass());
        }
        if (m == null) {
            // try looser signature
            m = findMethod(target.getClass(), "executeCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", Object.class, String.class);
        }
        if (m == null) {
            // single-argument variants
            m = findMethod(target.getClass(), "executeCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "sendChatMessage", String.class);
        }
        if (m == null) {
            return false;
        }
        try {
            m.setAccessible(true);
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2) {
                if (params[0].isAssignableFrom(playerRef.getClass())) {
                    m.invoke(target, playerRef, command);
                } else {
                    m.invoke(target, command, playerRef);
                }
            } else if (params.length == 1) {
                // assume single String parameter
                m.invoke(target, command);
            } else {
                m.invoke(target, playerRef, command);
            }
            return true;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: failed to invoke command method %s: %s", m.getName(),
                    ex.getMessage());
            return false;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception ex) {
            return null;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Map<String, Object> castToStringObjectMap(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private List<String> parseStringList(Object node) {
        if (!(node instanceof Iterable<?> iterable)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object entry : iterable) {
            String value = safeString(entry);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private String deriveRaceId(Path file, Map<String, Object> yamlData) {
        String explicitId = safeString(yamlData.get("id"));
        if (explicitId != null) {
            return explicitId;
        }
        String raceName = safeString(yamlData.get("race_name"));
        if (raceName != null) {
            return raceName;
        }
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        if (base.isBlank()) {
            return PlayerData.DEFAULT_RACE_ID;
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private String safeString(Object value) {
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private String parseConfiguredDefaultIdentifier(Object value) {
        String parsed = safeString(value);
        if (parsed == null) {
            return null;
        }
        return "none".equalsIgnoreCase(parsed) ? null : parsed;
    }

    private boolean isNoneLiteral(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return "none".equalsIgnoreCase(text.trim());
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return defaultValue;
    }

    private long parseCooldownSeconds(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof String stringValue) {
            try {
                long parsed = Long.parseLong(stringValue.trim());
                return Math.max(0L, parsed);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
