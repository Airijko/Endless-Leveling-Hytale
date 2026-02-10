package com.airijko.endlessleveling.classes;

import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable descriptor for a playable character class.
 */
public class CharacterClassDefinition {

    private final String id;
    private final String displayName;
    private final String description;
    private final String role;
    private final boolean enabled;
    private final String iconItemId;
    private final Map<ClassWeaponType, Double> weaponMultipliers;
    private final List<Map<String, Object>> passives;
    private final List<RacePassiveDefinition> passiveDefinitions;

    public CharacterClassDefinition(String id,
            String displayName,
            String description,
            String role,
            boolean enabled,
            String iconItemId,
            Map<ClassWeaponType, Double> weaponMultipliers,
            List<Map<String, Object>> passives,
            List<RacePassiveDefinition> passiveDefinitions) {
        this.id = Objects.requireNonNull(id, "Class id cannot be null");
        this.displayName = displayName == null ? id : displayName;
        this.description = description == null ? "" : description;
        this.role = role == null ? "" : role;
        this.enabled = enabled;
        this.iconItemId = iconItemId == null ? "" : iconItemId.trim();
        this.weaponMultipliers = Collections.unmodifiableMap(new EnumMap<>(weaponMultipliers));
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

    public String getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getIconItemId() {
        return iconItemId;
    }

    public Map<ClassWeaponType, Double> getWeaponMultipliers() {
        return weaponMultipliers;
    }

    public double getWeaponMultiplier(ClassWeaponType type) {
        if (type == null) {
            return 1.0D;
        }
        return weaponMultipliers.getOrDefault(type, 1.0D);
    }

    public List<Map<String, Object>> getPassives() {
        return passives;
    }

    public List<RacePassiveDefinition> getPassiveDefinitions() {
        return passiveDefinitions;
    }
}
