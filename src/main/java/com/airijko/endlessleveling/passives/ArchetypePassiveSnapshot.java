package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all archetype passive values aggregated for a player.
 */
public record ArchetypePassiveSnapshot(Map<ArchetypePassiveType, Double> values,
        Map<ArchetypePassiveType, List<RacePassiveDefinition>> definitions) {

    private static final ArchetypePassiveSnapshot EMPTY = new ArchetypePassiveSnapshot(Collections.emptyMap(),
            Collections.emptyMap());

    public static ArchetypePassiveSnapshot empty() {
        return EMPTY;
    }

    @Override
    public Map<ArchetypePassiveType, Double> values() {
        return values == null ? Collections.emptyMap() : values;
    }

    public Map<ArchetypePassiveType, List<RacePassiveDefinition>> definitions() {
        return definitions == null ? Collections.emptyMap() : definitions;
    }

    public double getValue(ArchetypePassiveType type) {
        if (type == null || values == null || values.isEmpty()) {
            return 0.0D;
        }
        return values.getOrDefault(type, 0.0D);
    }

    public List<RacePassiveDefinition> getDefinitions(ArchetypePassiveType type) {
        if (type == null || definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        List<RacePassiveDefinition> entries = definitions.get(type);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries;
    }

    public boolean isEmpty() {
        boolean noValues = values == null || values.isEmpty();
        boolean noDefinitions = definitions == null || definitions.isEmpty()
                || definitions.values().stream().allMatch(List::isEmpty);
        return noValues && noDefinitions;
    }
}
