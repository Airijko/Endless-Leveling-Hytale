package com.airijko.endlessleveling.passives.archetype;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.List;
import java.util.Map;

/**
 * Shared helpers for resolving per-passive effectiveness scales across primary
 * and secondary class contributions.
 */
public final class ArchetypePassiveScaling {

    private static final double FALLBACK_SECONDARY_SCALE = 0.5D;

    private ArchetypePassiveScaling() {
    }

    public static AuraScales resolveAuraScales(ArchetypePassiveSnapshot snapshot,
            ArchetypePassiveType type,
            PlayerData playerData) {
        if (snapshot == null || type == null) {
            return AuraScales.none();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(type);
        if (definitions == null || definitions.isEmpty()) {
            double fallback = Math.max(0.0D, snapshot.getValue(type));
            return new AuraScales(fallback, fallback);
        }

        double secondaryScale = resolveSecondaryPassiveScale();
        double ratioScale = 0.0D;
        double fullScale = 0.0D;

        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            double value = Math.max(0.0D, definition.value());
            if (value <= 0.0D) {
                continue;
            }

            ratioScale += value;
            if (isSecondaryClassContribution(definition, playerData) && secondaryScale > 0.0D) {
                fullScale += value / secondaryScale;
            } else {
                fullScale += value;
            }
        }

        if (ratioScale <= 0.0D) {
            double fallback = Math.max(0.0D, snapshot.getValue(type));
            return new AuraScales(fallback, fallback);
        }
        if (fullScale <= 0.0D) {
            fullScale = ratioScale;
        }

        return new AuraScales(ratioScale, fullScale);
    }

    private static boolean isSecondaryClassContribution(RacePassiveDefinition definition, PlayerData playerData) {
        if (definition == null || playerData == null) {
            return false;
        }

        Map<String, Object> properties = definition.properties();
        if (properties == null || properties.isEmpty()) {
            return false;
        }

        Object source = properties.get(ArchetypePassiveManager.PASSIVE_SOURCE_PROPERTY);
        if (!(source instanceof String sourceText)
                || !ArchetypePassiveManager.PASSIVE_SOURCE_CLASS.equalsIgnoreCase(sourceText.trim())) {
            return false;
        }

        String secondaryClassId = playerData.getSecondaryClassId();
        if (secondaryClassId == null || secondaryClassId.isBlank()) {
            return false;
        }

        Object classIdRaw = properties.get(ArchetypePassiveManager.PASSIVE_CLASS_ID_PROPERTY);
        if (!(classIdRaw instanceof String classId) || classId.isBlank()) {
            return false;
        }

        return secondaryClassId.trim().equalsIgnoreCase(classId.trim());
    }

    private static double resolveSecondaryPassiveScale() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return FALLBACK_SECONDARY_SCALE;
        }

        ClassManager classManager = plugin.getClassManager();
        if (classManager == null) {
            return FALLBACK_SECONDARY_SCALE;
        }

        double scale = classManager.getSecondaryPassiveScale();
        return scale > 0.0D ? scale : FALLBACK_SECONDARY_SCALE;
    }

    public record AuraScales(double ratioScale, double fullScale) {
        public static AuraScales none() {
            return new AuraScales(0.0D, 0.0D);
        }
    }
}
