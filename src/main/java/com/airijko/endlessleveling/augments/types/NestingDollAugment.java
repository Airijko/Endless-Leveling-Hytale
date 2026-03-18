package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.player.PlayerData;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class NestingDollAugment extends YamlAugment
        implements AugmentHooks.OnDamageTakenAugment, AugmentHooks.OnLowHpAugment, AugmentHooks.PassiveStatAugment {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String ID = "nesting_doll";
    private static final String MAX_HP_PENALTY_KEY = "EL_" + ID + "_max_hp_penalty";
    private static final String LEGACY_MAX_HP_PENALTY_KEY = ID + "_max_hp_penalty";

    // Short burst immunity so one lethal cluster only consumes one revive.
    private static final long STACK_GRANT_IMMUNITY_MS = 250L;

    private final int maxRevives;
    private final long restoreAfterMillis;

    public NestingDollAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> deathStacks = AugmentValueReader.getMap(passives, "death_stacks");

        int maxDeaths = Math.max(1, AugmentValueReader.getInt(deathStacks, "max_deaths", 1));
        this.maxRevives = Math.max(0, maxDeaths - 1);
        this.restoreAfterMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(deathStacks, "restore_after_seconds", 0.0D));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null || context.getIncomingDamage() <= 0f) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        AugmentState state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.getStacks() > 0 && isWithinStackGrantImmunity(state, now)) {
                return 0f;
            }
        }
        return context.getIncomingDamage();
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null
                || context.getIncomingDamage() <= 0f) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        EntityStatValue hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
            return context.getIncomingDamage();
        }

        double projectedHp = hp.get() - context.getIncomingDamage();
        if (projectedHp > 1.0D) {
            return context.getIncomingDamage();
        }

        AugmentState state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.getStacks() > 0 && isWithinStackGrantImmunity(state, now)) {
                return 0f;
            }

            if (state.getStacks() >= maxRevives) {
                return context.getIncomingDamage();
            }

            int newStacks = state.getStacks() + 1;
            state.setStacks(newStacks);
            state.setLastProc(now);
            state.setExpiresAt(restoreAfterMillis > 0L ? now + restoreAfterMillis : 0L);

            syncMaxHealthPenalty(context.getPlayerData(),
                    context.getDefenderRef(),
                    context.getCommandBuffer(),
                    context.getStatMap(),
                    state);
            EntityStatValue updatedHp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
            float healedTo = updatedHp != null ? Math.max(1.0f, updatedHp.getMax()) : 1.0f;

            AugmentUtils.applyUnkillableThreshold(context.getStatMap(), context.getIncomingDamage(), 1.0f, 1.0f);
            context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), healedTo);

            LOGGER.atFine().log(
                    "[NestingDoll] Revive triggered: player=%s stacks=%d/%d incoming=%.2f healedTo=%.2f",
                    context.getRuntimeState().getPlayerId(),
                    newStacks,
                    maxRevives,
                    context.getIncomingDamage(),
                    healedTo);

            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
            if (playerRef != null && playerRef.isValid()) {
                String msg = String.format("%s triggered! Fully revived (%d/%d).", getName(), newStacks, maxRevives);
                if (newStacks >= maxRevives) {
                    msg = msg + " Next lethal hit will kill you.";
                }
                AugmentUtils.sendAugmentMessage(playerRef, msg);
            }
        }
        return 0f;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        AugmentState state = context.getRuntimeState().getState(ID);

        if (state.getStacks() > 0 && isRestoreWindowCompleteOutOfCombat(context, state)) {
            LOGGER.atFine().log("[NestingDoll] Restore window complete: player=%s stacks=%d -> reset charges",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks());
            clearState(state);
        }

        EntityStatValue hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }

        if (hp.get() <= 0f && state.getStacks() > 0) {
            LOGGER.atFine().log("[NestingDoll] Passive death reset: player=%s stacks=%d hp=%.2f",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks(),
                    hp.get());
            clearState(state);
            return;
        }

        if (state.getStacks() == 0 && state.getStoredValue() == 0.0D && state.getLastProc() == 0L
                && state.getExpiresAt() == 0L) {
            state.setStoredValue(-1.0D);
        }

        syncMaxHealthPenalty(context.getPlayerData(),
                context.getPlayerRef(),
                context.getCommandBuffer(),
                context.getStatMap(),
                state);
        hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }

        if (state.getStacks() > 0 && !isOutOfCombat(context) && restoreAfterMillis > 0L) {
            state.setExpiresAt(System.currentTimeMillis() + restoreAfterMillis);
        }
    }

    private boolean isRestoreWindowCompleteOutOfCombat(AugmentHooks.PassiveStatContext context, AugmentState state) {
        if (state == null || state.getStacks() <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.getExpiresAt() > 0L && now < state.getExpiresAt()) {
            return false;
        }
        if (restoreAfterMillis <= 0L) {
            return true;
        }

        var playerData = context != null ? context.getPlayerData() : null;
        if (playerData == null || playerData.getUuid() == null) {
            return state.getExpiresAt() > 0L && now >= state.getExpiresAt();
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        var passiveManager = plugin != null ? plugin.getPassiveManager() : null;
        if (passiveManager != null) {
            return passiveManager.isOutOfCombat(playerData.getUuid(), restoreAfterMillis);
        }

        return state.getExpiresAt() > 0L && now >= state.getExpiresAt();
    }

    private boolean isOutOfCombat(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getPlayerData() == null || context.getPlayerData().getUuid() == null) {
            return true;
        }
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        var passiveManager = plugin != null ? plugin.getPassiveManager() : null;
        if (passiveManager == null) {
            return true;
        }
        return passiveManager.isOutOfCombat(context.getPlayerData().getUuid(), restoreAfterMillis);
    }

    private void clearState(AugmentState state) {
        if (state == null) {
            return;
        }
        state.clear();
        // Force one safe sync pass after respawn/out-of-combat to remove any
        // lingering max-HP penalty modifier without doing per-tick churn.
        state.setStoredValue(-1.0D);
    }

    private void syncMaxHealthPenalty(PlayerData playerData,
            Ref<EntityStore> playerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            AugmentState state) {
        if (statMap == null || state == null || playerData == null || playerRef == null || commandBuffer == null) {
            return;
        }

        int targetStacks = Math.max(0, Math.min(maxRevives, state.getStacks()));
        int appliedStacks = (int) Math.round(state.getStoredValue());
        if (appliedStacks == targetStacks) {
            return;
        }

        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_PENALTY_KEY);
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), LEGACY_MAX_HP_PENALTY_KEY);

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        var skillManager = plugin != null ? plugin.getSkillManager() : null;
        if (skillManager != null) {
            skillManager.applyHealthModifiers(playerRef, commandBuffer, playerData);
        } else {
            statMap.update();
        }

        state.setStoredValue(targetStacks);
    }

    private boolean isWithinStackGrantImmunity(AugmentState state, long nowMillis) {
        return state != null
                && state.getLastProc() > 0L
                && nowMillis - state.getLastProc() <= STACK_GRANT_IMMUNITY_MS;
    }
}
