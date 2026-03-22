package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.FirstStrikeSettings;
import com.airijko.endlessleveling.util.FirstStrikeTriggerEffects;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

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
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            float currentDamage,
            BiConsumer<PlayerRef, String> messenger) {
        if (runtimeState == null || !settings.enabled() || currentDamage <= 0f) {
            return TriggerResult.none();
        }
        long now = System.currentTimeMillis();
        long cooldownExpiresAt = runtimeState.getFirstStrikeCooldownExpiresAt();
        if (cooldownExpiresAt > now) {
            return TriggerResult.none();
        }

        runtimeState.setFirstStrikeHasteActiveUntil(now + settings.hasteDurationMillis());
        FirstStrikeTriggerEffects.play(targetRef, commandBuffer);
        if (settings.cooldownMillis() > 0L) {
            runtimeState.setFirstStrikeCooldownExpiresAt(now + settings.cooldownMillis());
            runtimeState.setFirstStrikeReadyNotified(false);
            runtimeState.setFirstStrikeKillResetReady(false);
        }
        return new TriggerResult(0.0f, Math.max(0.0D, settings.trueDamageFlatBonus()));
    }

    public void resetCooldownOnKill(PassiveRuntimeState runtimeState) {
        if (runtimeState == null || !settings.enabled() || !settings.resetOnKill()) {
            return;
        }

        long now = System.currentTimeMillis();
        long currentExpiresAt = runtimeState.getFirstStrikeCooldownExpiresAt();
        if (currentExpiresAt <= now) {
            return;
        }

        long remaining = currentExpiresAt - now;
        long reducedRemaining = Math.max(0L, (long) Math.ceil(remaining / 2.0D));
        runtimeState.setFirstStrikeCooldownExpiresAt(now + reducedRemaining);
        runtimeState.setFirstStrikeKillResetReady(true);
        runtimeState.setFirstStrikeReadyNotified(reducedRemaining <= 0L);
    }
}
