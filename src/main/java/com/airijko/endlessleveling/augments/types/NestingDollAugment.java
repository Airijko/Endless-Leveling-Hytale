package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;

import java.util.Map;

public final class NestingDollAugment extends YamlAugment
        implements AugmentHooks.OnLowHpAugment, AugmentHooks.PassiveStatAugment {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String ID = "nesting_doll";
    private static final String MAX_HP_PENALTY_KEY = ID + "_max_hp_penalty";
    // Some damage paths can invoke low-HP handling more than once for a single hit.
    // Keep this guard conservative so only one stack is consumed per lethal event.
    private static final long PROC_GUARD_WINDOW_MS = 1000L;
    // If the engine applies a late lethal update right after a revive proc,
    // recover once within this window to preserve the intended revive.
    private static final long POST_PROC_SURVIVAL_WINDOW_MS = 1500L;

    private final int maxDeaths;
    private final int maxRevives;
    private final double healthPenaltyPerDeath;
    private final long restoreAfterMillis;
    private final double regenPercentPerSecond;

    public NestingDollAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> deathStacks = AugmentValueReader.getMap(passives, "death_stacks");
        Map<String, Object> regeneration = AugmentValueReader.getMap(passives, "regeneration");

        this.maxDeaths = Math.max(1, AugmentValueReader.getInt(deathStacks, "max_deaths", 1));
        this.maxRevives = Math.max(1, this.maxDeaths - 1);
        this.healthPenaltyPerDeath = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(deathStacks, "health_penalty_per_death", 0.0D)));
        this.restoreAfterMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(deathStacks, "restore_after_seconds", 0.0D));
        this.regenPercentPerSecond = Math.max(0.0D,
                AugmentValueReader.getDouble(regeneration, "max_health_regen_percent_per_second", 0.0D));
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

        // Strict lethal crossing check: only consume a stack when this hit would
        // actually kill from above 1 HP.
        float projected = hp.get() - context.getIncomingDamage();
        if (hp.get() <= 1.0f || projected > 0.0f) {
            return context.getIncomingDamage();
        }

        AugmentState state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        if (state.getLastProc() > 0L && now - state.getLastProc() < PROC_GUARD_WINDOW_MS) {
            LOGGER.atFine().log(
                    "[NestingDoll] Proc guard blocked duplicate lethal event: player=%s stacks=%d hp=%.2f incoming=%.2f elapsedMs=%d",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks(),
                    hp.get(),
                    context.getIncomingDamage(),
                    now - state.getLastProc());
            return 0f;
        }
        // After all allowed revives are consumed, the next lethal hit kills.
        if (state.getStacks() >= maxRevives) {
            LOGGER.atFine().log(
                    "[NestingDoll] Death gate reached: player=%s stacks=%d/%d hp=%.2f incoming=%.2f -> allow death and clear state",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks(),
                    maxRevives,
                    hp.get(),
                    context.getIncomingDamage());
            state.clear();
            clearMaxHealthPenalty(context.getStatMap());
            return context.getIncomingDamage();
        }

        if (state.getStoredValue() <= 0.0D) {
            state.setStoredValue(Math.max(1.0D, hp.getMax()));
        }

        int newStacks = state.getStacks() + 1;
        state.setStacks(newStacks);
        state.setLastProc(now);
        state.setExpiresAt(System.currentTimeMillis() + restoreAfterMillis);

        // First prevent death at 1 HP, then restore to full of the reduced health pool.
        float hpBefore = hp.get();
        AugmentUtils.applyUnkillableThreshold(context.getStatMap(), context.getIncomingDamage(), 1.0f, 1.0f);
        float reducedMaxPool = applyMaxHealthPenalty(context.getStatMap(), state);
        context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1.0f, reducedMaxPool));
        LOGGER.atFine().log(
                "[NestingDoll] Revive triggered: player=%s stacks=%d/%d hpBefore=%.2f incoming=%.2f baseline=%.2f reducedMax=%.2f",
                context.getRuntimeState().getPlayerId(),
                newStacks,
                maxRevives,
                hpBefore,
                context.getIncomingDamage(),
                state.getStoredValue(),
                reducedMaxPool);

        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            double totalReductionPercent = Math.min(100.0D, Math.max(0.0D, newStacks * healthPenaltyPerDeath * 100.0D));
            String stackTier = newStacks + "/" + maxRevives;
            String msg = String.format("%s triggered! Max HP reduced by %.0f%% (%s).", getName(),
                    totalReductionPercent, stackTier);
            if (newStacks >= maxRevives) {
                msg = msg + " Next lethal hit will kill you.";
            }
            AugmentUtils.sendAugmentMessage(playerRef, msg);
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
            LOGGER.atFine().log("[NestingDoll] Restore window complete: player=%s stacks=%d -> clearing state",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks());
            state.clear();
            clearMaxHealthPenalty(context.getStatMap());
        }

        EntityStatValue hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }

        // Command/instant kills may bypass low-HP hooks; always reset stacks on death
        // so the player does not get stuck at 3/3 after respawn.
        if (hp.get() <= 0f) {
            long now = System.currentTimeMillis();
            if (state.getStacks() > 0
                    && state.getLastProc() > 0L
                    && now - state.getLastProc() <= POST_PROC_SURVIVAL_WINDOW_MS) {
                float recoveredMax = applyMaxHealthPenalty(context.getStatMap(), state);
                context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1.0f, recoveredMax));
                LOGGER.atFine().log(
                        "[NestingDoll] Post-proc survival correction: player=%s stacks=%d/%d elapsedMs=%d restoredHp=%.2f",
                        context.getRuntimeState().getPlayerId(),
                        state.getStacks(),
                        maxRevives,
                        now - state.getLastProc(),
                        Math.max(1.0f, recoveredMax));
                return;
            }

            LOGGER.atFine().log("[NestingDoll] Passive death reset: player=%s stacks=%d hp=%.2f",
                    context.getRuntimeState().getPlayerId(),
                    state.getStacks(),
                    hp.get());
            state.clear();
            clearMaxHealthPenalty(context.getStatMap());
            return;
        }

        if (state.getStacks() > 0) {
            // While Nesting Doll is active, keep max HP in sync with the locked
            // baseline penalty. This recovers if another system overwrites health mods.
            ensureActivePenaltySync(context.getStatMap(), state, hp, context.getRuntimeState().getPlayerId());

            // Keep restore timer moving only while in combat.
            if (!isOutOfCombat(context)) {
                state.setExpiresAt(System.currentTimeMillis() + restoreAfterMillis);
            }
        }

        if (regenPercentPerSecond > 0.0D && context.getDeltaSeconds() > 0.0D) {
            double heal = hp.getMax() * regenPercentPerSecond * context.getDeltaSeconds();
            AugmentUtils.heal(context.getStatMap(), heal);
        }

        if (state.getStacks() <= 0) {
            clearMaxHealthPenalty(context.getStatMap());
            return;
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

    private float applyMaxHealthPenalty(EntityStatMap statMap, AugmentState state) {
        if (statMap == null || state == null || state.getStacks() <= 0) {
            return 1.0f;
        }

        double baseline = state.getStoredValue() > 0.0D ? state.getStoredValue() : 1.0D;
        float targetMax = (float) Math.max(1.0D, baseline * effectiveHealthRatio(state.getStacks()));
        float delta = (float) (targetMax - baseline);

        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_PENALTY_KEY);
        if (Math.abs(delta) > 0.0001f) {
            statMap.putModifier(DefaultEntityStatTypes.getHealth(),
                    MAX_HP_PENALTY_KEY,
                    new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, delta));
        }
        statMap.update();

        EntityStatValue updatedHp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (updatedHp != null && updatedHp.get() > updatedHp.getMax()) {
            statMap.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1.0f, updatedHp.getMax()));
        }
        return targetMax;
    }

    private void ensureActivePenaltySync(EntityStatMap statMap,
            AugmentState state,
            EntityStatValue hp,
            Object playerId) {
        if (statMap == null || state == null || hp == null || state.getStacks() <= 0) {
            return;
        }
        double baseline = state.getStoredValue() > 0.0D ? state.getStoredValue() : hp.getMax();
        float expectedMax = (float) Math.max(1.0D, baseline * effectiveHealthRatio(state.getStacks()));
        if (Math.abs(hp.getMax() - expectedMax) > 0.5f) {
            LOGGER.atFine().log(
                    "[NestingDoll] Active sync mismatch: player=%s stacks=%d currentMax=%.2f expectedMax=%.2f baseline=%.2f -> reapplying penalty",
                    playerId,
                    state.getStacks(),
                    hp.getMax(),
                    expectedMax,
                    baseline);
            applyMaxHealthPenalty(statMap, state);
        }
    }

    private void clearMaxHealthPenalty(EntityStatMap statMap) {
        if (statMap == null) {
            return;
        }
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_PENALTY_KEY);
        statMap.update();
    }

    private double effectiveHealthRatio(int stacks) {
        return Math.max(0.0D, 1.0D - (healthPenaltyPerDeath * Math.max(0, stacks)));
    }
}
