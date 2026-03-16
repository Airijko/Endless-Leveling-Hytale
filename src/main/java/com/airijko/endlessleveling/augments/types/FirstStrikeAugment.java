package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class FirstStrikeAugment extends YamlAugment
        implements AugmentHooks.OnHitAugment, AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "first_strike";

    private final double baseMultiplier;
    private final long cooldownMillis;
    private final Map<String, Object> classValues;

    public FirstStrikeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");
        this.baseMultiplier = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonus, "value", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bonus, "cooldown", 0.0D));
        this.classValues = AugmentValueReader.getMap(bonus, "class_values");
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        double classMultiplier = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentUtils.resolveClassValue(classValues,
                        context.getPlayerData().getPrimaryClassId()));
        double multiplier = classMultiplier > 0 ? classMultiplier : baseMultiplier;
        if (multiplier <= 0.0D) {
            return context.getDamage();
        }
        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.first_strike.triggered",
                            "{0} triggered! +{1}% damage.",
                            getName(), multiplier * 100.0D));
        }
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), multiplier);
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || cooldownMillis <= 0L || context.getIncomingDamage() <= 0f) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        if (!AugmentUtils.isCooldownReady(context.getRuntimeState(), ID, cooldownMillis)) {
            return context.getIncomingDamage();
        }

        if (AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        Lang.tr(playerRef.getUuid(),
                                "augments.first_strike.lost",
                                "{0} lost: you were hit before striking. Cooldown started.",
                                getName()));
            }
        }

        return context.getIncomingDamage();
    }
}
