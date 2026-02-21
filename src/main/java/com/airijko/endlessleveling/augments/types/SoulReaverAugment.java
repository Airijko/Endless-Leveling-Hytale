package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;

import java.util.Map;

public final class SoulReaverAugment extends YamlAugment
        implements AugmentHooks.OnKillAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "soul_reaver";

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
        if (runtime == null || !AugmentUtils.isCooldownReady(runtime, ID, cooldownMillis)) {
            return;
        }
        var victimStats = context.getVictimStats();
        var hp = victimStats == null ? null
                : victimStats.get(
                        com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }
        double healAmount = hp.getMax() * healPercent;
        var state = runtime.getState(ID);
        state.setStoredValue(state.getStoredValue() + healAmount);
        state.setExpiresAt(System.currentTimeMillis() + 3000L);
        AugmentUtils.markProc(runtime, ID, cooldownMillis);
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return;
        }
        var state = runtime.getState(ID);
        if (state.getStoredValue() <= 0.0D) {
            return;
        }
        double heal = state.getStoredValue();
        var statMap = context.getStatMap();
        if (statMap == null) {
            return;
        }
        float applied = AugmentUtils.heal(statMap, heal);
        state.setStoredValue(Math.max(0.0D, state.getStoredValue() - applied));
    }
}
