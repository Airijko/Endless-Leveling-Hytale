package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.TrueBoltsSettings;

/**
 * Handles True Bolts passive values.
 */
public final class TrueBoltsPassive {

    private final TrueBoltsSettings settings;

    private TrueBoltsPassive(TrueBoltsSettings settings) {
        this.settings = settings == null ? new TrueBoltsSettings(false, 0.0D, 0.0D, 0.0D, 0L, 0.0D) : settings;
    }

    public static TrueBoltsPassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new TrueBoltsPassive(TrueBoltsSettings.fromSnapshot(snapshot));
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public double flatTrueDamage() {
        return settings.flatTrueDamage();
    }

    public double trueDamagePercent() {
        return settings.trueDamagePercent();
    }

    public double maxHealthTrueDamagePercent() {
        return settings.maxHealthTrueDamagePercent();
    }

    public long internalCooldownMillis() {
        return settings.internalCooldownMillis();
    }

    public double monsterTrueDamageCap() {
        return settings.monsterTrueDamageCap();
    }
}
