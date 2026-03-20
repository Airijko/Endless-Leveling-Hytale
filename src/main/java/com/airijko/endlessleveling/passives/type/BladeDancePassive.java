package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.BladeDanceSettings;

/**
 * Handles Blade Dance passive values.
 */
public final class BladeDancePassive {

    private final BladeDanceSettings settings;

    private BladeDancePassive(BladeDanceSettings settings) {
        this.settings = settings == null ? BladeDanceSettings.disabled() : settings;
    }

    public static BladeDancePassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new BladeDancePassive(BladeDanceSettings.fromSnapshot(snapshot));
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean triggerOnHit() {
        return settings.triggerOnHit();
    }

    public int maxStacks() {
        return settings.maxStacks();
    }

    public long durationMillis() {
        return settings.durationMillis();
    }

    public double multiplierForStacks(int stacks) {
        return settings.multiplierForStacks(stacks);
    }

    public double damageMultiplierForStacks(int stacks) {
        return settings.damageMultiplierForStacks(stacks);
    }
}
