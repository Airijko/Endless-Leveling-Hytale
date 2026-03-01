package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AugmentManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int BUILTIN_AUGMENTS_VERSION = 3;
    private static final String AUGMENTS_VERSION_FILE = "augments.version";

    private final Yaml yaml;
    private final Path root;
    private final PluginFilesManager filesManager;
    private final boolean forceBuiltinAugments;
    private Map<String, AugmentDefinition> cache;

    public AugmentManager(Path root, PluginFilesManager filesManager, ConfigManager configManager) {
        this.yaml = new Yaml();
        this.root = Objects.requireNonNull(root, "root");
        this.filesManager = Objects.requireNonNull(filesManager, "filesManager");
        Object forceFlag = configManager != null
                ? configManager.get("force_builtin_augments", Boolean.TRUE, false)
                : Boolean.TRUE;
        this.forceBuiltinAugments = parseBoolean(forceFlag, true);
        this.cache = Collections.emptyMap();
    }

    public void load() {
        syncBuiltinAugmentsIfNeeded();
        if (!Files.isDirectory(root)) {
            LOGGER.atWarning().log("Augment directory %s does not exist or is not a directory", root);
            this.cache = Collections.emptyMap();
            return;
        }
        Map<String, AugmentDefinition> loaded = new HashMap<>();
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> yamlFiles = paths
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml") || !name.contains(".");
                    })
                    .collect(Collectors.toList());
            for (Path file : yamlFiles) {
                try {
                    AugmentDefinition def = AugmentParser.parse(file, yaml);
                    loaded.put(def.getId(), def);
                } catch (Exception ex) {
                    LOGGER.atWarning().withCause(ex).log("Failed to parse augment %s", file.getFileName());
                }
            }
        } catch (IOException ex) {
            LOGGER.atSevere().withCause(ex).log("Error reading augment directory %s", root);
        }
        this.cache = Collections.unmodifiableMap(loaded);
        LOGGER.atInfo().log("Loaded %d augments from %s", cache.size(), root);
    }

    public Map<String, AugmentDefinition> getAugments() {
        return cache;
    }

    public AugmentDefinition getAugment(String id) {
        return cache.get(id);
    }

    public Augment createAugment(String id) {
        AugmentDefinition definition = cache.get(id);
        if (definition == null) {
            return null;
        }
        return AugmentRegistry.create(definition);
    }

    private void syncBuiltinAugmentsIfNeeded() {
        if (!forceBuiltinAugments) {
            return;
        }

        File augmentsFolder = filesManager.getAugmentsFolder();
        if (augmentsFolder == null) {
            LOGGER.atWarning().log("Augments folder is null; cannot sync built-in augments.");
            return;
        }

        int storedVersion = readAugmentsVersion(augmentsFolder);
        if (storedVersion == BUILTIN_AUGMENTS_VERSION) {
            return;
        }

        clearDirectory(augmentsFolder.toPath());
        filesManager.exportResourceDirectory("augments", augmentsFolder, true);
        writeAugmentsVersion(augmentsFolder, BUILTIN_AUGMENTS_VERSION);
        LOGGER.atInfo().log("Synced built-in augments to version %d (force_builtin_augments=true)",
                BUILTIN_AUGMENTS_VERSION);
    }

    private int readAugmentsVersion(File augmentsFolder) {
        Path versionPath = augmentsFolder.toPath().resolve(AUGMENTS_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read augments version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeAugmentsVersion(File augmentsFolder, int version) {
        Path versionPath = augmentsFolder.toPath().resolve(AUGMENTS_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write augments version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(folder))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear augments directory: %s", e.getMessage());
        }
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return defaultValue;
    }
}
