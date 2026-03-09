package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class BloodthirsterAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "bloodthirster";

    private static final int MODE_NONE = 0;
    private static final int MODE_HEALTHY = 1;
    private static final int MODE_WOUNDED = 2;

    private final double healthyThresholdAbove;
    private final int healthyHitCounter;
    private final double healthyBonusDamage;
    private final double healthySelfDamagePercentOfCurrent;

    private final double woundedThresholdBelow;
    private final int woundedHitCounter;
    private final double woundedMissingHealthPercent;
    private final double woundedStrengthScaling;
    private final double woundedSorceryScaling;

    public BloodthirsterAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> healthyState = AugmentValueReader.getMap(passives, "healthy_state");
        Map<String, Object> healthyBonus = AugmentValueReader.getMap(healthyState, "bonus_damage");
        Map<String, Object> healthySelfDamage = AugmentValueReader.getMap(healthyState, "self_damage");
        Map<String, Object> woundedState = AugmentValueReader.getMap(passives, "wounded_state");
        Map<String, Object> woundedHealing = AugmentValueReader.getMap(woundedState, "healing");

        this.healthyThresholdAbove = clampRatio(
                AugmentValueReader.getDouble(healthyState, "health_threshold_above", 0.50D));
        this.healthyHitCounter = Math.max(1, AugmentValueReader.getInt(healthyState, "hit_counter", 3));
        this.healthyBonusDamage = Math.max(0.0D, AugmentValueReader.getDouble(healthyBonus, "value", 0.0D));
        this.healthySelfDamagePercentOfCurrent = clampRatio(
                AugmentValueReader.getDouble(healthySelfDamage, "percent_of_current_hp", 0.0D));

        this.woundedThresholdBelow = clampRatio(
                AugmentValueReader.getDouble(woundedState, "health_threshold_below", 0.50D));
        this.woundedHitCounter = Math.max(1, AugmentValueReader.getInt(woundedState, "hit_counter", 3));
        this.woundedMissingHealthPercent = clampRatio(
                AugmentValueReader.getDouble(woundedHealing, "missing_health_percent", 0.0D));
        this.woundedStrengthScaling = Math.max(0.0D,
                AugmentValueReader.getDouble(woundedHealing, "strength_scaling", 0.0D));
        this.woundedSorceryScaling = Math.max(0.0D,
                AugmentValueReader.getDouble(woundedHealing, "sorcery_scaling", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        EntityStatMap attackerStats = context.getAttackerStats();
        EntityStatValue hp = attackerStats == null ? null : attackerStats.get(DefaultEntityStatTypes.getHealth());
        if (runtime == null || hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }

        double healthRatio = Math.max(0.0D, Math.min(1.0D, hp.get() / hp.getMax()));
        int mode = healthRatio > healthyThresholdAbove ? MODE_HEALTHY
                : (healthRatio <= woundedThresholdBelow ? MODE_WOUNDED : MODE_NONE);
        if (mode == MODE_NONE) {
            return context.getDamage();
        }

        var state = runtime.getState(ID);
        int previousMode = (int) state.getStoredValue();
        if (previousMode != mode) {
            state.setStacks(0);
            state.setStoredValue(mode);
        }

        int hitsRequired = mode == MODE_HEALTHY ? healthyHitCounter : woundedHitCounter;
        int nextHits = state.getStacks() + 1;
        if (nextHits < hitsRequired) {
            state.setStacks(nextHits);
            return context.getDamage();
        }

        state.setStacks(0);
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());

        if (mode == MODE_HEALTHY) {
            float outgoing = AugmentUtils.applyMultiplier(context.getDamage(), healthyBonusDamage);
            applyHealthySelfDamage(attackerStats, healthySelfDamagePercentOfCurrent);
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        String.format("%s activated! +%.0f%% damage (healthy state).",
                                getName(), healthyBonusDamage * 100.0D));
            }
            return outgoing;
        }

        double healAmount = resolveWoundedHealing(context, hp);
        float healed = AugmentUtils.heal(attackerStats, healAmount);
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    String.format("%s activated! Healed %.1f HP (wounded state).", getName(), healed));
        }
        return context.getDamage();
    }

    private double resolveWoundedHealing(AugmentHooks.HitContext context, EntityStatValue hp) {
        if (hp == null || hp.getMax() <= 0f || woundedMissingHealthPercent <= 0.0D) {
            return 0.0D;
        }
        double missing = Math.max(0.0D, hp.getMax() - hp.get());
        double baseHeal = missing * woundedMissingHealthPercent;

        SkillManager skillManager = context.getSkillManager();
        if (skillManager == null || context.getPlayerData() == null) {
            return baseHeal;
        }

        double strength = Math.max(0.0D, skillManager.calculatePlayerStrength(context.getPlayerData()));
        double sorcery = Math.max(0.0D, skillManager.calculatePlayerSorcery(context.getPlayerData()));
        double scalingMultiplier = 1.0D
                + ((strength * woundedStrengthScaling) / 100.0D)
                + ((sorcery * woundedSorceryScaling) / 100.0D);
        return Math.max(0.0D, baseHeal * scalingMultiplier);
    }

    private void applyHealthySelfDamage(EntityStatMap statMap, double percentOfCurrent) {
        if (statMap == null || percentOfCurrent <= 0.0D) {
            return;
        }
        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.get() <= 0f) {
            return;
        }
        float current = hp.get();
        float selfDamage = (float) (current * percentOfCurrent);
        float updated = Math.max(1.0f, current - selfDamage);
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), updated);
    }

    private double clampRatio(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}