package com.airijko.endlessleveling.races;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/**
 * Represents a single passive entry parsed from a race configuration file.
 */
public record RacePassiveDefinition(ArchetypePassiveType type,
        double value,
        Map<String, Object> properties,
        SkillAttributeType attributeType,
        DamageLayer damageLayer,
        String tag,
        PassiveCategory category,
        PassiveStackingStyle stackingStyle,
        PassiveTier tier,
        Map<String, Double> classValues) {

    public RacePassiveDefinition {
        Map<String, Object> safeProps = properties == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(properties);
        properties = Collections.unmodifiableMap(safeProps);

        PassiveTier resolvedTier = tier == null ? PassiveTier.COMMON : tier;
        tier = resolvedTier;

        category = category == null ? PassiveCategory.PASSIVE_STAT : category;

        Map<String, Double> safeClassValues = normalizeClassValues(classValues);
        classValues = Collections.unmodifiableMap(safeClassValues);

        PassiveStackingStyle resolvedStacking = resolvedTier.isUniqueTier()
                ? PassiveStackingStyle.UNIQUE
                : stackingStyle;
        stackingStyle = resolvedStacking == null && type != null
                ? PassiveStackingStyle.defaultFor(type)
                : Objects.requireNonNullElse(resolvedStacking, PassiveStackingStyle.ADDITIVE);

        tag = normalizeTag(tag, type);
    }

    public PassiveStackingStyle effectiveStackingStyle() {
        return stackingStyle == null ? PassiveStackingStyle.defaultFor(type) : stackingStyle;
    }

    public double resolveValueForClass(String classId) {
        if (classId == null || classId.isBlank()) {
            return value;
        }
        Double override = classValues.get(classId.trim().toLowerCase(Locale.ROOT));
        return override != null ? override : value;
    }

    public PassiveTier tier() {
        return tier == null ? PassiveTier.COMMON : tier;
    }

    public PassiveCategory category() {
        return category == null ? PassiveCategory.PASSIVE_STAT : category;
    }

    public Map<String, Double> classValues() {
        return classValues == null ? Collections.emptyMap() : classValues;
    }

    private static String normalizeTag(String rawTag, ArchetypePassiveType type) {
        if (rawTag != null && !rawTag.isBlank()) {
            return rawTag.trim().toLowerCase(Locale.ROOT);
        }
        if (type != null) {
            return type.getConfigKey().toLowerCase(Locale.ROOT);
        }
        return "default";
    }

    private static Map<String, Double> normalizeClassValues(Map<String, Double> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            String key = entry.getKey();
            Double val = entry.getValue();
            if (key == null || key.isBlank() || val == null) {
                continue;
            }
            normalized.put(key.trim().toLowerCase(Locale.ROOT), val.doubleValue());
        }
        return normalized;
    }
}
