package com.airijko.endlessleveling.races;

import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RaceAscensionRequirements {

    private final int requiredPrestige;
    private final Map<SkillAttributeType, Integer> minSkillLevels;
    private final Map<SkillAttributeType, Integer> maxSkillLevels;
    private final List<Map<SkillAttributeType, Integer>> minAnySkillLevels;
    private final List<String> requiredAugments;
    private final List<String> requiredForms;

    public RaceAscensionRequirements(int requiredPrestige,
            Map<SkillAttributeType, Integer> minSkillLevels,
            Map<SkillAttributeType, Integer> maxSkillLevels,
            List<Map<SkillAttributeType, Integer>> minAnySkillLevels,
            List<String> requiredAugments,
            List<String> requiredForms) {
        this.requiredPrestige = Math.max(0, requiredPrestige);
        this.minSkillLevels = Collections.unmodifiableMap(copyLevels(minSkillLevels));
        this.maxSkillLevels = Collections.unmodifiableMap(copyLevels(maxSkillLevels));
        this.minAnySkillLevels = Collections.unmodifiableList(copyMinAny(minAnySkillLevels));
        this.requiredAugments = Collections.unmodifiableList(copyStrings(requiredAugments));
        this.requiredForms = Collections.unmodifiableList(copyStrings(requiredForms));
    }

    public static RaceAscensionRequirements none() {
        return new RaceAscensionRequirements(0,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    public int getRequiredPrestige() {
        return requiredPrestige;
    }

    public Map<SkillAttributeType, Integer> getMinSkillLevels() {
        return minSkillLevels;
    }

    public Map<SkillAttributeType, Integer> getMaxSkillLevels() {
        return maxSkillLevels;
    }

    public List<Map<SkillAttributeType, Integer>> getMinAnySkillLevels() {
        return minAnySkillLevels;
    }

    public List<String> getRequiredAugments() {
        return requiredAugments;
    }

    public List<String> getRequiredForms() {
        return requiredForms;
    }

    private Map<SkillAttributeType, Integer> copyLevels(Map<SkillAttributeType, Integer> source) {
        Map<SkillAttributeType, Integer> copy = new EnumMap<>(SkillAttributeType.class);
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            copy.put(key, Math.max(0, value));
        });
        return copy;
    }

    private List<Map<SkillAttributeType, Integer>> copyMinAny(List<Map<SkillAttributeType, Integer>> source) {
        List<Map<SkillAttributeType, Integer>> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (Map<SkillAttributeType, Integer> group : source) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            Map<SkillAttributeType, Integer> groupCopy = new EnumMap<>(SkillAttributeType.class);
            group.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                groupCopy.put(key, Math.max(0, value));
            });
            if (!groupCopy.isEmpty()) {
                copy.add(Collections.unmodifiableMap(groupCopy));
            }
        }
        return copy;
    }

    private List<String> copyStrings(List<String> source) {
        List<String> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (String value : source) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                copy.add(trimmed);
            }
        }
        return copy;
    }
}
