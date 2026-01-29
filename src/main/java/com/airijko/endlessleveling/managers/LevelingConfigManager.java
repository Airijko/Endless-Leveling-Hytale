package com.airijko.endlessleveling.managers;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Manages leveling.yml with version checks and simple migration.
 */
public class LevelingConfigManager {

    private final File levelingFile;
    private final Yaml yaml;
    private Map<String, Object> configMap = new LinkedHashMap<>();
    private final int bundledConfigVersion;
    private final String resourceName;
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final String BACKUP_EXTENSION = ".old";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public LevelingConfigManager(File levelingFile) {
        this.levelingFile = levelingFile;
        this.resourceName = levelingFile.getName();

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        this.bundledConfigVersion = resolveBundledConfigVersion();

        load();
    }

    public void load() {
        ensureFileExists();
        readFromDisk();
        ensureUpToDateAndMigrate();
    }

    public int getInt(String key, int defaultValue) {
        Object val = configMap.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            if (val instanceof String)
                return Integer.parseInt((String) val);
        } catch (NumberFormatException ignored) {
        }
        return defaultValue;
    }

    public Object get(String key, Object defaultValue) {
        return configMap.getOrDefault(key, defaultValue);
    }

    private void ensureFileExists() {
        if (levelingFile.exists())
            return;
        try (InputStream in = LevelingConfigManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null)
                throw new FileNotFoundException("Bundled leveling resource missing: " + resourceName);
            Files.copy(in, levelingFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atInfo().log("Generated default leveling.yml at %s", levelingFile.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create default leveling.yml", e);
        }
    }

    private void readFromDisk() {
        try (FileReader reader = new FileReader(levelingFile)) {
            Object loaded = yaml.load(reader);
            configMap = toMutableMap(loaded);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to read leveling.yml: %s", e.getMessage());
            configMap = new LinkedHashMap<>();
        }
    }

    private Map<String, Object> toMutableMap(Object loaded) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    target.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        return target;
    }

    private void ensureUpToDateAndMigrate() {
        Integer currentVersion = extractConfigVersion(configMap);
        if (currentVersion != null && currentVersion >= bundledConfigVersion)
            return;

        String found = currentVersion == null ? "missing" : Integer.toString(currentVersion);
        LOGGER.atWarning().log("leveling.yml version is missing/outdated (found=%s, expected=%d). Refreshing...",
                found, bundledConfigVersion);

        backupCurrentFile();
        try {
            copyBundledToFile();
        } catch (IOException e) {
            LOGGER.atSevere().log("Unable to refresh leveling.yml: %s", e.getMessage());
            return;
        }
        readFromDisk();
    }

    private void backupCurrentFile() {
        if (!levelingFile.exists())
            return;
        File backup = new File(levelingFile.getParentFile(), levelingFile.getName() + BACKUP_EXTENSION);
        try {
            Files.copy(levelingFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atInfo().log("Backed up old leveling.yml to %s", backup.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to create leveling.yml backup: %s", e.getMessage());
        }
    }

    private void copyBundledToFile() throws IOException {
        InputStream in = LevelingConfigManager.class.getClassLoader().getResourceAsStream(resourceName);
        if (in == null) {
            // Try class resource lookup as fallback (handles different classloader
            // behaviors)
            in = LevelingConfigManager.class.getResourceAsStream("/" + resourceName);
        }
        if (in == null) {
            throw new FileNotFoundException("Bundled leveling resource not found: " + resourceName);
        }
        try (InputStream input = in) {
            Files.copy(input, levelingFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int resolveBundledConfigVersion() {
        InputStream in = LevelingConfigManager.class.getClassLoader().getResourceAsStream(resourceName);
        if (in == null) {
            in = LevelingConfigManager.class.getResourceAsStream("/" + resourceName);
        }
        if (in == null) {
            LOGGER.atWarning().log("Bundled resource %s missing, defaulting version to 1", resourceName);
            return 1;
        }
        try (InputStream input = in) {
            Map<String, Object> bundledMap = toMutableMap(yaml.load(input));
            Integer v = extractConfigVersion(bundledMap);
            return v == null ? 1 : v;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read bundled leveling resource", e);
        }
    }

    private Integer extractConfigVersion(Map<String, Object> source) {
        if (source == null || source.isEmpty())
            return null;
        Object versionValue = source.get(CONFIG_VERSION_KEY);
        if (versionValue instanceof Number number) {
            double raw = number.doubleValue();
            if (Double.isNaN(raw) || raw % 1 != 0)
                return null;
            return number.intValue();
        }
        if (versionValue instanceof String str) {
            String trimmed = str.trim();
            if (!trimmed.matches("-?\\d+"))
                return null;
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
