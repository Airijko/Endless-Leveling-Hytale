package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class RaceManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final ConfigManager configManager;
    private final PluginFilesManager filesManager;
    private final boolean racesEnabled;
    private final Map<String, RaceDefinition> racesByKey = new HashMap<>();
    private final Yaml yaml = new Yaml();

    private String defaultRaceId = PlayerData.DEFAULT_RACE_ID;

    public RaceManager(ConfigManager configManager, PluginFilesManager filesManager) {
        this.configManager = Objects.requireNonNull(configManager, "ConfigManager is required");
        this.filesManager = Objects.requireNonNull(filesManager, "PluginFilesManager is required");
        this.racesEnabled = parseBoolean(configManager.get("enable_races", Boolean.TRUE, false), true);

        if (!racesEnabled) {
            LOGGER.atInfo().log("Race system disabled via config.yml (enable_races=false).");
            return;
        }

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
        RaceDefinition configuredDefault = racesByKey.get(normalizeKey(defaultRaceId));
        if (configuredDefault != null) {
            return configuredDefault;
        }
        return racesByKey.values().stream().findFirst().orElse(null);
    }

    public String getDefaultRaceId() {
        RaceDefinition defaultRace = getDefaultRace();
        return defaultRace != null ? defaultRace.getId() : PlayerData.DEFAULT_RACE_ID;
    }

    public String resolveRaceId(String requestedId) {
        return resolveRaceIdentifier(requestedId);
    }

    public String resolveRaceIdentifier(String requestedValue) {
        if (!isEnabled()) {
            return PlayerData.DEFAULT_RACE_ID;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            RaceDefinition fallback = getDefaultRace();
            return fallback != null ? fallback.getId() : PlayerData.DEFAULT_RACE_ID;
        }

        RaceDefinition requested = getRace(requestedValue);
        if (requested != null) {
            return requested.getId();
        }

        String normalizedName = requestedValue.trim().toLowerCase(Locale.ROOT);
        for (RaceDefinition definition : racesByKey.values()) {
            if (definition.getDisplayName() != null
                    && definition.getDisplayName().trim().toLowerCase(Locale.ROOT).equals(normalizedName)) {
                return definition.getId();
            }
        }

        RaceDefinition fallback = getDefaultRace();
        return fallback != null ? fallback.getId() : PlayerData.DEFAULT_RACE_ID;
    }

    public RaceDefinition getPlayerRace(PlayerData data) {
        if (data == null) {
            return null;
        }
        RaceDefinition current = getRace(data.getRaceId());
        if (current != null) {
            return current;
        }
        return getDefaultRace();
    }

    public RaceDefinition setPlayerRace(PlayerData data, String requestedValue) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolveRaceIdentifier(requestedValue);
        RaceDefinition resolved = getRace(resolvedId);
        if (resolved == null) {
            resolved = getDefaultRace();
        }
        if (resolved != null) {
            data.setRaceId(resolved.getId());
        } else {
            data.setRaceId(PlayerData.DEFAULT_RACE_ID);
        }
        return resolved;
    }

    public Collection<RaceDefinition> getLoadedRaces() {
        return Collections.unmodifiableCollection(racesByKey.values());
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

        if (!racesByKey.containsKey(normalizeKey(defaultRaceId)) && !racesByKey.isEmpty()) {
            defaultRaceId = racesByKey.values().iterator().next().getId();
            LOGGER.atInfo().log("Default race set to %s", defaultRaceId);
        }

        LOGGER.atInfo().log("Loaded %d race definition(s).", racesByKey.size());
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
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        EnumMap<SkillAttributeType, Double> attributes = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> attributeSection = castToStringObjectMap(yamlData.get("attributes"));
        for (SkillAttributeType type : SkillAttributeType.values()) {
            double value = attributeSection != null ? parseDouble(attributeSection.get(type.getConfigKey())) : 0.0;
            attributes.put(type, value);
        }

        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        return new RaceDefinition(raceId, displayName, description, enabled, attributes, passives);
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

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
