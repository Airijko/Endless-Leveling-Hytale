package com.airijko.endlessleveling.managers;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Map;
import com.hypixel.hytale.logger.HytaleLogger;

public class ConfigManager {

    private final File configFile;
    private final Yaml yaml;
    private Map<String, Object> configMap;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public ConfigManager(File configFile) {
        this.configFile = configFile;

        // Setup SnakeYAML
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        load(); // load config when manager is created
    }

    /** Load config from disk */
    public void load() {
        if (!configFile.exists()) {
            try {
                configFile.createNewFile(); // create empty file if missing
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try (FileReader reader = new FileReader(configFile)) {
            configMap = yaml.load(reader);
            if (configMap == null)
                configMap = Map.of(); // empty map if file was blank
        } catch (IOException e) {
            e.printStackTrace();
        }
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
}
