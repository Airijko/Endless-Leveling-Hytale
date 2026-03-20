package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.PrimalDominanceSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.function.BiConsumer;

/**
 * Handles Primal Dominance passive window/cooldown state and bonus consumption.
 */
public final class PrimalDominancePassive {

    private final PrimalDominanceSettings settings;

    private PrimalDominancePassive(PrimalDominanceSettings settings) {
        this.settings = settings == null ? PrimalDominanceSettings.disabled() : settings;
    }

    public static PrimalDominancePassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new PrimalDominancePassive(PrimalDominanceSettings.fromSnapshot(snapshot));
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
        long windowExpiresAt = runtimeState.getPrimalDominanceWindowExpiresAt();
        if (windowExpiresAt <= 0L) {
            return 0f;
        }
        if (now > windowExpiresAt) {
            runtimeState.setPrimalDominanceWindowExpiresAt(0L);
            runtimeState.setPrimalDominanceDamageStored(0.0D);
            return 0f;
        }
        if (now < runtimeState.getPrimalDominanceCooldownExpiresAt()) {
            return 0f;
        }

        double bonus = runtimeState.getPrimalDominanceDamageStored();
        if (bonus <= 0.0D) {
            return 0f;
        }

        runtimeState.setPrimalDominanceDamageStored(0.0D);
        runtimeState.setPrimalDominanceWindowExpiresAt(0L);
        runtimeState.setPrimalDominanceCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setPrimalDominanceReadyNotified(false);

        if (messenger != null) {
            messenger.accept(playerRef,
                    String.format("Primal Dominance unleashed! Added %.0f flat damage.", bonus));
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

        if (now < runtimeState.getPrimalDominanceCooldownExpiresAt()) {
            return;
        }

        long windowMillis = settings.windowMillis();
        if (windowMillis <= 0L) {
            return;
        }

        double stored = runtimeState.getPrimalDominanceDamageStored();
        long windowExpiresAt = runtimeState.getPrimalDominanceWindowExpiresAt();
        if (stored <= 0.0D || windowExpiresAt <= 0L || now > windowExpiresAt) {
            runtimeState.setPrimalDominanceDamageStored(contribution);
            runtimeState.setPrimalDominanceWindowExpiresAt(now + windowMillis);
        } else {
            runtimeState.setPrimalDominanceDamageStored(stored + contribution);
        }
    }

    private void expireWindowIfNeeded(PassiveRuntimeState runtimeState, long now) {
        long windowExpiresAt = runtimeState.getPrimalDominanceWindowExpiresAt();
        if (windowExpiresAt > 0L && now > windowExpiresAt) {
            runtimeState.setPrimalDominanceWindowExpiresAt(0L);
            runtimeState.setPrimalDominanceDamageStored(0.0D);
        }
    }

    private void clearState(PassiveRuntimeState runtimeState) {
        runtimeState.setPrimalDominanceWindowExpiresAt(0L);
        runtimeState.setPrimalDominanceDamageStored(0.0D);
        runtimeState.setPrimalDominanceCooldownExpiresAt(0L);
        runtimeState.setPrimalDominanceReadyNotified(true);
    }
}
