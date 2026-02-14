package com.airijko.endlessleveling.api;

/** Component-wise attribute totals for external integrations. */
public record AttributeBreakdown(
        double raceBase,
        double skillBonus,
        double externalBonus,
        double total) {
}
