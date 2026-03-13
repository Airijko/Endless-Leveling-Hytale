package com.airijko.endlessleveling.enums.themes;

import com.airijko.endlessleveling.enums.PassiveTier;

/**
 * Shared color presets for augment-related sections.
 */
public enum AugmentTheme {
    PROFILE_MYTHIC("#7851a9"),
    PROFILE_ELITE("#89cff0"),
    PROFILE_COMMON("#a4a4a4"),
    GRID_MYTHIC_OWNED("#b084e0"),
    GRID_ELITE_OWNED("#7ec8f5"),
    GRID_COMMON_OWNED("#bbbbbb"),
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

    public static String gridOwnedColor(PassiveTier tier) {
        if (tier == null) {
            return GRID_COMMON_OWNED.color();
        }
        return switch (tier) {
            case MYTHIC -> GRID_MYTHIC_OWNED.color();
            case ELITE -> GRID_ELITE_OWNED.color();
            case COMMON -> GRID_COMMON_OWNED.color();
        };
    }

    public static int tierSortOrder(PassiveTier tier) {
        if (tier == null) {
            return Integer.MAX_VALUE;
        }
        return switch (tier) {
            case MYTHIC -> 0;
            case ELITE -> 1;
            case COMMON -> 2;
        };
    }

    public static String gridUnownedColor() {
        return GRID_UNOWNED.color();
    }

    public static String chooseAvailabilityColor(boolean available) {
        return available ? CHOOSE_AVAILABLE.color() : CHOOSE_UNAVAILABLE.color();
    }
}
