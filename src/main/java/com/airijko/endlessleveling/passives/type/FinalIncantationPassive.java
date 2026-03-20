package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.ExecutionerSettings;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.function.BiConsumer;

/**
 * Applies Final Incantation passive bonus damage against low-health targets.
 */
public final class FinalIncantationPassive {

    private final ExecutionerSettings settings;

    private FinalIncantationPassive(ExecutionerSettings settings) {
        this.settings = settings == null ? new ExecutionerSettings(java.util.List.of(), 0.0D, 0.0D) : settings;
    }

    public static FinalIncantationPassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        return new FinalIncantationPassive(ExecutionerSettings.fromSnapshot(snapshot));
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public float apply(PassiveRuntimeState runtimeState,
            PlayerRef playerRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            float currentDamage,
            BiConsumer<PlayerRef, String> messenger) {
        if (runtimeState == null || !settings.enabled() || targetRef == null || commandBuffer == null
                || currentDamage <= 0f) {
            return 0f;
        }

        EntityStatMap statMap = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            return 0f;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return 0f;
        }

        float current = healthStat.get();
        float max = healthStat.getMax();
        if (max <= 0f || current <= 0f) {
            return 0f;
        }

        float predicted = Math.max(0f, current - currentDamage);
        double flatBonusDamage = 0.0D;
        double percentBonusDamage = 0.0D;
        for (ExecutionerSettings.Entry entry : settings.entries()) {
            double threshold = entry.thresholdPercent();
            if (threshold <= 0.0D) {
                continue;
            }
            float thresholdHealth = (float) (max * threshold);
            if (current <= thresholdHealth || predicted <= thresholdHealth) {
                flatBonusDamage += Math.max(0.0D, entry.flatBonusDamage());
                percentBonusDamage += Math.max(0.0D, entry.bonusDamagePercent());
            }
        }

        if (flatBonusDamage <= 0.0D && percentBonusDamage <= 0.0D) {
            return 0f;
        }

        long cooldownMillis = settings.cooldownMillis();
        long now = System.currentTimeMillis();
        if (cooldownMillis > 0L && now < runtimeState.getExecutionerCooldownExpiresAt()) {
            return 0f;
        }

        double scaledBonus = Math.max(0.0D, currentDamage) * Math.max(0.0D, percentBonusDamage);
        float bonusDamage = (float) (Math.max(0.0D, flatBonusDamage) + Math.max(0.0D, scaledBonus));
        if (bonusDamage <= 0f) {
            return 0f;
        }

        if (cooldownMillis > 0L) {
            runtimeState.setExecutionerCooldownExpiresAt(now + cooldownMillis);
            runtimeState.setExecutionerReadyNotified(false);
        }

        if (messenger != null) {
            messenger.accept(playerRef,
                    String.format("Final Incantation triggered! +%.0f flat, +%.0f%% bonus damage.",
                            flatBonusDamage,
                            percentBonusDamage * 100.0D));
        }

        return bonusDamage;
    }

    public void reduceCooldownOnKill(PassiveRuntimeState runtimeState) {
        if (runtimeState == null || !settings.enabled()) {
            return;
        }

        long reduction = settings.cooldownReductionOnKillMillis();
        if (reduction <= 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        long previousExpiresAt = runtimeState.getExecutionerCooldownExpiresAt();
        if (previousExpiresAt <= now) {
            return;
        }

        long updatedExpiresAt = Math.max(now, previousExpiresAt - reduction);
        if (updatedExpiresAt >= previousExpiresAt) {
            return;
        }

        runtimeState.setExecutionerCooldownExpiresAt(updatedExpiresAt);
        runtimeState.setExecutionerReadyNotified(false);
    }
}
