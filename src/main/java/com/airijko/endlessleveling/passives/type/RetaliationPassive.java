package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.RetaliationSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.function.BiConsumer;

/**
 * Handles Retaliation passive window/cooldown state and bonus consumption.
 */
public final class RetaliationPassive {

    private final RetaliationSettings settings;

    private RetaliationPassive(RetaliationSettings settings) {
        this.settings = settings == null ? RetaliationSettings.disabled() : settings;
    }

    public static RetaliationPassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new RetaliationPassive(RetaliationSettings.fromSnapshot(snapshot));
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
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (windowExpiresAt <= 0L) {
            return 0f;
        }
        if (now > windowExpiresAt) {
            runtimeState.setRetaliationWindowExpiresAt(0L);
            runtimeState.setRetaliationDamageStored(0.0D);
            return 0f;
        }
        if (now < runtimeState.getRetaliationCooldownExpiresAt()) {
            return 0f;
        }

        double bonus = runtimeState.getRetaliationDamageStored();
        if (bonus <= 0.0D) {
            return 0f;
        }

        runtimeState.setRetaliationDamageStored(0.0D);
        runtimeState.setRetaliationWindowExpiresAt(0L);
        runtimeState.setRetaliationCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setRetaliationReadyNotified(false);

        if (messenger != null) {
            messenger.accept(playerRef,
                    String.format("Retaliation unleashed! Added %.0f flat damage.", bonus));
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

        if (now < runtimeState.getRetaliationCooldownExpiresAt()) {
            return;
        }

        long windowMillis = settings.windowMillis();
        if (windowMillis <= 0L) {
            return;
        }

        double stored = runtimeState.getRetaliationDamageStored();
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (stored <= 0.0D || windowExpiresAt <= 0L || now > windowExpiresAt) {
            runtimeState.setRetaliationDamageStored(contribution);
            runtimeState.setRetaliationWindowExpiresAt(now + windowMillis);
        } else {
            runtimeState.setRetaliationDamageStored(stored + contribution);
        }
    }

    private void expireWindowIfNeeded(PassiveRuntimeState runtimeState, long now) {
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (windowExpiresAt > 0L && now > windowExpiresAt) {
            runtimeState.setRetaliationWindowExpiresAt(0L);
            runtimeState.setRetaliationDamageStored(0.0D);
        }
    }

    private void clearState(PassiveRuntimeState runtimeState) {
        runtimeState.setRetaliationWindowExpiresAt(0L);
        runtimeState.setRetaliationDamageStored(0.0D);
        runtimeState.setRetaliationCooldownExpiresAt(0L);
        runtimeState.setRetaliationReadyNotified(true);
    }
}
