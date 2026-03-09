package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.CooldownState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;

import java.util.Map;

public final class TimeMasterAugment extends YamlAugment implements AugmentHooks.OnKillAugment {
    public static final String ID = "time_master";

    private final long flatReductionMillis;
    private final double percentRemainingReduction;

    public TimeMasterAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> cooldownReduction = AugmentValueReader.getMap(passives, "cooldown_reduction");

        this.flatReductionMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(cooldownReduction, "flat_seconds", 0.0D));
        this.percentRemainingReduction = Math.max(0.0D,
                AugmentValueReader.getDouble(cooldownReduction, "percent_remaining", 0.0D));
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context != null ? context.getRuntimeState() : null;
        PlayerData playerData = context != null ? context.getPlayerData() : null;
        if (runtime == null && playerData == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (runtime != null) {
            reduceAugmentCooldowns(runtime, now);
            reduceInternalAugmentCooldowns(runtime, now);
        }
        if (playerData != null) {
            reducePassiveCooldowns(playerData, now);
        }
    }

    private void reduceAugmentCooldowns(AugmentRuntimeState runtime, long now) {
        for (CooldownState cooldown : runtime.getCooldowns()) {
            if (cooldown == null || cooldown.getExpiresAt() <= now) {
                continue;
            }
            long previousExpiresAt = cooldown.getExpiresAt();
            long updatedExpiresAt = reduceExpiresAt(previousExpiresAt, now);
            if (updatedExpiresAt >= previousExpiresAt) {
                continue;
            }
            cooldown.setExpiresAt(updatedExpiresAt);
            cooldown.setReadyNotified(false);

            long reductionApplied = previousExpiresAt - updatedExpiresAt;
            var state = runtime.getState(cooldown.getAugmentId());
            if (state != null && reductionApplied > 0L) {
                if (state.getLastProc() > 0L) {
                    state.setLastProc(Math.max(0L, state.getLastProc() - reductionApplied));
                }
                if (state.getExpiresAt() > 0L) {
                    state.setExpiresAt(Math.max(now, state.getExpiresAt() - reductionApplied));
                }
            }
        }
    }

    private void reducePassiveCooldowns(PlayerData playerData, long now) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        PassiveManager passiveManager = plugin != null ? plugin.getPassiveManager() : null;
        if (passiveManager == null || playerData.getUuid() == null) {
            return;
        }

        PassiveRuntimeState state = passiveManager.getRuntimeState(playerData.getUuid());
        if (state == null) {
            return;
        }

        long secondWind = reduceExpiresAt(state.getSecondWindCooldownExpiresAt(), now);
        if (secondWind < state.getSecondWindCooldownExpiresAt()) {
            state.setSecondWindCooldownExpiresAt(secondWind);
            state.setSecondWindReadyNotified(false);
        }

        long firstStrike = reduceExpiresAt(state.getFirstStrikeCooldownExpiresAt(), now);
        if (firstStrike < state.getFirstStrikeCooldownExpiresAt()) {
            state.setFirstStrikeCooldownExpiresAt(firstStrike);
            state.setFirstStrikeReadyNotified(false);
        }

        long adrenaline = reduceExpiresAt(state.getAdrenalineCooldownExpiresAt(), now);
        if (adrenaline < state.getAdrenalineCooldownExpiresAt()) {
            state.setAdrenalineCooldownExpiresAt(adrenaline);
            state.setAdrenalineReadyNotified(false);
        }

        long executioner = reduceExpiresAt(state.getExecutionerCooldownExpiresAt(), now);
        if (executioner < state.getExecutionerCooldownExpiresAt()) {
            state.setExecutionerCooldownExpiresAt(executioner);
            state.setExecutionerReadyNotified(false);
        }

        long retaliation = reduceExpiresAt(state.getRetaliationCooldownExpiresAt(), now);
        if (retaliation < state.getRetaliationCooldownExpiresAt()) {
            state.setRetaliationCooldownExpiresAt(retaliation);
            state.setRetaliationReadyNotified(false);
        }
    }

    private void reduceInternalAugmentCooldowns(AugmentRuntimeState runtime, long now) {
        long drainCooldownMillis = resolveDrainInternalCooldownMillis();
        reduceInternalLastProcCooldown(runtime, DrainAugment.ID, drainCooldownMillis, now);
        reduceInternalLastProcCooldown(runtime,
                PhantomHitsAugment.ID,
                PhantomHitsAugment.INTERNAL_COOLDOWN_MILLIS,
                now);
    }

    private void reduceInternalLastProcCooldown(AugmentRuntimeState runtime,
            String augmentId,
            long cooldownMillis,
            long now) {
        if (runtime == null || augmentId == null || augmentId.isBlank() || cooldownMillis <= 0L) {
            return;
        }
        var state = runtime.getState(augmentId);
        if (state == null || state.getLastProc() <= 0L) {
            return;
        }
        long elapsed = Math.max(0L, now - state.getLastProc());
        if (elapsed >= cooldownMillis) {
            return;
        }
        long remaining = cooldownMillis - elapsed;
        long reduction = flatReductionMillis + (long) Math.floor(remaining * percentRemainingReduction);
        if (reduction <= 0L) {
            return;
        }

        long updatedRemaining = Math.max(0L, remaining - reduction);
        long adjustedLastProc = now - Math.max(0L, cooldownMillis - updatedRemaining);
        state.setLastProc(Math.max(0L, adjustedLastProc));
    }

    private long resolveDrainInternalCooldownMillis() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getAugmentManager() == null) {
            return 0L;
        }
        AugmentDefinition definition = plugin.getAugmentManager().getAugment(DrainAugment.ID);
        if (definition == null) {
            return 0L;
        }
        return AugmentUtils.secondsToMillis(
                AugmentValueReader.getNestedDouble(definition.getPassives(),
                        0.0D,
                        "bonus_damage_on_hit",
                        "cooldown"));
    }

    private long reduceExpiresAt(long expiresAt, long now) {
        if (expiresAt <= now) {
            return expiresAt;
        }
        long remaining = expiresAt - now;
        long reduction = flatReductionMillis + (long) Math.floor(remaining * percentRemainingReduction);
        if (reduction <= 0L) {
            return expiresAt;
        }
        return Math.max(now, expiresAt - reduction);
    }
}
