package com.airijko.endlessleveling.enums.themes;

/**
 * Shared color presets for augment-related sections.
 */
public enum AugmentTheme {
    PROFILE_MYTHIC("#7851a9"),
    PROFILE_ELITE("#89cff0"),
    PROFILE_COMMON("#ffc300"),
    GRID_MYTHIC_OWNED("#b084e0"),
    GRID_ELITE_OWNED("#7ec8f5"),
    GRID_COMMON_OWNED("#b8bec9"),
    GRID_UNOWNED("#d4d9df"),
    CHOOSE_AVAILABLE("#8adf9e"),
    CHOOSE_UNAVAILABLE("#ff9f9f");

    private final String color;

    AugmentTheme(String color) {
        this.color = color;
    }

    public String color() {
        return color;
    }
}
