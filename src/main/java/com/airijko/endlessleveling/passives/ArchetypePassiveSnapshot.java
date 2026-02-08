package com.airijko.endlessleveling.passives;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of all archetype passive values aggregated for a player.
 */
public record ArchetypePassiveSnapshot(Map<ArchetypePassiveType, Double> values) {

    private static final ArchetypePassiveSnapshot EMPTY = new ArchetypePassiveSnapshot(Collections.emptyMap());

    public static ArchetypePassiveSnapshot empty() {
        return EMPTY;
    }

    @Override
    public Map<ArchetypePassiveType, Double> values() {
        return values == null ? Collections.emptyMap() : values;
    }

    public double getValue(ArchetypePassiveType type) {
        if (type == null || values == null || values.isEmpty()) {
            return 0.0D;
        }
        return values.getOrDefault(type, 0.0D);
    }

    public boolean isEmpty() {
        return values == null || values.isEmpty();
    }
}
