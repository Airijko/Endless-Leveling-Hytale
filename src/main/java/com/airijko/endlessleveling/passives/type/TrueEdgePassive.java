package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.TrueEdgeSettings;

/**
 * Handles True Edge passive values.
 */
public final class TrueEdgePassive {

    private final TrueEdgeSettings settings;

    private TrueEdgePassive(TrueEdgeSettings settings) {
        this.settings = settings == null ? new TrueEdgeSettings(false, 0.0D, 0.0D, 0.0D, 0L, 0.0D) : settings;
    }

    public static TrueEdgePassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new TrueEdgePassive(TrueEdgeSettings.fromSnapshot(snapshot));
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
