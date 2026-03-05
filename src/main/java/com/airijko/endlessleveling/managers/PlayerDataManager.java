package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.PlayerProfile;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.hypixel.hytale.logger.HytaleLogger;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PlayerDataManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PluginFilesManager filesManager;
    private final SkillManager skillManager;
    private final RaceManager raceManager;
    private final ClassManager classManager;
    private final Yaml yaml;
    private final ConcurrentHashMap<UUID, PlayerData> playerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAutoBackupMs = new ConcurrentHashMap<>();

    private static final int AUTO_BACKUP_RETENTION = 5; // keep latest N per player
    private static final long AUTO_BACKUP_MIN_INTERVAL_MS = 10 * 60 * 1000L; // 10 minutes between backups per player
    private static final DateTimeFormatter AUTO_BACKUP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public PlayerDataManager(PluginFilesManager filesManager,
            SkillManager skillManager,
            RaceManager raceManager,
            ClassManager classManager) {
        this.filesManager = filesManager;
        this.skillManager = skillManager;
        this.raceManager = raceManager;
        this.classManager = classManager;

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        LOGGER.atInfo().log("PlayerDataManager initialized.");
    }

    public int getCurrentPlayerDataVersion() {
        return VersionRegistry.PLAYERDATA_SCHEMA_VERSION;
    }

    // --- Load or create a player ---
    public PlayerData loadOrCreate(UUID uuid, String playerName) {
        ReentrantLock lock = lockFor(uuid);
        lock.lock();
        try {
            PlayerData cached = playerCache.get(uuid);
            if (cached != null) {
                return cached;
            }

            File file = filesManager.getPlayerDataFile(uuid);
            PlayerData data;
            boolean safeToSave = true; // avoid overwriting if load failed

            if (file.exists()) {
                data = loadFromFile(uuid, playerName, file);
                if (data == null) {
                    safeToSave = false;
                    data = new PlayerData(uuid, playerName, getStartingSkillPoints());
                    applyConfigDefaults(data);
                    LOGGER.atSevere().log(
                            "PlayerData for UUID %s could not be parsed; using in-memory fallback and will NOT overwrite %s. Please fix the YAML or restore a backup.",
                            uuid, file.getName());
                } else {
                    LOGGER.atInfo().log("PlayerData for UUID %s loaded from file.", uuid);
                }
            } else {
                data = new PlayerData(uuid, playerName, getStartingSkillPoints());
                applyConfigDefaults(data);
                LOGGER.atInfo().log("PlayerData for UUID %s created new.", uuid);
            }

            ensureValidRace(data);
            ensureValidClasses(data);

            // Cache and save
            playerCache.put(uuid, data);
            if (safeToSave) {
                save(data);
                LOGGER.atInfo().log("PlayerData for UUID %s cached and saved.", uuid);
            } else {
                LOGGER.atWarning().log("PlayerData for UUID %s cached only; not saved to avoid overwriting original.",
                        uuid);
            }

            return data;
        } finally {
            lock.unlock();
        }
    }

    // --- Get player from cache ---
    public PlayerData get(UUID uuid) {
        return playerCache.get(uuid);
    }

    // --- Remove player from cache ---
    public void remove(UUID uuid) {
        ReentrantLock lock = lockFor(uuid);
        lock.lock();
        try {
            playerCache.remove(uuid);
            playerLocks.remove(uuid);
            LOGGER.atInfo().log("PlayerData for UUID %s removed from cache.", uuid);
        } finally {
            lock.unlock();
        }
    }

    // --- Save a player ---
    public void save(PlayerData data) {
        if (data == null) {
            return;
        }
        ReentrantLock lock = lockFor(data.getUuid());
        lock.lock();
        try {
            ensureValidRace(data);
            ensureValidClasses(data);
            File file = filesManager.getPlayerDataFile(data.getUuid());

            Map<String, Object> map = buildYamlMap(data);

            try (StringWriter buffer = new StringWriter()) {
                yaml.dump(map, buffer);
                String yamlContent = buffer.toString()
                        .replace("\nattributes:", "\n\nattributes:")
                        .replace("\noptions:", "\n\noptions:")
                        .replace("\nprofiles:", "\n\nprofiles:")
                        .replace("\nrace:", "\n\nrace:")
                        .replace("\naugments:", "\n\naugments:")
                        .replace("\npassives:", "\n\npassives:");

                if (!isYamlRoundTripSafe(yamlContent)) {
                    LOGGER.atSevere().log(
                            "Aborting save for %s: generated YAML failed validation; file left unchanged.",
                            data.getUuid());
                    return;
                }

                // Create a rolling backup of the previous on-disk file before overwriting.
                createAutoBackupIfNeeded(file, data.getUuid());

                writeAtomically(file.toPath(), yamlContent);
                LOGGER.atFine().log("PlayerData for UUID %s saved to file.", data.getUuid());
            } catch (IOException e) {
                LOGGER.atSevere().log("Failed to save PlayerData for UUID %s: %s", data.getUuid(), e.getMessage());
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    // --- Load from file helper ---
    private PlayerData loadFromFile(UUID uuid, String playerName, File file) {
        Map<String, Object> map;
        try (FileReader reader = new FileReader(file)) {
            map = yaml.load(reader);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to load PlayerData for UUID %s from %s: %s", uuid,
                    file.getName(), e.getMessage());
            e.printStackTrace();
            var backupPath = backupCorruptFile(file, "parse-error");
            if (backupPath != null) {
                LOGGER.atWarning().log("Backed up unreadable playerdata to %s; original left untouched.",
                        backupPath.toString());
            }
            return null; // signal caller to avoid overwriting
        }

        if (map == null) {
            LOGGER.atWarning().log("PlayerData file %s for UUID %s is empty; skipping load.", file.getName(),
                    uuid);
            var backupPath = backupCorruptFile(file, "empty-file");
            if (backupPath != null) {
                LOGGER.atWarning().log("Backed up empty playerdata to %s; original left untouched.",
                        backupPath.toString());
            }
            return null;
        }

        try {
            // Migrate file if it's an older schema version. This will create
            // a backup of the original file and write a migrated file in-place.
            map = PlayerDataMigration.migrateIfNeeded(file, map, yaml, VersionRegistry.PLAYERDATA_SCHEMA_VERSION);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to migrate PlayerData for UUID %s from %s: %s", uuid,
                    file.getName(), e.getMessage());
            e.printStackTrace();
            var backupPath = backupCorruptFile(file, "migration-error");
            if (backupPath != null) {
                LOGGER.atWarning().log(
                        "Backed up playerdata prior to migration failure at %s; original left untouched.",
                        backupPath.toString());
            }
            return null;
        }

        PlayerData data = new PlayerData(uuid, playerName, getStartingSkillPoints());
        applyConfigDefaults(data);

        Map<Integer, PlayerProfile> profiles = parseProfiles(map.get("profiles"), data.getBaseSkillPoints());
        if (profiles.isEmpty()) {
            profiles = buildLegacyProfiles(map, data.getBaseSkillPoints());
        }
        int activeProfileIndex = parseActiveProfileIndex(map.get("activeProfile"));
        data.loadProfilesFromStorage(profiles, activeProfileIndex);
        applyOptions(map, data);
        ensureValidRace(data);
        LOGGER.atInfo().log("PlayerData for UUID %s loaded from disk.", uuid);
        return data;
    }

    public PlayerData getByName(String playerName) {
        if (playerName == null)
            return null;

        for (PlayerData data : playerCache.values()) {
            if (data.getPlayerName().equalsIgnoreCase(playerName)) {
                LOGGER.atFine().log("PlayerData for %s retrieved from cache by name.", playerName);
                return data;
            }
        }

        LOGGER.atWarning().log("PlayerData for player name %s not found in cache.", playerName);
        return null;
    }

    public void saveAll() {
        LOGGER.atInfo().log("Saving all cached PlayerData (%d entries)...", playerCache.size());
        for (Map.Entry<UUID, PlayerData> entry : playerCache.entrySet()) {
            save(entry.getValue());
        }
        LOGGER.atInfo().log("All cached PlayerData saved successfully.");
    }

    public Collection<PlayerData> getAllCached() {
        return Collections.unmodifiableCollection(playerCache.values());
    }

    /**
     * Load all player data files from disk (if not already cached) and
     * return a list of PlayerData sorted by level descending, then XP
     * descending as a secondary key.
     */
    public List<PlayerData> getAllPlayersSortedByLevel() {
        File folder = filesManager.getPlayerDataFolder();
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String uuidPart = name.substring(0, name.length() - 4); // strip .yml
                try {
                    UUID uuid = UUID.fromString(uuidPart);
                    // If already cached, use cached version; otherwise, load minimally from file
                    if (!playerCache.containsKey(uuid)) {
                        PlayerData loaded = loadFromFileMinimal(uuid, file);
                        if (loaded != null) {
                            playerCache.put(uuid, loaded);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID file, skip
                }
            }
        }

        List<PlayerData> all = new ArrayList<>(playerCache.values());
        all.sort(Comparator
                .comparingInt(PlayerData::getLevel).reversed()
                .thenComparingDouble(PlayerData::getXp).reversed());
        return all;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        return defaultValue;
    }

    private boolean isRaceModelGloballyDisabled() {
        return raceManager != null && raceManager.isRaceModelGloballyDisabled();
    }

    private boolean defaultUseRaceModel() {
        if (raceManager == null) {
            return false;
        }
        return raceManager.isRaceModelDefaultEnabled();
    }

    private void applyConfigDefaults(PlayerData data) {
        if (data == null) {
            return;
        }
        boolean useRaceModelDefault = defaultUseRaceModel();
        if (isRaceModelGloballyDisabled()) {
            useRaceModelDefault = false;
        }
        data.setUseRaceModel(useRaceModelDefault);
        data.setLanguage(PlayerData.DEFAULT_LANGUAGE);
    }

    private Map<String, Object> castToStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private Map<Integer, PlayerProfile> parseProfiles(Object profilesNode, int baseSkillPoints) {
        Map<Integer, PlayerProfile> profiles = new LinkedHashMap<>();
        if (!(profilesNode instanceof Map<?, ?> rawProfiles)) {
            return profiles;
        }

        for (Map.Entry<?, ?> entry : rawProfiles.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            int index = parseProfileIndex(key);
            if (!PlayerData.isValidProfileIndex(index)) {
                continue;
            }
            Map<String, Object> profileMap = castToStringObjectMap(entry.getValue());
            if (profileMap == null) {
                continue;
            }
            PlayerProfile profile = PlayerProfile.fresh(baseSkillPoints, PlayerData.defaultProfileName(index));
            applyProfileMap(profile, profileMap, index);
            profiles.put(index, profile);
        }

        return profiles;
    }

    private Map<Integer, PlayerProfile> buildLegacyProfiles(Map<String, Object> source, int baseSkillPoints) {
        Map<Integer, PlayerProfile> profiles = new LinkedHashMap<>();
        if (source == null) {
            profiles.put(1, PlayerProfile.fresh(baseSkillPoints, PlayerData.defaultProfileName(1)));
            return profiles;
        }
        PlayerProfile profile = PlayerProfile.fresh(baseSkillPoints, PlayerData.defaultProfileName(1));
        applyProfileMap(profile, source, 1);
        profiles.put(1, profile);
        return profiles;
    }

    private void applyProfileMap(PlayerProfile profile, Map<String, Object> source, int slot) {
        if (profile == null || source == null) {
            return;
        }

        profile.setXp(parseDouble(source.get("xp"), 0.0));
        profile.setLevel(parseInt(source.get("level"), 1));
        profile.setSkillPoints(parseInt(source.get("skillPoints"), profile.getSkillPoints()));
        profile.setName(PlayerData.normalizeProfileName(parseString(source.get("name")), slot));

        Map<String, Object> attrs = castToStringObjectMap(source.get("attributes"));
        if (attrs != null) {
            for (SkillAttributeType type : SkillAttributeType.values()) {
                Object value = attrs.get(type.name());
                if (value == null) {
                    value = attrs.get(type.getConfigKey());
                }
                profile.getAttributes().put(type, parseInt(value, 0));
            }
        }

        Map<String, Object> passivesNode = castToStringObjectMap(source.get("passives"));
        boolean loadedPassives = false;
        if (passivesNode != null) {
            loadedPassives = loadPassiveLevels(profile, passivesNode);
        }

        Map<String, Object> augmentOffersNode = castToStringObjectMap(source.get("augmentOffers"));
        if (augmentOffersNode != null) {
            for (Map.Entry<String, Object> entry : augmentOffersNode.entrySet()) {
                List<String> offers = parseStringList(entry.getValue());
                profile.setAugmentOffers(entry.getKey(), offers);
            }
        }

        Map<String, Object> selectedAugmentsNode = castToStringObjectMap(source.get("selectedAugments"));
        if (selectedAugmentsNode != null) {
            for (Map.Entry<String, Object> entry : selectedAugmentsNode.entrySet()) {
                String augmentId = parseString(entry.getValue());
                profile.setSelectedAugment(entry.getKey(), augmentId);
            }
        }

        Object raceNode = source.get("race");
        profile.setRaceId(parseRaceId(raceNode));
        profile.setLastRaceChangeEpochSeconds(parseRaceLastChanged(raceNode));

        Map<String, Object> classesNode = castToStringObjectMap(source.get("classes"));
        String primaryClassId = parseClassId(classesNode != null ? classesNode.get("primary") : null);
        String secondaryClassId = parseClassId(classesNode != null ? classesNode.get("secondary") : null);
        profile.setPrimaryClassId(primaryClassId);
        profile.setSecondaryClassId(secondaryClassId);
        long legacyClassTimestamp = parseClassTimestamp(classesNode, "lastChangedEpochSeconds");
        long primaryClassTimestamp = parseClassTimestamp(classesNode, "primaryLastChangedEpochSeconds");
        long secondaryClassTimestamp = parseClassTimestamp(classesNode, "secondaryLastChangedEpochSeconds");
        if (primaryClassTimestamp <= 0L && legacyClassTimestamp > 0L) {
            primaryClassTimestamp = legacyClassTimestamp;
        }
        if (secondaryClassTimestamp <= 0L && legacyClassTimestamp > 0L) {
            secondaryClassTimestamp = legacyClassTimestamp;
        }
        profile.setLastPrimaryClassChangeEpochSeconds(primaryClassTimestamp);
        profile.setLastSecondaryClassChangeEpochSeconds(secondaryClassTimestamp);
    }

    private int parseProfileIndex(String key) {
        if (key == null) {
            return -1;
        }
        try {
            return Integer.parseInt(key.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int parseActiveProfileIndex(Object node) {
        int parsed = parseInt(node, 1);
        if (!PlayerData.isValidProfileIndex(parsed)) {
            return 1;
        }
        return parsed;
    }

    private boolean loadPassiveLevels(PlayerProfile profile, Map<String, Object> node) {
        boolean loaded = false;
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            PassiveType type = resolvePassiveType(entry.getKey());
            if (type == null) {
                continue;
            }
            int level = parseInt(entry.getValue(), profile.getPassiveLevel(type));
            profile.setPassiveLevel(type, level);
            loaded = true;
        }
        return loaded;
    }

    private PassiveType resolvePassiveType(Object rawKey) {
        if (!(rawKey instanceof String key)) {
            return null;
        }
        String normalized = key.trim();
        for (PassiveType type : PassiveType.values()) {
            if (type.getConfigKey().equalsIgnoreCase(normalized)) {
                return type;
            }
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }

    private String parseString(Object value) {
        if (value instanceof String stringValue) {
            return stringValue;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof String str && !str.isBlank()) {
                    result.add(str.trim());
                }
            }
            return result;
        }
        if (value instanceof String single && !single.isBlank()) {
            return List.of(single.trim());
        }
        return Collections.emptyList();
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

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String parseRaceId(Object raceNode) {
        String directValue = coerceRaceString(raceNode);
        if (directValue != null) {
            return directValue;
        }

        Map<String, Object> raceMap = castToStringObjectMap(raceNode);
        if (raceMap == null) {
            return null;
        }

        String idValue = coerceRaceString(raceMap.get("id"));
        if (idValue != null) {
            return idValue;
        }

        String nameValue = coerceRaceString(raceMap.get("name"));
        if (nameValue != null) {
            return nameValue;
        }

        String legacyValue = coerceRaceString(raceMap.get("race"));
        if (legacyValue != null) {
            return legacyValue;
        }

        return null;
    }

    private long parseRaceLastChanged(Object raceNode) {
        Map<String, Object> raceMap = castToStringObjectMap(raceNode);
        if (raceMap == null) {
            return 0L;
        }

        Object raw = raceMap.get("lastChangedEpochSeconds");
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (raw instanceof String stringValue) {
            try {
                long parsed = Long.parseLong(stringValue.trim());
                return Math.max(0L, parsed);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private String parseClassId(Object classNode) {
        if (classNode instanceof String stringValue) {
            String trimmed = stringValue.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private long parseClassTimestamp(Map<String, Object> classesNode, String key) {
        if (classesNode == null || key == null) {
            return 0L;
        }
        Object raw = classesNode.get(key);
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (raw instanceof String stringValue) {
            try {
                long parsed = Long.parseLong(stringValue.trim());
                return Math.max(0L, parsed);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private String coerceRaceString(Object value) {
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    private void ensureValidRace(PlayerData data) {
        if (data == null) {
            return;
        }
        if (raceManager == null) {
            data.getProfiles().values().forEach(profile -> profile.setRaceId(null));
            data.setRaceId(null);
            return;
        }

        data.getProfiles().values().forEach(profile -> {
            String resolved = raceManager.resolveRaceIdentifier(profile.getRaceId());
            profile.setRaceId(resolved);
        });

        raceManager.setPlayerRaceSilently(data, data.getRaceId());
    }

    private void ensureValidClasses(PlayerData data) {
        if (data == null) {
            return;
        }
        if (classManager == null || !classManager.isEnabled()) {
            data.getProfiles().values().forEach(profile -> {
                profile.setPrimaryClassId(null);
                profile.setSecondaryClassId(null);
            });
            data.setPrimaryClassId(null);
            data.setSecondaryClassId(null);
            return;
        }

        data.getProfiles().values().forEach(profile -> {
            String resolvedPrimary = classManager.resolvePrimaryClassIdentifier(profile.getPrimaryClassId());
            profile.setPrimaryClassId(resolvedPrimary);
            String resolvedSecondary = classManager.resolveSecondaryClassIdentifier(profile.getSecondaryClassId());
            if (resolvedSecondary != null && resolvedSecondary.equalsIgnoreCase(resolvedPrimary)) {
                resolvedSecondary = null;
            }
            profile.setSecondaryClassId(resolvedSecondary);
        });

        classManager.setPlayerPrimaryClass(data, data.getPrimaryClassId());
        classManager.setPlayerSecondaryClass(data, data.getSecondaryClassId());
    }

    private String resolveRaceDisplayName(String raceId) {
        if (raceManager == null) {
            return raceId;
        }
        RaceDefinition definition = raceManager.getRace(raceId);
        if (definition == null) {
            definition = raceManager.getDefaultRace();
        }
        return definition != null ? definition.getDisplayName() : raceId;
    }

    /**
     * Minimal loader used for leaderboards when a player has never joined
     * this server run. Reads name/level/xp/skillPoints from the YAML file.
     */
    private PlayerData loadFromFileMinimal(UUID uuid, File file) {
        try (FileReader reader = new FileReader(file)) {
            Map<String, Object> map = yaml.load(reader);
            if (map == null) {
                LOGGER.atWarning().log("Minimal load: empty YAML for UUID %s in file %s", uuid, file.getName());
                return null;
            }
            // Do NOT auto-migrate on minimal loads (leaderboards) to avoid
            // touching files during read-only operations. Full load will
            // perform migration when needed.
            String playerName = (String) map.getOrDefault("playerName", uuid.toString());
            PlayerData data = new PlayerData(uuid, playerName, getStartingSkillPoints());
            applyConfigDefaults(data);

            Map<Integer, PlayerProfile> profiles = parseProfiles(map.get("profiles"), data.getBaseSkillPoints());
            if (profiles.isEmpty()) {
                profiles = buildLegacyProfiles(map, data.getBaseSkillPoints());
            }
            int activeProfileIndex = parseActiveProfileIndex(map.get("activeProfile"));
            data.loadProfilesFromStorage(profiles, activeProfileIndex);

            applyOptions(map, data);
            ensureValidRace(data);
            ensureValidClasses(data);

            return data;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to minimally load PlayerData for UUID %s from %s: %s", uuid,
                    file.getName(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private int getStartingSkillPoints() {
        return skillManager != null ? skillManager.getBaseSkillPoints() : 0;
    }

    private Map<String, Object> buildYamlMap(PlayerData data) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(VersionRegistry.PLAYERDATA_VERSION_KEY, VersionRegistry.PLAYERDATA_SCHEMA_VERSION);
        map.put("playerName", data.getPlayerName());
        map.put("activeProfile", data.getActiveProfileIndex());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("playerHud", data.isPlayerHudEnabled());
        options.put("criticalNotif", data.isCriticalNotifEnabled());
        options.put("xpNotif", data.isXpNotifEnabled());
        options.put("passiveLevelUpNotif", data.isPassiveLevelUpNotifEnabled());
        options.put("luckDoubleDropsNotif", data.isLuckDoubleDropsNotifEnabled());
        options.put("healthRegenNotif", data.isHealthRegenNotifEnabled());
        options.put("useRaceModel", data.isUseRaceModel());
        options.put("language", data.getLanguage());
        map.put("options", options);

        map.put("profiles", buildProfilesSection(data));
        return map;
    }

    private Map<String, Object> buildProfilesSection(PlayerData data) {
        Map<String, Object> profilesSection = new LinkedHashMap<>();
        data.getProfiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int index = entry.getKey();
                    PlayerProfile profile = entry.getValue();
                    if (profile == null) {
                        return;
                    }

                    Map<String, Object> profileMap = new LinkedHashMap<>();
                    profileMap.put("xp", profile.getXp());
                    profileMap.put("level", profile.getLevel());
                    profileMap.put("skillPoints", profile.getSkillPoints());
                    profileMap.put("name", profile.getName());

                    Map<String, Integer> profileAttrs = new LinkedHashMap<>();
                    for (SkillAttributeType type : SkillAttributeType.values()) {
                        profileAttrs.put(type.name(),
                                profile.getAttributes().getOrDefault(type, 0));
                    }
                    profileMap.put("attributes", profileAttrs);

                    Map<String, Object> raceSection = new LinkedHashMap<>();
                    String raceId = profile.getRaceId();
                    raceSection.put("id", raceId);
                    String raceDisplay = resolveRaceDisplayName(raceId);
                    if (raceDisplay != null && !raceDisplay.equalsIgnoreCase(raceId)) {
                        raceSection.put("name", raceDisplay);
                    }
                    raceSection.put("lastChangedEpochSeconds", profile.getLastRaceChangeEpochSeconds());
                    profileMap.put("race", raceSection);

                    Map<String, Object> classesSection = new LinkedHashMap<>();
                    classesSection.put("primary", profile.getPrimaryClassId());
                    if (profile.getSecondaryClassId() != null) {
                        classesSection.put("secondary", profile.getSecondaryClassId());
                    }
                    long primaryChanged = profile.getLastPrimaryClassChangeEpochSeconds();
                    long secondaryChanged = profile.getLastSecondaryClassChangeEpochSeconds();
                    classesSection.put("primaryLastChangedEpochSeconds", primaryChanged);
                    classesSection.put("secondaryLastChangedEpochSeconds", secondaryChanged);
                    classesSection.put("lastChangedEpochSeconds", Math.max(primaryChanged, secondaryChanged));
                    profileMap.put("classes", classesSection);

                    Map<String, Object> profileAugmentOffers = new LinkedHashMap<>();
                    profile.getAugmentOffers().forEach((tier, offers) -> {
                        if (offers != null && !offers.isEmpty()) {
                            profileAugmentOffers.put(tier, new ArrayList<>(offers));
                        }
                    });
                    if (!profileAugmentOffers.isEmpty()) {
                        profileMap.put("augmentOffers", profileAugmentOffers);
                    }

                    Map<String, Object> profileSelectedAugments = new LinkedHashMap<>();
                    profile.getSelectedAugments().forEach((tier, augmentId) -> {
                        if (augmentId != null && !augmentId.isBlank()) {
                            profileSelectedAugments.put(tier, augmentId);
                        }
                    });
                    if (!profileSelectedAugments.isEmpty()) {
                        profileMap.put("selectedAugments", profileSelectedAugments);
                    }

                    Map<String, Integer> profilePassives = new LinkedHashMap<>();
                    profile.getPassiveLevels().forEach((type, level) -> {
                        int normalized = Math.max(0, level);
                        if (normalized > 0) {
                            profilePassives.put(type.getConfigKey(), normalized);
                        }
                    });
                    if (!profilePassives.isEmpty()) {
                        profileMap.put("passives", profilePassives);
                    }

                    profilesSection.put(String.valueOf(index), profileMap);
                });

        return profilesSection;
    }

    private ReentrantLock lockFor(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, key -> new ReentrantLock());
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path backupCorruptFile(File file, String reason) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            Path source = file.toPath();
            String suffix = ".corrupt-" + System.currentTimeMillis();
            Path backup = source.resolveSibling(source.getFileName().toString() + suffix);
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atWarning().log("Backed up potentially corrupt playerdata %s to %s (%s)", source, backup, reason);
            return backup;
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to back up corrupt playerdata %s (%s): %s", file.getName(), reason,
                    e.getMessage());
            return null;
        }
    }

    private void applyOptions(Map<String, Object> map, PlayerData data) {
        Map<String, Object> options = castToStringObjectMap(map.get("options"));
        Object playerHud = options != null ? options.get("playerHud") : map.get("playerHud");
        Object criticalNotif = options != null ? options.get("criticalNotif") : map.get("criticalNotif");
        Object xpNotif = options != null ? options.get("xpNotif") : map.get("xpNotif");
        Object passiveLevelUpNotif = options != null ? options.get("passiveLevelUpNotif")
                : map.get("passiveLevelUpNotif");
        Object luckDoubleDropsNotif = options != null ? options.get("luckDoubleDropsNotif")
                : map.get("luckDoubleDropsNotif");
        Object healthRegenNotif = options != null ? options.get("healthRegenNotif")
                : map.get("healthRegenNotif");
        Object useRaceModel = options != null ? options.get("useRaceModel") : map.get("useRaceModel");
        Object language = options != null ? options.get("language") : map.get("language");
        data.setPlayerHudEnabled(parseBoolean(playerHud, true));
        data.setCriticalNotifEnabled(parseBoolean(criticalNotif, true));
        data.setXpNotifEnabled(parseBoolean(xpNotif, true));
        data.setPassiveLevelUpNotifEnabled(parseBoolean(passiveLevelUpNotif, true));
        data.setLuckDoubleDropsNotifEnabled(parseBoolean(luckDoubleDropsNotif, true));
        data.setHealthRegenNotifEnabled(parseBoolean(healthRegenNotif, true));
        data.setLanguage(parseString(language));
        boolean useRaceModelValue = parseBoolean(useRaceModel, defaultUseRaceModel());
        if (isRaceModelGloballyDisabled()) {
            useRaceModelValue = false;
        }
        data.setUseRaceModel(useRaceModelValue);
    }

    /**
     * Quick safety check: ensure the YAML we emit can be loaded back by SnakeYAML.
     * If this fails we skip writing to avoid corrupting the on-disk file.
     */
    private boolean isYamlRoundTripSafe(String yamlContent) {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return false;
        }
        try (StringReader reader = new StringReader(yamlContent)) {
            Object loaded = yaml.load(reader);
            return loaded instanceof Map<?, ?>;
        } catch (Exception ex) {
            LOGGER.atSevere().log("Round-trip YAML validation failed: %s", ex.getMessage());
            return false;
        }
    }

    /**
     * Rolling backup policy: keep the latest few copies per player in
     * plugins/EndlessLeveling/playerdata/backups/auto/<uuid>/.
     * - Throttled to avoid copying on every save (10m default).
     * - Retention limited to AUTO_BACKUP_RETENTION newest files.
     */
    private void createAutoBackupIfNeeded(File sourceFile, UUID uuid) {
        if (sourceFile == null || uuid == null) {
            return;
        }
        if (!sourceFile.exists()) {
            return; // nothing on disk yet to back up
        }

        long now = System.currentTimeMillis();
        long last = lastAutoBackupMs.getOrDefault(uuid, 0L);
        if (now - last < AUTO_BACKUP_MIN_INTERVAL_MS) {
            return; // throttle
        }

        Path backupDir = filesManager.getPlayerDataFolder().toPath()
                .resolve("backups")
                .resolve("auto")
                .resolve(uuid.toString());
        try {
            Files.createDirectories(backupDir);
            String timestamp = LocalDateTime.now().format(AUTO_BACKUP_FORMATTER);
            Path target = backupDir.resolve(sourceFile.getName() + "." + timestamp + ".yml");
            Files.copy(sourceFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            lastAutoBackupMs.put(uuid, now);
            pruneAutoBackups(backupDir);
            LOGGER.atFine().log("Auto-backed up playerdata %s to %s", sourceFile.getName(), target);
        } catch (Exception ex) {
            LOGGER.atWarning().log("Auto-backup failed for %s: %s", sourceFile.getName(), ex.getMessage());
        }
    }

    private void pruneAutoBackups(Path backupDir) {
        try (Stream<Path> stream = Files.list(backupDir)) {
            var backups = stream
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .collect(Collectors.toList());

            for (int i = AUTO_BACKUP_RETENTION; i < backups.size(); i++) {
                try {
                    Files.deleteIfExists(backups.get(i));
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        } catch (Exception ex) {
            LOGGER.atWarning().log("Failed to prune auto-backups in %s: %s", backupDir, ex.getMessage());
        }
    }

}
