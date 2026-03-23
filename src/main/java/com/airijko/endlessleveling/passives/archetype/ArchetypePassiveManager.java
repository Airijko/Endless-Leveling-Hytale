package com.airijko.endlessleveling.passives.archetype;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Aggregates passive modifiers contributed by player archetypes (races,
 * classes, etc.).
 */
public class ArchetypePassiveManager {

    public static final String PASSIVE_SOURCE_PROPERTY = "__el_source";
    public static final String PASSIVE_SOURCE_RACE = "race";
    public static final String PASSIVE_SOURCE_CLASS = "class";
    public static final String PASSIVE_SOURCE_EXTERNAL = "external";
    public static final String PASSIVE_CLASS_ID_PROPERTY = "__el_class_id";

    private final List<PassiveSource> builtinSources;
    private final List<ArchetypePassiveSource> externalSources;

    public ArchetypePassiveManager(RaceManager raceManager, ClassManager classManager) {
        List<PassiveSource> builder = new ArrayList<>();
        if (raceManager != null) {
            builder.add(new RacePassiveSource(raceManager));
        }
        if (classManager != null) {
            builder.add(new ClassPassiveSource(classManager));
        }
        this.builtinSources = List.copyOf(builder);
        this.externalSources = new CopyOnWriteArrayList<>();
    }

    public ArchetypePassiveSnapshot getSnapshot(PlayerData playerData) {
        if (playerData == null || (builtinSources.isEmpty() && externalSources.isEmpty())) {
            return ArchetypePassiveSnapshot.empty();
        }

        EnumMap<ArchetypePassiveType, ArchetypePassiveSource.StackAccumulator> totals = new EnumMap<>(
                ArchetypePassiveType.class);
        EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped = new EnumMap<>(ArchetypePassiveType.class);

        // Collect from builtin sources
        for (PassiveSource source : builtinSources) {
            source.collect(playerData, totals, grouped);
        }

        // Collect from external sources
        for (ArchetypePassiveSource source : externalSources) {
            if (source != null) {
                source.collect(playerData, totals, grouped);
            }
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

    /**
     * Register a custom archetype passive source.
     * The source will be called during snapshot generation for each player.
     */
    public boolean registerArchetypePassiveSource(ArchetypePassiveSource source) {
        if (source == null) {
            return false;
        }
        return externalSources.add(source);
    }

    /**
     * Unregister a previously registered custom archetype passive source.
     */
    public boolean unregisterArchetypePassiveSource(ArchetypePassiveSource source) {
        if (source == null) {
            return false;
        }
        return externalSources.remove(source);
    }

    private interface PassiveSource extends ArchetypePassiveSource {
        // Extends public interface, same contract
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
            EnumMap<ArchetypePassiveType, ArchetypePassiveSource.StackAccumulator> totals,
            EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
        if (passive == null || passive.type() == null) {
            return;
        }
        double baseValue = passive.resolveValueForClass(classId);
        double scaledValue = baseValue * scale;
        PassiveStackingStyle stackingStyle = passive.effectiveStackingStyle();
        if (scaledValue != 0.0D) {
            ArchetypePassiveSource.StackAccumulator accumulator = totals.computeIfAbsent(passive.type(),
                key -> new ArchetypePassiveSource.StackAccumulator(stackingStyle));
            accumulator.addValue(scaledValue);
        }

        Map<String, Object> effectiveProperties = new LinkedHashMap<>(passive.properties());
        if (classId == null || classId.isBlank()) {
            effectiveProperties.put(PASSIVE_SOURCE_PROPERTY, PASSIVE_SOURCE_RACE);
        } else {
            effectiveProperties.put(PASSIVE_SOURCE_PROPERTY, PASSIVE_SOURCE_CLASS);
            effectiveProperties.put(PASSIVE_CLASS_ID_PROPERTY, classId);
        }

        RacePassiveDefinition effectiveDefinition = new RacePassiveDefinition(passive.type(),
                scaledValue,
                effectiveProperties,
                passive.attributeType(),
                passive.damageLayer(),
                passive.tag(),
                passive.category(),
                passive.stackingStyle(),
                passive.tier(),
                passive.classValues());
        List<RacePassiveDefinition> definitions = grouped.computeIfAbsent(passive.type(), key -> new ArrayList<>());
        if (stackingStyle == PassiveStackingStyle.UNIQUE) {
            if (definitions.isEmpty()) {
                definitions.add(effectiveDefinition);
                return;
            }

            RacePassiveDefinition current = definitions.get(0);
            if (effectiveDefinition.value() > current.value()) {
                definitions.set(0, effectiveDefinition);
            }
            return;
        }

        definitions.add(effectiveDefinition);
    }
}
