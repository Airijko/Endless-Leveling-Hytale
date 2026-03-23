package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable data parsed from an augment YAML.
 */
public final class AugmentDefinition {

    private final String id;
    private final String name;
    private final PassiveTier tier;
    private final PassiveCategory category;
    private final boolean stackable;
    private final String description;
    private final Map<String, Object> passives;
    private final List<UiSection> uiSections;
    private final boolean mobCompatible;

    public AugmentDefinition(String id,
            String name,
            PassiveTier tier,
            PassiveCategory category,
            boolean stackable,
            String description,
            Map<String, Object> passives) {
        this(id, name, tier, category, stackable, description, passives, Collections.emptyList(), true);
    }

    public AugmentDefinition(String id,
            String name,
            PassiveTier tier,
            PassiveCategory category,
            boolean stackable,
            String description,
            Map<String, Object> passives,
            List<UiSection> uiSections) {
        this(id, name, tier, category, stackable, description, passives, uiSections, true);
    }

    public AugmentDefinition(String id,
            String name,
            PassiveTier tier,
            PassiveCategory category,
            boolean stackable,
            String description,
            Map<String, Object> passives,
            List<UiSection> uiSections,
            boolean mobCompatible) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? id : name;
        this.tier = tier == null ? PassiveTier.COMMON : tier;
        this.category = category == null ? PassiveCategory.PASSIVE_STAT : category;
        this.stackable = stackable;
        this.description = description == null ? "" : description;
        Map<String, Object> safe = passives == null ? Collections.emptyMap() : new LinkedHashMap<>(passives);
        this.passives = Collections.unmodifiableMap(safe);
        List<UiSection> safeSections = uiSections == null ? Collections.emptyList() : new ArrayList<>(uiSections);
        this.uiSections = Collections.unmodifiableList(safeSections);
        this.mobCompatible = mobCompatible;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PassiveTier getTier() {
        return tier;
    }

    public PassiveCategory getCategory() {
        return category;
    }

    public boolean isStackable() {
        return stackable;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getPassives() {
        return passives;
    }

    public List<UiSection> getUiSections() {
        return uiSections;
    }

    public boolean isMobCompatible() {
        return mobCompatible;
    }

    public record UiSection(String title, String body, String color) {
        public UiSection {
            title = title == null ? "" : title;
            body = body == null ? "" : body;
            color = color == null ? "" : color;
        }
    }
}
