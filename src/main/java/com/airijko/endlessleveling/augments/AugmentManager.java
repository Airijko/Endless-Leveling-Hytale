package com.airijko.endlessleveling.augments;

import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AugmentManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final Yaml yaml;
    private final Path root;
    private Map<String, AugmentDefinition> cache;

    public AugmentManager(Path root) {
        this.yaml = new Yaml();
        this.root = Objects.requireNonNull(root, "root");
        this.cache = Collections.emptyMap();
    }

    public void load() {
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
}
