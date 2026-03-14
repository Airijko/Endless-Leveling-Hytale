package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EndurePainAugment extends YamlAugment
        implements AugmentHooks.OnDamageTakenAugment, AugmentHooks.OnHitAugment, AugmentHooks.OnKillAugment,
        AugmentHooks.PassiveStatAugment {
    public static final String ID = "endure_pain";
    public static final String SHIELD_CAP_STATE_ID = ID + "_shield_cap";
    private static final Map<UUID, Double> LAST_HIT_DAMAGE = new ConcurrentHashMap<>();

    private final double bleedPercent;
    private final double durationSeconds;
    private final double healOnKillPercent;
    private final boolean resetOnKill;

    public EndurePainAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_over_time");
        Map<String, Object> healOnKill = AugmentValueReader.getMap(passives, "heal_on_kill");
        this.bleedPercent = Math.max(0.0D, Math.min(1.0D, AugmentValueReader.getDouble(heal, "value", 0.0D)));
        this.durationSeconds = AugmentValueReader.getDouble(heal, "duration", 3.0D);
        this.healOnKillPercent = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(healOnKill, "value", 0.25D)));
        this.resetOnKill = AugmentValueReader.getBoolean(heal, "reset_on_kill", true);
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        float incoming = context.getIncomingDamage();
        if (runtime == null || bleedPercent <= 0.0D || durationSeconds <= 0.0D || incoming <= 0f) {
            return incoming;
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        var capState = runtime.getState(SHIELD_CAP_STATE_ID);

        if (state.isExpired(now)) {
            state.clear();
            capState.clear();
        }

        double deferred = incoming * bleedPercent;
        float immediate = (float) Math.max(0.0D, incoming - deferred);
        state.setStoredValue(state.getStoredValue() + deferred);
        state.setExpiresAt(now + AugmentUtils.secondsToMillis(durationSeconds));

        capState.setStoredValue(Math.max(capState.getStoredValue(), state.getStoredValue()));
        capState.setExpiresAt(state.getExpiresAt());
        capState.setStacks(1);
        return immediate;
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getPlayerData() == null) {
            return context != null ? context.getDamage() : 0f;
        }
        UUID playerId = context.getPlayerData().getUuid();
        if (playerId != null && context.getDamage() > 0f) {
            LAST_HIT_DAMAGE.put(playerId, (double) context.getDamage());
        }
        return context.getDamage();
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (!resetOnKill || runtime == null) {
            return;
        }

        UUID playerId = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
        double executeDamage = playerId != null ? LAST_HIT_DAMAGE.getOrDefault(playerId, 0.0D) : 0.0D;

        var state = runtime.getState(ID);
        var capState = runtime.getState(SHIELD_CAP_STATE_ID);

        EntityStatMap killerStats = context.getCommandBuffer() != null && context.getKillerRef() != null
                ? context.getCommandBuffer().getComponent(context.getKillerRef(), EntityStatMap.getComponentType())
                : null;
        if (killerStats != null) {
            AugmentUtils.heal(killerStats, executeDamage * healOnKillPercent);
        }
        state.clear();
        capState.clear();
        if (playerId != null) {
            LAST_HIT_DAMAGE.remove(playerId);
        }
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        AugmentRuntimeState runtime = context.getRuntimeState();
        EntityStatMap stats = context.getStatMap();
        if (runtime == null || stats == null) {
            return;
        }
        var state = runtime.getState(ID);
        var capState = runtime.getState(SHIELD_CAP_STATE_ID);
        if (state.getStoredValue() <= 0.0D) {
            capState.clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (state.isExpired(now)) {
            state.clear();
            capState.clear();
            return;
        }

        EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
        if (health == null || health.get() <= 0f) {
            return;
        }

        long remainingMillis = Math.max(1L, state.getExpiresAt() - now);
        double tickMillis = Math.max(0.0D, context.getDeltaSeconds() * 1000.0D);
        if (tickMillis <= 0.0D) {
            return;
        }

        double bleedDamageThisTick = state.getStoredValue() * Math.min(1.0D, tickMillis / remainingMillis);
        if (bleedDamageThisTick <= 0.0D) {
            return;
        }

        float currentHp = health.get();
        float updatedHp = Math.max(0f, (float) (currentHp - bleedDamageThisTick));
        float applied = currentHp - updatedHp;
        if (applied <= 0f) {
            return;
        }
        stats.setStatValue(DefaultEntityStatTypes.getHealth(), updatedHp);
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - applied));
        if (state.getStoredValue() <= 0.0001D) {
            state.clear();
            capState.clear();
        }
    }
}
