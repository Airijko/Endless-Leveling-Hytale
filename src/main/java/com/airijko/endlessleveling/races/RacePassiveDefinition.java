package com.airijko.endlessleveling.races;

import com.airijko.endlessleveling.passives.ArchetypePassiveType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a single passive entry parsed from a race configuration file.
 */
public record RacePassiveDefinition(ArchetypePassiveType type,
        double value,
        Map<String, Object> properties) {

    public RacePassiveDefinition {
        Map<String, Object> safeProps = properties == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(properties);
        properties = Collections.unmodifiableMap(safeProps);
    }
}
