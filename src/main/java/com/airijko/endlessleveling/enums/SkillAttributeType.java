package com.airijko.endlessleveling.enums;

import java.util.Locale;

public enum SkillAttributeType {
    LIFE_FORCE("life_force"),
    STRENGTH("strength"),
    DEFENSE("defense"),
    HASTE("haste"),
    PRECISION("precision"),
    FEROCITY("ferocity"),
    STAMINA("stamina"),
    DISCIPLINE("discipline"),
    INTELLIGENCE("intelligence"),
    SORCERY("sorcery");

    private final String configKey;

    SkillAttributeType(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return configKey;
    }

    public static SkillAttributeType fromConfigKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (SkillAttributeType type : values()) {
            if (type.configKey.equals(normalized) || type.name().equalsIgnoreCase(key.trim())) {
                return type;
            }
        }
        return null;
    }
}
