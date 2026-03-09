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
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;

import java.util.Map;

public final class TankEngineAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "tank_engine";
    private static final String MAX_HP_BONUS_KEY = ID + "_max_hp_bonus";

    private final double flatHealthPerStack;
    private final double percentMaxHealthPerStack;
    private final double percentCap;
    private final int maxStacks;
    private final long durationPerStackMillis;
    private final boolean excludeFlatFromPercentScaling;

    public TankEngineAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> stacking = AugmentValueReader.getMap(passives, "stacking_health");

        this.flatHealthPerStack = Math.max(0.0D, AugmentValueReader.getDouble(stacking, "flat_health_per_stack", 0.0D));
        this.percentMaxHealthPerStack = Math.max(0.0D,
                AugmentValueReader.getDouble(stacking, "percent_max_health_per_stack", 0.0D));
        this.percentCap = Math.max(0.0D, AugmentValueReader.getDouble(stacking, "percent_cap", 0.0D));
        this.maxStacks = Math.max(1, AugmentValueReader.getInt(stacking, "max_stacks", 1));
        this.durationPerStackMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(stacking, "duration_per_stack", 0.0D));
        this.excludeFlatFromPercentScaling = AugmentValueReader.getBoolean(stacking,
                "exclude_flat_from_percent_scaling",
                true);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        var state = context.getRuntimeState().getState(ID);
        int current = Math.max(0, state.getStacks());
        if (current < maxStacks) {
            state.setStacks(current + 1);
            if (current == 0) {
                var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
                if (playerRef != null && playerRef.isValid()) {
                    AugmentUtils.sendAugmentMessage(playerRef,
                            String.format("%s activated!", getName()));
                }
            }
        }
        state.setLastProc(System.currentTimeMillis());
        return context.getDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();

        if (durationPerStackMillis > 0L && state.getStacks() > 0) {
            long anchor = state.getLastProc();
            if (anchor > 0L && now > anchor) {
                long elapsed = now - anchor;
                int decay = (int) (elapsed / durationPerStackMillis);
                if (decay > 0) {
                    state.setStacks(Math.max(0, state.getStacks() - decay));
                    state.setLastProc(anchor + (long) decay * durationPerStackMillis);
                }
            }
        }

        applyMaxHealthBonus(context.getStatMap(), state.getStacks());
    }

    private void applyMaxHealthBonus(EntityStatMap statMap, int stacks) {
        if (statMap == null) {
            return;
        }

        EntityStatValue hpBefore = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBefore == null || hpBefore.getMax() <= 0f) {
            return;
        }
        float previousMax = hpBefore.getMax();
        float previousCurrent = hpBefore.get();

        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_BONUS_KEY);
        statMap.update();

        EntityStatValue hpBaseline = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBaseline == null || hpBaseline.getMax() <= 0f) {
            return;
        }

        int safeStacks = Math.max(0, Math.min(maxStacks, stacks));
        double flatBonus = flatHealthPerStack * safeStacks;
        double percentRatio = Math.min(percentCap, percentMaxHealthPerStack * safeStacks);
        double percentBase = excludeFlatFromPercentScaling
                ? hpBaseline.getMax()
                : (hpBaseline.getMax() + flatBonus);
        double totalBonus = flatBonus + (percentBase * Math.max(0.0D, percentRatio));

        if (Math.abs(totalBonus) > 0.0001D) {
            statMap.putModifier(DefaultEntityStatTypes.getHealth(),
                    MAX_HP_BONUS_KEY,
                    new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, (float) totalBonus));
            statMap.update();
        }

        EntityStatValue hpUpdated = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpUpdated == null || hpUpdated.getMax() <= 0f) {
            return;
        }
        float newMax = hpUpdated.getMax();
        float ratio = previousMax > 0.01f ? previousCurrent / previousMax : 1.0f;
        float adjustedCurrent = Math.max(1.0f, Math.min(newMax, ratio * newMax));
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), adjustedCurrent);
    }
}