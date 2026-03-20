package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.FirstStrikeSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.function.BiConsumer;

/**
 * Encapsulates First Strike passive behavior and cooldown handling.
 */
public final class FocusedStrikePassive {

    public record TriggerResult(float bonusDamage, double trueDamageBonus) {
        public static TriggerResult none() {
            return new TriggerResult(0.0f, 0.0D);
        }
    }

    private final FirstStrikeSettings settings;

    private FocusedStrikePassive(FirstStrikeSettings settings) {
        this.settings = settings == null ? FirstStrikeSettings.disabled() : settings;
    }

    public static FocusedStrikePassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new FocusedStrikePassive(FirstStrikeSettings.fromSnapshot(snapshot));
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public long cooldownMillis() {
        return settings.cooldownMillis();
    }

    public double hasteBonusPercent() {
        return Math.max(0.0D, settings.hasteBonusPercent());
    }

    public TriggerResult apply(PassiveRuntimeState runtimeState,
            PlayerRef playerRef,
            float currentDamage,
            BiConsumer<PlayerRef, String> messenger) {
        if (runtimeState == null || !settings.enabled() || currentDamage <= 0f) {
            return TriggerResult.none();
        }

        double bonusPercent = settings.normalBonusDamage() ? Math.max(0.0D, settings.bonusPercent()) : 0.0D;
        double flatBonusDamage = settings.normalBonusDamage() ? Math.max(0.0D, settings.flatBonusDamage()) : 0.0D;
        double trueDamageFlatBonus = Math.max(0.0D, settings.trueDamageFlatBonus());
        double trueDamageConversionPercent = Math.max(0.0D, settings.trueDamageConversionPercent());

        long now = System.currentTimeMillis();
        if (now < runtimeState.getFirstStrikeCooldownExpiresAt()) {
            return TriggerResult.none();
        }

        double bonusDamageTotal = Math.max(0.0D, flatBonusDamage + (Math.max(0.0D, currentDamage) * bonusPercent));
        double trueDamageTotal = Math.max(0.0D,
                trueDamageFlatBonus + (Math.max(0.0D, currentDamage) * trueDamageConversionPercent));

        float bonusDamage = (float) bonusDamageTotal;
        if (bonusDamage <= 0f && trueDamageTotal <= 0.0D) {
            return TriggerResult.none();
        }

        runtimeState.setFirstStrikeCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setFirstStrikeKillResetReady(false);
        runtimeState.setFirstStrikeReadyNotified(false);

        if (messenger != null) {
            messenger.accept(playerRef,
                    String.format("Focused Strike triggered! +%.0f true damage.",
                            trueDamageTotal));
        }
        return new TriggerResult(bonusDamage, trueDamageTotal);
    }

    public void resetCooldownOnKill(PassiveRuntimeState runtimeState) {
        if (runtimeState == null || !settings.enabled() || !settings.resetOnKill()) {
            return;
        }
        runtimeState.setFirstStrikeCooldownExpiresAt(0L);
        runtimeState.setFirstStrikeKillResetReady(true);
        runtimeState.setFirstStrikeReadyNotified(true);
    }
}
