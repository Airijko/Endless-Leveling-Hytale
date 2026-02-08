package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.managers.RaceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates passive modifiers contributed by player archetypes (races,
 * classes, etc.).
 */
public class ArchetypePassiveManager {

    private final List<PassiveSource> sources;

    public ArchetypePassiveManager(RaceManager raceManager) {
        List<PassiveSource> builder = new ArrayList<>();
        if (raceManager != null) {
            builder.add(new RacePassiveSource(raceManager));
        }
        this.sources = List.copyOf(builder);
    }

    public ArchetypePassiveSnapshot getSnapshot(PlayerData playerData) {
        if (playerData == null || sources.isEmpty()) {
            return ArchetypePassiveSnapshot.empty();
        }

        EnumMap<ArchetypePassiveType, Double> totals = new EnumMap<>(ArchetypePassiveType.class);
        EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped = new EnumMap<>(ArchetypePassiveType.class);
        for (PassiveSource source : sources) {
            source.collect(playerData, totals, grouped);
        }

        if (totals.isEmpty() && grouped.isEmpty()) {
            return ArchetypePassiveSnapshot.empty();
        }
        Map<ArchetypePassiveType, Double> immutableTotals = Collections.unmodifiableMap(totals);
        Map<ArchetypePassiveType, List<RacePassiveDefinition>> immutableDefinitions = grouped.entrySet().stream()
                .collect(() -> new EnumMap<>(ArchetypePassiveType.class),
                        (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                        Map::putAll);
        return new ArchetypePassiveSnapshot(immutableTotals, Collections.unmodifiableMap(immutableDefinitions));
    }

    private interface PassiveSource {
        void collect(PlayerData playerData,
                EnumMap<ArchetypePassiveType, Double> totals,
                EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped);
    }

    private static final class RacePassiveSource implements PassiveSource {
        private final RaceManager raceManager;

        RacePassiveSource(RaceManager raceManager) {
            this.raceManager = raceManager;
        }

        @Override
        public void collect(PlayerData playerData,
                EnumMap<ArchetypePassiveType, Double> totals,
                EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
            if (playerData == null || raceManager == null || !raceManager.isEnabled()) {
                return;
            }
            RaceDefinition race = raceManager.getPlayerRace(playerData);
            if (race == null) {
                return;
            }
            for (RacePassiveDefinition passive : race.getPassiveDefinitions()) {
                if (passive == null || passive.type() == null) {
                    continue;
                }
                double value = passive.value();
                if (value == 0.0D) {
                    continue;
                }
                totals.merge(passive.type(), value, Double::sum);
                grouped.computeIfAbsent(passive.type(), key -> new ArrayList<>()).add(passive);
            }
        }
    }
}
