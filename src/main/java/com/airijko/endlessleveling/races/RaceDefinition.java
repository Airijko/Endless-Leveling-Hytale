package com.airijko.endlessleveling.races;

import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RaceDefinition {

    private final String id;
    private final String displayName;
    private final String description;
    private final boolean enabled;
    private final Map<SkillAttributeType, Double> baseAttributes;
    private final List<Map<String, Object>> passives;
    private final List<RacePassiveDefinition> passiveDefinitions;

    public RaceDefinition(String id,
            String displayName,
            String description,
            boolean enabled,
            Map<SkillAttributeType, Double> baseAttributes,
            List<Map<String, Object>> passives,
            List<RacePassiveDefinition> passiveDefinitions) {
        this.id = Objects.requireNonNull(id, "Race id cannot be null");
        this.displayName = displayName == null ? id : displayName;
        this.description = description == null ? "" : description;
        this.enabled = enabled;
        this.baseAttributes = Collections.unmodifiableMap(new EnumMap<>(baseAttributes));
        this.passives = Collections.unmodifiableList(copyPassives(passives));
        List<RacePassiveDefinition> typed = passiveDefinitions == null
                ? new ArrayList<>()
                : new ArrayList<>(passiveDefinitions);
        this.passiveDefinitions = Collections.unmodifiableList(typed);
    }

    private List<Map<String, Object>> copyPassives(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (Map<String, Object> passive : source) {
            if (passive == null) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(passive);
            result.add(Collections.unmodifiableMap(copy));
        }
        return result;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<SkillAttributeType, Double> getBaseAttributes() {
        return baseAttributes;
    }

    public double getBaseAttribute(SkillAttributeType type, double defaultValue) {
        if (type == null) {
            return defaultValue;
        }
        return baseAttributes.getOrDefault(type, defaultValue);
    }

    public List<Map<String, Object>> getPassives() {
        return passives;
    }

    public List<RacePassiveDefinition> getPassiveDefinitions() {
        return passiveDefinitions;
    }
}
