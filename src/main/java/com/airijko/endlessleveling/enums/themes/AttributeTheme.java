package com.airijko.endlessleveling.enums.themes;

import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.EnumMap;

/**
 * Shared attribute theme metadata used across profile/race UI sections.
 */
public enum AttributeTheme {
    LIFE_FORCE(SkillAttributeType.LIFE_FORCE,
            "LifeForce",
            "ui.skills.label.life_force",
            "Life Force",
            "#7be0ff",
            "#4fd7f7",
            "#4fd7f7",
            "#8aa1bf"),
    STRENGTH(SkillAttributeType.STRENGTH,
            "Strength",
            "ui.skills.label.strength",
            "Strength",
            "#ffb347",
            "#ffdf8f",
            "#ffdf8f",
            "#8aa1bf"),
    SORCERY(SkillAttributeType.SORCERY,
            "Sorcery",
            "ui.skills.label.sorcery",
            "Sorcery",
            "#d7baff",
            "#e8d5ff",
            "#e8d5ff",
            "#8aa1bf"),
    DEFENSE(SkillAttributeType.DEFENSE,
            "Defense",
            "ui.skills.label.defense",
            "Defense",
            "#8be0b2",
            "#6ee7b7",
            "#6ee7b7",
            "#8aa1bf"),
    HASTE(SkillAttributeType.HASTE,
            "Haste",
            "ui.skills.label.haste",
            "Haste",
            "#f2a2e8",
            "#f78bd9",
            "#f78bd9",
            "#8aa1bf"),
    PRECISION(SkillAttributeType.PRECISION,
            "Precision",
            "ui.skills.label.precision",
            "Precision",
            "#9ad4ff",
            "#7cb8ff",
            "#7cb8ff",
            "#8aa1bf"),
    FEROCITY(SkillAttributeType.FEROCITY,
            "Ferocity",
            "ui.skills.label.ferocity",
            "Ferocity",
            "#ff7b7b",
            "#ff5555",
            "#ff5555",
            "#8aa1bf"),
    STAMINA(SkillAttributeType.STAMINA,
            "Stamina",
            "ui.skills.label.stamina",
            "Stamina",
            "#ffc56f",
            "#ffad42",
            "#ffad42",
            "#8aa1bf"),
    FLOW(SkillAttributeType.FLOW,
            "Flow",
            "ui.skills.label.flow",
            "Flow",
            "#9be3ff",
            "#7dd6ff",
            "#7dd6ff",
            "#8aa1bf"),
    DISCIPLINE(SkillAttributeType.DISCIPLINE,
            "Discipline",
            "ui.skills.label.discipline",
            "Discipline",
            "#d59fff",
            "#d59fff",
            "#d59fff",
            "#8aa1bf");

    private static final EnumMap<SkillAttributeType, AttributeTheme> BY_TYPE = new EnumMap<>(SkillAttributeType.class);

    static {
        for (AttributeTheme theme : values()) {
            BY_TYPE.put(theme.type, theme);
        }
    }

    private final SkillAttributeType type;
    private final String uiSuffix;
    private final String labelKey;
    private final String labelFallback;
    private final String labelColor;
    private final String valueColor;
    private final String profileLevelColor;
    private final String raceNoteColor;

    AttributeTheme(SkillAttributeType type,
            String uiSuffix,
            String labelKey,
            String labelFallback,
            String labelColor,
            String valueColor,
            String profileLevelColor,
            String raceNoteColor) {
        this.type = type;
        this.uiSuffix = uiSuffix;
        this.labelKey = labelKey;
        this.labelFallback = labelFallback;
        this.labelColor = labelColor;
        this.valueColor = valueColor;
        this.profileLevelColor = profileLevelColor;
        this.raceNoteColor = raceNoteColor;
    }

    public SkillAttributeType type() {
        return type;
    }

    public String labelKey() {
        return labelKey;
    }

    public String labelFallback() {
        return labelFallback;
    }

    public String labelColor() {
        return labelColor;
    }

    public String valueColor() {
        return valueColor;
    }

    public String profileLevelColor() {
        return profileLevelColor;
    }

    public String raceNoteColor() {
        return raceNoteColor;
    }

    public String profileLabelSelector() {
        return "#Attribute" + uiSuffix + "Label";
    }

    public String profileValueSelector() {
        return "#Attribute" + uiSuffix + "Value";
    }

    public String profileLevelSelector() {
        return "#Attribute" + uiSuffix + "Level";
    }

    public String raceLabelSelector() {
        return "#RaceAttribute" + uiSuffix + "Label";
    }

    public String raceValueSelector() {
        return "#RaceAttribute" + uiSuffix + "Value";
    }

    public String raceNoteSelector() {
        return "#RaceAttribute" + uiSuffix + "Note";
    }

    public static AttributeTheme fromType(SkillAttributeType type) {
        return type == null ? null : BY_TYPE.get(type);
    }
}
