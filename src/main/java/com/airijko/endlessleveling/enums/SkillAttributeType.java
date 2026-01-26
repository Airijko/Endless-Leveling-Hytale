package com.airijko.endlessleveling.enums;

public enum SkillAttributeType {
    LIFE_FORCE("life_force"),
    STRENGTH("strength"),
    DEFENSE("defense"),
    HASTE("haste"),
    PRECISION("precision"),
    FEROCITY("ferocity"),
    STAMINA("stamina"),
    INTELLIGENCE("intelligence");

    private final String configKey;

    SkillAttributeType(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }
}
