package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

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

    public ArchetypePassiveManager(RaceManager raceManager, ClassManager classManager) {
        List<PassiveSource> builder = new ArrayList<>();
        if (raceManager != null) {
            builder.add(new RacePassiveSource(raceManager));
        }
        if (classManager != null) {
            builder.add(new ClassPassiveSource(classManager));
        }
        this.sources = List.copyOf(builder);
    }

    public ArchetypePassiveSnapshot getSnapshot(PlayerData playerData) {
        if (playerData == null || sources.isEmpty()) {
            return ArchetypePassiveSnapshot.empty();
        }

        EnumMap<ArchetypePassiveType, StackAccumulator> totals = new EnumMap<>(ArchetypePassiveType.class);
        EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped = new EnumMap<>(ArchetypePassiveType.class);
        for (PassiveSource source : sources) {
            source.collect(playerData, totals, grouped);
        }

        if (totals.isEmpty() && grouped.isEmpty()) {
            return ArchetypePassiveSnapshot.empty();
        }
        Map<ArchetypePassiveType, Double> resolvedTotals = totals.entrySet().stream()
                .collect(() -> new EnumMap<>(ArchetypePassiveType.class),
                        (map, entry) -> map.put(entry.getKey(), entry.getValue().value()),
                        Map::putAll);
        Map<ArchetypePassiveType, Double> immutableTotals = Collections.unmodifiableMap(resolvedTotals);
        Map<ArchetypePassiveType, List<RacePassiveDefinition>> immutableDefinitions = grouped.entrySet().stream()
                .collect(() -> new EnumMap<>(ArchetypePassiveType.class),
                        (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue())),
                        Map::putAll);
        return new ArchetypePassiveSnapshot(immutableTotals, Collections.unmodifiableMap(immutableDefinitions));
    }

    private interface PassiveSource {
        void collect(PlayerData playerData,
                EnumMap<ArchetypePassiveType, StackAccumulator> totals,
                EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped);
    }

    private static final class RacePassiveSource implements PassiveSource {
        private final RaceManager raceManager;

        RacePassiveSource(RaceManager raceManager) {
            this.raceManager = raceManager;
        }

        @Override
        public void collect(PlayerData playerData,
                EnumMap<ArchetypePassiveType, StackAccumulator> totals,
                EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
            if (playerData == null || raceManager == null || !raceManager.isEnabled()) {
                return;
            }
            RaceDefinition race = raceManager.getPlayerRace(playerData);
            if (race == null) {
                return;
            }
            for (RacePassiveDefinition passive : race.getPassiveDefinitions()) {
                addPassive(passive, 1.0D, null, totals, grouped);
            }
        }
    }

    private static final class ClassPassiveSource implements PassiveSource {
        private final ClassManager classManager;

        ClassPassiveSource(ClassManager classManager) {
            this.classManager = classManager;
        }

        @Override
        public void collect(PlayerData playerData,
                EnumMap<ArchetypePassiveType, StackAccumulator> totals,
                EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
            if (playerData == null || classManager == null || !classManager.isEnabled()) {
                return;
            }
            CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(playerData);
            if (primary != null) {
                for (RacePassiveDefinition passive : primary.getPassiveDefinitions()) {
                    addPassive(passive, 1.0D, primary.getId(), totals, grouped);
                }
            }
            CharacterClassDefinition secondary = classManager.getPlayerSecondaryClass(playerData);
            if (secondary != null && secondary != primary) {
                double scale = classManager.getSecondaryPassiveScale();
                for (RacePassiveDefinition passive : secondary.getPassiveDefinitions()) {
                    addPassive(passive, scale, secondary.getId(), totals, grouped);
                }
            }
        }
    }

    private static void addPassive(RacePassiveDefinition passive,
            double scale,
            String classId,
            EnumMap<ArchetypePassiveType, StackAccumulator> totals,
            EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
        if (passive == null || passive.type() == null) {
            return;
        }
        double baseValue = passive.resolveValueForClass(classId);
        double scaledValue = baseValue * scale;
        if (scaledValue == 0.0D) {
            return;
        }
        StackAccumulator accumulator = totals.computeIfAbsent(passive.type(),
                key -> new StackAccumulator(passive.effectiveStackingStyle()));
        accumulator.addValue(scaledValue);
        RacePassiveDefinition effectiveDefinition = scale == 1.0D ? passive
                : new RacePassiveDefinition(passive.type(),
                        scaledValue,
                        passive.properties(),
                        passive.attributeType(),
                        passive.damageLayer(),
                        passive.tag(),
                        passive.category(),
                        passive.stackingStyle(),
                        passive.tier(),
                        passive.classValues());
        grouped.computeIfAbsent(passive.type(), key -> new ArrayList<>()).add(effectiveDefinition);
    }

    private static final class StackAccumulator {
        private final PassiveStackingStyle stackingStyle;
        private double value;

        StackAccumulator(PassiveStackingStyle stackingStyle) {
            this.stackingStyle = stackingStyle == null ? PassiveStackingStyle.ADDITIVE : stackingStyle;
            this.value = 0.0D;
        }

        void addValue(double newValue) {
            value = stackingStyle.combine(value, newValue);
        }

        double value() {
            return value;
        }
    }
}
