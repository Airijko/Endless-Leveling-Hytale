package com.airijko.endlessleveling.races;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
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
        PassiveStackingStyle stackingStyle) {

    public RacePassiveDefinition {
        Map<String, Object> safeProps = properties == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(properties);
        properties = Collections.unmodifiableMap(safeProps);
        stackingStyle = stackingStyle == null && type != null
                ? PassiveStackingStyle.defaultFor(type)
                : Objects.requireNonNullElse(stackingStyle, PassiveStackingStyle.ADDITIVE);
        tag = normalizeTag(tag, type);
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
}
