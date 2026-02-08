package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.managers.RaceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

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
        for (PassiveSource source : sources) {
            source.collect(playerData, totals);
        }

        if (totals.isEmpty()) {
            return ArchetypePassiveSnapshot.empty();
        }
        return new ArchetypePassiveSnapshot(Collections.unmodifiableMap(totals));
    }

    private interface PassiveSource {
        void collect(PlayerData playerData, EnumMap<ArchetypePassiveType, Double> totals);
    }

    private static final class RacePassiveSource implements PassiveSource {
        private final RaceManager raceManager;

        RacePassiveSource(RaceManager raceManager) {
            this.raceManager = raceManager;
        }

        @Override
        public void collect(PlayerData playerData, EnumMap<ArchetypePassiveType, Double> totals) {
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
            }
        }
    }
}
