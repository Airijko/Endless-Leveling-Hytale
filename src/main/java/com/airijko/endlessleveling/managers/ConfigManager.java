package com.airijko.endlessleveling.managers;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import com.hypixel.hytale.logger.HytaleLogger;

public class ConfigManager {

    private final File configFile;
    private final Yaml yaml;
    private Map<String, Object> configMap = new LinkedHashMap<>();
    private final double bundledConfigVersion;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String CONFIG_RESOURCE = "config.yml";
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final String BACKUP_EXTENSION = ".old";

    public ConfigManager(File configFile) {
        this.configFile = configFile;

        // Setup SnakeYAML
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        this.bundledConfigVersion = resolveBundledConfigVersion();

        load(); // load config when manager is created
    }

    /** Load config from disk */
    public void load() {
        ensureConfigFileExists();
        readConfigFromDisk();
        ensureConfigUpToDate();
    }

    /** Save current configMap to disk */
    public void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            yaml.dump(configMap, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Get a value from config */
    public Object get(String path, Object defaultValue) {
        return get(path, defaultValue, true);
    }

    /** Get a value from config with optional logging */
    public Object get(String path, Object defaultValue, boolean logAccess) {
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;
        Object value = null;

        if (currentMap == null) {
            return defaultValue;
        }

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (i == keys.length - 1) {
                // Last key, return value or default
                value = currentMap.getOrDefault(key, defaultValue);
            } else {
                Object next = currentMap.get(key);
                if (next instanceof Map<?, ?> map) {
                    // noinspection unchecked
                    currentMap = (Map<String, Object>) map;
                } else {
                    if (logAccess) {
                        LOGGER.atWarning().log("ConfigManager.get: Path broken at '%s', returning default", key);
                    }
                    return defaultValue;
                }
            }
        }

        if (logAccess) {
            LOGGER.atInfo().log("ConfigManager.get: path='%s', value=%s", path, value);
        }
        return value;
    }

    /** Set a value in config */
    public void set(String path, Object value) {
        configMap.put(path, value);
    }

    private void ensureConfigFileExists() {
        if (configFile.exists()) {
            return;
        }
        try {
            copyBundledConfigToFile();
            LOGGER.atInfo().log("Generated default config at %s", configFile.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create default config file", e);
        }
    }

    private void readConfigFromDisk() {
        try (FileReader reader = new FileReader(configFile)) {
            Object loaded = yaml.load(reader);
            configMap = toMutableMap(loaded);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to read config file: %s", e.getMessage());
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

    private void ensureConfigUpToDate() {
        double currentVersion = extractConfigVersion(configMap);
        if (!Double.isNaN(currentVersion) && currentVersion >= bundledConfigVersion) {
            return;
        }

        String foundVersion = Double.isNaN(currentVersion) ? "missing" : Double.toString(currentVersion);
        String expectedVersion = Double.toString(bundledConfigVersion);
        LOGGER.atWarning().log(
                "Config version is missing/outdated (found=%s, expected=%s). Refreshing config...",
                foundVersion, expectedVersion);

        backupCurrentConfig();
        try {
            copyBundledConfigToFile();
        } catch (IOException e) {
            LOGGER.atSevere().log("Unable to refresh config: %s", e.getMessage());
            return;
        }
        readConfigFromDisk();
    }

    private void backupCurrentConfig() {
        if (!configFile.exists()) {
            return;
        }
        File backupFile = new File(configFile.getParentFile(), configFile.getName() + BACKUP_EXTENSION);
        try {
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.atInfo().log("Backed up old config to %s", backupFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to create config backup: %s", e.getMessage());
        }
    }

    private void copyBundledConfigToFile() throws IOException {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException("Bundled config resource not found: " + CONFIG_RESOURCE);
            }
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private double resolveBundledConfigVersion() {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled config resource missing: " + CONFIG_RESOURCE);
            }
            Map<String, Object> bundledMap = toMutableMap(yaml.load(in));
            double version = extractConfigVersion(bundledMap);
            if (Double.isNaN(version)) {
                throw new IllegalStateException("Bundled config is missing a valid config_version");
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read bundled config resource", e);
        }
    }

    private double extractConfigVersion(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Double.NaN;
        }
        Object versionValue = source.get(CONFIG_VERSION_KEY);
        if (versionValue instanceof Number number) {
            return number.doubleValue();
        }
        if (versionValue instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}
