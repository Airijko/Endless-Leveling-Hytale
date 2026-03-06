package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class SoulReaverAugment extends YamlAugment
        implements AugmentHooks.OnKillAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "soul_reaver";
    private static final long HEAL_WINDOW_MILLIS = 3000L;

    private final double healPercent;
    private final long cooldownMillis;

    public SoulReaverAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_on_kill");
        this.healPercent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(heal, "cooldown", 0.0D));
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null || !AugmentUtils.consumeCooldown(runtime, ID, cooldownMillis)) {
            return;
        }
        var victimStats = context.getVictimStats();
        var hp = victimStats == null ? null
                : victimStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }
        double healAmount = hp.getMax() * healPercent;
        if (healAmount <= 0.0D) {
            return;
        }

        double maxPlayerHealth = resolveKillerMaxHealth(context);
        if (maxPlayerHealth > 0.0D) {
            healAmount = Math.min(healAmount, maxPlayerHealth);
        }

        var state = runtime.getState(ID);
        double nextStored = state.getStoredValue() + healAmount;
        if (maxPlayerHealth > 0.0D) {
            nextStored = Math.min(nextStored, maxPlayerHealth);
        }
        state.setStoredValue(nextStored);
        state.setExpiresAt(System.currentTimeMillis() + HEAL_WINDOW_MILLIS);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        long expiresAt = state.getExpiresAt();
        if (expiresAt > 0L && now > expiresAt) {
            state.setStoredValue(0.0D);
            state.setExpiresAt(0L);
            return;
        }
        if (state.getStoredValue() <= 0.0D) {
            state.setExpiresAt(0L);
            return;
        }
        double heal = state.getStoredValue();
        var statMap = context.getStatMap();
        if (statMap == null) {
            return;
        }
        float applied = AugmentUtils.heal(statMap, heal);
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - applied));
        if (state.getStoredValue() <= 0.0D) {
            state.setExpiresAt(0L);
        }
    }

    private double resolveKillerMaxHealth(AugmentHooks.KillContext context) {
        if (context == null || context.getCommandBuffer() == null || context.getKillerRef() == null) {
            return 0.0D;
        }
        var killerStats = context.getCommandBuffer().getComponent(
                context.getKillerRef(),
                com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
        if (killerStats == null) {
            return 0.0D;
        }
        var health = killerStats.get(DefaultEntityStatTypes.getHealth());
        if (health == null || health.getMax() <= 0f) {
            return 0.0D;
        }
        return health.getMax();
    }
}
