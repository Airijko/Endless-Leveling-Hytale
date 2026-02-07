package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import org.yaml.snakeyaml.Yaml;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class PlayerDataMigration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private PlayerDataMigration() {
    }

    /**
     * Migrate a playerdata YAML map in-place on disk if it's older than
     * {@code currentVersion}. Creates a timestamped backup of the original
     * file before writing. Returns the migrated map (or the original if no
     * migration was necessary).
     */
    public static Map<String, Object> migrateIfNeeded(File file, Map<String, Object> originalMap, Yaml yaml,
            int currentVersion) {

        int fileVersion = parseVersion(originalMap);
        boolean missingVersion = !originalMap.containsKey("version");

        // If already up-to-date and had explicit version, nothing to do.
        if (fileVersion >= currentVersion && !missingVersion) {
            return originalMap;
        }

        // Perform a safe backup of the original file under backups/<timestamp>/
        Path dated = file.toPath().getParent().resolve("backups").resolve(timestamp());
        try {
            Files.createDirectories(dated);
            Path backupPath = dated.resolve(file.getName());
            Files.copy(file.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atInfo().log("Backed up %s to %s before migration.", file.getName(), backupPath.toString());
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to create backup for %s: %s", file.getName(), e.getMessage());
        }

        Map<String, Object> migrated = new LinkedHashMap<>(originalMap);

        // Sequentially apply migrations from fileVersion -> currentVersion
        for (int v = fileVersion; v < currentVersion; v++) {
            applyMigrationStep(v, migrated, file);
            migrated.put("version", v + 1);
        }

        // Write migrated YAML back to disk (normalize formatting)
        try (StringWriter buffer = new StringWriter(); FileWriter writer = new FileWriter(file)) {
            yaml.dump(migrated, buffer);
            String yamlContent = buffer.toString()
                    .replace("\nattributes:", "\n\nattributes:")
                    .replace("\noptions:", "\n\noptions:")
                    .replace("\npassives:", "\n\npassives:")
                    .replace("\nprofiles:", "\n\nprofiles:")
                    .replace("\nrace:", "\n\nrace:");
            writer.write(yamlContent);
            LOGGER.atInfo().log("Wrote migrated PlayerData to %s (now v%d).", file.getName(), currentVersion);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to write migrated PlayerData for %s: %s", file.getName(), e.getMessage());
            e.printStackTrace();
        }

        return migrated;
    }

    private static int parseVersion(Map<String, Object> map) {
        Object versionObj = map.get("version");
        if (versionObj instanceof Number)
            return ((Number) versionObj).intValue();
        if (versionObj instanceof String) {
            try {
                return Integer.parseInt((String) versionObj);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
    }

    private static void applyMigrationStep(int fromVersion, Map<String, Object> migrated, File file) {
        switch (fromVersion) {
            case 1 -> {
                // v1 -> v2: add 'prestige' default 0
                migrated.putIfAbsent("prestige", 0);
                LOGGER.atInfo().log("Migrated %s from v1 to v2.", file.getName());
            }
            case 2 -> {
                ensureRaceTimestamp(migrated);
                LOGGER.atInfo().log("Migrated %s from v2 to v3.", file.getName());
            }
            case 3 -> {
                migrateToProfilesSchema(migrated);
                LOGGER.atInfo().log("Migrated %s from v3 to v4.", file.getName());
            }
            case 4 -> {
                ensureProfileNames(migrated);
                LOGGER.atInfo().log("Migrated %s from v4 to v5.", file.getName());
            }
            default -> LOGGER.atInfo().log("Bumped %s from v%d to v%d (default).", file.getName(), fromVersion,
                    fromVersion + 1);
        }
    }

    private static void ensureRaceTimestamp(Map<String, Object> migrated) {
        Object profilesNode = migrated.get("profiles");
        if (profilesNode instanceof Map<?, ?> profilesMap) {
            for (Map.Entry<?, ?> entry : profilesMap.entrySet()) {
                Object profileNode = entry.getValue();
                if (!(profileNode instanceof Map<?, ?> rawProfile)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> profileMap = (Map<String, Object>) rawProfile;
                Map<String, Object> raceMap = toMutableStringObjectMap(profileMap.get("race"));
                if (raceMap == null) {
                    raceMap = new LinkedHashMap<>();
                    Object raceValue = profileMap.get("race");
                    if (raceValue instanceof String stringValue) {
                        String trimmed = stringValue.trim();
                        if (!trimmed.isEmpty()) {
                            raceMap.put("id", trimmed);
                        }
                    }
                }
                raceMap.putIfAbsent("lastChangedEpochSeconds", 0L);
                profileMap.put("race", raceMap);
            }
            return;
        }

        Object raceNode = migrated.get("race");
        Map<String, Object> raceMap = toMutableStringObjectMap(raceNode);
        if (raceMap == null) {
            raceMap = new LinkedHashMap<>();
            if (raceNode instanceof String stringValue) {
                String trimmed = stringValue.trim();
                if (!trimmed.isEmpty()) {
                    raceMap.put("id", trimmed);
                }
            }
        }

        raceMap.putIfAbsent("lastChangedEpochSeconds", 0L);
        migrated.put("race", raceMap);
    }

    private static Map<String, Object> toMutableStringObjectMap(Object source) {
        if (!(source instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static void migrateToProfilesSchema(Map<String, Object> migrated) {
        if (migrated.containsKey("profiles")) {
            ensureRaceTimestamp(migrated);
            ensureProfileNames(migrated);
            return;
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        moveIfPresent(migrated, profile, "xp");
        moveIfPresent(migrated, profile, "level");
        moveIfPresent(migrated, profile, "skillPoints");
        moveIfPresent(migrated, profile, "attributes");
        moveIfPresent(migrated, profile, "passives");
        moveIfPresent(migrated, profile, "race");
        profile.put("name", PlayerData.defaultProfileName(1));

        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put("1", profile);
        migrated.put("profiles", profiles);
        migrated.put("activeProfile", 1);

        ensureRaceTimestamp(migrated);
        ensureProfileNames(migrated);
    }

    private static void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.remove(key));
        }
    }

    private static void ensureProfileNames(Map<String, Object> migrated) {
        Object profilesNode = migrated.get("profiles");
        if (!(profilesNode instanceof Map<?, ?> rawProfiles)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> profilesMap = (Map<Object, Object>) rawProfiles;

        for (Map.Entry<Object, Object> entry : profilesMap.entrySet()) {
            int slot = parseProfileIndex(entry.getKey());
            if (slot < 1) {
                continue;
            }
            Map<String, Object> profileMap = toMutableStringObjectMap(entry.getValue());
            if (profileMap == null) {
                profileMap = new LinkedHashMap<>();
            }
            Object rawName = profileMap.get("name");
            String normalized = PlayerData.normalizeProfileName(
                    rawName instanceof String stringValue ? stringValue : null,
                    slot);
            profileMap.put("name", normalized);
            profilesMap.put(entry.getKey(), profileMap);
        }
    }

    private static int parseProfileIndex(Object key) {
        if (key instanceof Number number) {
            return number.intValue();
        }
        if (key instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
