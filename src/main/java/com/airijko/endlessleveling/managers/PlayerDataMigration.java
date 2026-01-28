package com.airijko.endlessleveling.managers;

import org.yaml.snakeyaml.Yaml;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.FileWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

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
        int fileVersion = 1;
        Object versionObj = originalMap.get("version");
        if (versionObj instanceof Number) {
            fileVersion = ((Number) versionObj).intValue();
        } else if (versionObj instanceof String) {
            try {
                fileVersion = Integer.parseInt((String) versionObj);
            } catch (NumberFormatException ignored) {
            }
        } else {
            fileVersion = 1;
        }

        if (fileVersion >= currentVersion) {
            return originalMap;
        }

        // Backup original file into backups/<timestamp>/ so multiple files
        // can be grouped per migration run.
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backupsRoot = new File(file.getParentFile(), "backups");
            if (!backupsRoot.exists())
                backupsRoot.mkdirs();
            File dated = new File(backupsRoot, timestamp);
            if (!dated.exists())
                dated.mkdirs();
            File backup = new File(dated, file.getName() + ".v" + fileVersion + ".bak");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atInfo().log("Backed up %s to %s before migration.", file.getName(), backup.getPath());
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to backup %s before migration: %s", file.getName(), e.getMessage());
        }

        Map<String, Object> migrated = new LinkedHashMap<>(originalMap);
        int v = fileVersion;
        while (v < currentVersion) {
            switch (v) {
                case 1 -> {
                    // v1 -> v2: add 'prestige' default 0
                    if (!migrated.containsKey("prestige")) {
                        migrated.put("prestige", 0);
                    }
                    v = 2;
                    migrated.put("version", v);
                    LOGGER.atInfo().log("Migrated %s from v1 to v2.", file.getName());
                }
                default -> {
                    v++;
                    migrated.put("version", v);
                    LOGGER.atInfo().log("Bumped %s to version %d (default migration).", file.getName(), v);
                }
            }
        }

        // Write migrated YAML back to disk
        try (StringWriter buffer = new StringWriter(); FileWriter writer = new FileWriter(file)) {
            yaml.dump(migrated, buffer);
            String yamlContent = buffer.toString()
                    .replace("\nattributes:", "\n\nattributes:")
                    .replace("\noptions:", "\n\noptions:")
                    .replace("\npassives:", "\n\npassives:");
            writer.write(yamlContent);
            LOGGER.atInfo().log("Wrote migrated PlayerData to %s (now v%d).", file.getName(), v);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to write migrated PlayerData for %s: %s", file.getName(), e.getMessage());
            e.printStackTrace();
        }

        return migrated;
    }
}
