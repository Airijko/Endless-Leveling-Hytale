package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class TankEngineAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "tank_engine";
    private static final String MAX_HP_BONUS_KEY = "EL_" + ID + "_max_hp_bonus";
    private static final String LEGACY_MAX_HP_BONUS_KEY = ID + "_max_hp_bonus";

    private final double flatHealthPerStack;
    private final double percentMaxHealthPerStack;
    private final double maxHealthMultiplierAtMaxStacks;
    private final int maxStacks;
    private final long durationMillis;
    private final double decayPerSecond;
    private final boolean excludeFlatFromPercentScaling;

    public TankEngineAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> stacking = AugmentValueReader.getMap(passives, "stacking_health");

        this.flatHealthPerStack = Math.max(0.0D, AugmentValueReader.getDouble(stacking, "flat_health_per_stack", 0.0D));
        this.percentMaxHealthPerStack = Math.max(0.0D,
                AugmentValueReader.getDouble(stacking, "percent_max_health_per_stack", 0.0D));
        this.maxHealthMultiplierAtMaxStacks = Math.max(0.0D,
                AugmentValueReader.getDouble(stacking, "max_health_multiplier_at_max_stacks", 0.0D));
        this.maxStacks = Math.max(1, AugmentValueReader.getInt(stacking, "max_stacks", 1));
        this.durationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(stacking, "duration", 0.0D));
        this.decayPerSecond = Math.max(0.0D, AugmentValueReader.getDouble(stacking, "decay_per_second", 0.0D));
        this.excludeFlatFromPercentScaling = AugmentValueReader.getBoolean(stacking,
                "exclude_flat_from_percent_scaling",
                true);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        gainStack(context.getRuntimeState(), context.getCommandBuffer(), context.getAttackerRef());
        return context.getDamage();
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        gainStack(context.getRuntimeState(), context.getCommandBuffer(), context.getDefenderRef());
        return context.getIncomingDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();

        if (durationMillis > 0L && decayPerSecond > 0.0D && state.getStacks() > 0) {
            long expiresAt = state.getExpiresAt();
            if (expiresAt > 0L && now > expiresAt) {
                double elapsedSeconds = (now - expiresAt) / 1000.0D;
                int decay = (int) Math.floor(elapsedSeconds * decayPerSecond);
                if (decay > 0) {
                    int newStacks = Math.max(0, state.getStacks() - decay);
                    state.setStacks(newStacks);
                    if (newStacks == 0) {
                        state.setExpiresAt(0L);
                    } else {
                        state.setExpiresAt(now); // sliding window: continue decay next tick
                    }
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
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), LEGACY_MAX_HP_BONUS_KEY);
        statMap.update();

        EntityStatValue hpBaseline = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBaseline == null || hpBaseline.getMax() <= 0f) {
            return;
        }

        int safeStacks = Math.max(0, Math.min(maxStacks, stacks));
        double flatBonus = flatHealthPerStack * safeStacks;
        double healthMultiplier = resolveHealthMultiplier(safeStacks);
        double baselineMax = hpBaseline.getMax();

        double targetMax = excludeFlatFromPercentScaling
                ? (baselineMax * healthMultiplier) + flatBonus
                : (baselineMax + flatBonus) * healthMultiplier;
        double totalBonus = targetMax - baselineMax;

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

    private double resolveHealthMultiplier(int safeStacks) {
        if (safeStacks <= 0) {
            return 1.0D;
        }

        if (maxHealthMultiplierAtMaxStacks > 0.0D) {
            double maxMultiplier = Math.max(1.0D, maxHealthMultiplierAtMaxStacks);
            double progress = Math.max(0.0D, Math.min(1.0D, safeStacks / (double) maxStacks));
            return 1.0D + ((maxMultiplier - 1.0D) * progress);
        }

        double percentRatio = Math.max(0.0D, percentMaxHealthPerStack * safeStacks);
        return 1.0D + percentRatio;
    }

    private void gainStack(AugmentRuntimeState runtime,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> entityRef) {
        if (runtime == null) {
            return;
        }

        var state = runtime.getState(ID);
        int current = Math.max(0, state.getStacks());
        if (current < maxStacks) {
            state.setStacks(current + 1);
            if (current == 0) {
                var playerRef = AugmentUtils.getPlayerRef(commandBuffer, entityRef);
                if (playerRef != null && playerRef.isValid()) {
                    AugmentUtils.sendAugmentMessage(playerRef,
                            String.format("%s activated!", getName()));
                }
            }
        }
        if (durationMillis > 0L) {
            state.setExpiresAt(System.currentTimeMillis() + durationMillis);
        }
    }
}