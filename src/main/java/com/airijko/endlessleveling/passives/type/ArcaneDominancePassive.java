package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.ArcaneDominanceSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.function.BiConsumer;

/**
 * Handles Arcane Dominance passive window/cooldown state and bonus consumption.
 */
public final class ArcaneDominancePassive {

    private final ArcaneDominanceSettings settings;

    private ArcaneDominancePassive(ArcaneDominanceSettings settings) {
        this.settings = settings == null ? ArcaneDominanceSettings.disabled() : settings;
    }

    public static ArcaneDominancePassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new ArcaneDominancePassive(ArcaneDominanceSettings.fromSnapshot(snapshot));
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public double targetHasteSlowOnHitPercent() {
        return Math.max(0.0D, settings.targetHasteSlowOnHitPercent());
    }

    public long targetHasteSlowDurationMillis() {
        return Math.max(0L, settings.targetHasteSlowDurationMillis());
    }

    public float consumeBonus(PassiveRuntimeState runtimeState,
            PlayerRef playerRef,
            BiConsumer<PlayerRef, String> messenger) {
        if (runtimeState == null || !settings.enabled()) {
            return 0f;
        }

        long now = System.currentTimeMillis();
        long windowExpiresAt = runtimeState.getArcaneDominanceWindowExpiresAt();
        if (windowExpiresAt <= 0L) {
            return 0f;
        }
        if (now > windowExpiresAt) {
            runtimeState.setArcaneDominanceWindowExpiresAt(0L);
            runtimeState.setArcaneDominanceDamageStored(0.0D);
            return 0f;
        }
        if (now < runtimeState.getArcaneDominanceCooldownExpiresAt()) {
            return 0f;
        }

        double bonus = runtimeState.getArcaneDominanceDamageStored();
        if (bonus <= 0.0D) {
            return 0f;
        }

        runtimeState.setArcaneDominanceDamageStored(0.0D);
        runtimeState.setArcaneDominanceWindowExpiresAt(0L);
        runtimeState.setArcaneDominanceCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setArcaneDominanceReadyNotified(false);

        if (messenger != null) {
            messenger.accept(playerRef,
                    String.format("Arcane Dominance unleashed! Added %.0f flat damage.", bonus));
        }

        return (float) bonus;
    }

    public void onDamageTaken(PassiveRuntimeState runtimeState, float damageTaken) {
        if (runtimeState == null) {
            return;
        }

        double reflectPercent = Math.max(0.0D, settings.reflectPercent());
        if (!settings.enabled() || reflectPercent <= 0.0D) {
            clearState(runtimeState);
            return;
        }
        if (damageTaken <= 0f) {
            return;
        }

        double contribution = damageTaken * reflectPercent;
        if (contribution <= 0.0D) {
            return;
        }

        long now = System.currentTimeMillis();
        expireWindowIfNeeded(runtimeState, now);

        if (now < runtimeState.getArcaneDominanceCooldownExpiresAt()) {
            return;
        }

        long windowMillis = settings.windowMillis();
        if (windowMillis <= 0L) {
            return;
        }

        double stored = runtimeState.getArcaneDominanceDamageStored();
        long windowExpiresAt = runtimeState.getArcaneDominanceWindowExpiresAt();
        if (stored <= 0.0D || windowExpiresAt <= 0L || now > windowExpiresAt) {
            runtimeState.setArcaneDominanceDamageStored(contribution);
            runtimeState.setArcaneDominanceWindowExpiresAt(now + windowMillis);
        } else {
            runtimeState.setArcaneDominanceDamageStored(stored + contribution);
        }
    }

    private void expireWindowIfNeeded(PassiveRuntimeState runtimeState, long now) {
        long windowExpiresAt = runtimeState.getArcaneDominanceWindowExpiresAt();
        if (windowExpiresAt > 0L && now > windowExpiresAt) {
            runtimeState.setArcaneDominanceWindowExpiresAt(0L);
            runtimeState.setArcaneDominanceDamageStored(0.0D);
        }
    }

    private void clearState(PassiveRuntimeState runtimeState) {
        runtimeState.setArcaneDominanceWindowExpiresAt(0L);
        runtimeState.setArcaneDominanceDamageStored(0.0D);
        runtimeState.setArcaneDominanceCooldownExpiresAt(0L);
        runtimeState.setArcaneDominanceReadyNotified(true);
    }
}
