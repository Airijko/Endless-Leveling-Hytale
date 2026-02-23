package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class FirstStrikeAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "first_strike";

    private final double baseMultiplier;
    private final long cooldownMillis;
    private final Map<String, Object> classValues;

    public FirstStrikeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");
        this.baseMultiplier = AugmentValueReader.getDouble(bonus, "value", 0.0D);
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bonus, "cooldown", 0.0D));
        this.classValues = AugmentValueReader.getMap(bonus, "class_values");
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        double classMultiplier = AugmentUtils.resolveClassValue(classValues,
                context.getPlayerData().getPrimaryClassId());
        double multiplier = classMultiplier > 0 ? classMultiplier : baseMultiplier;
        if (multiplier <= 0.0D) {
            return context.getDamage();
        }
        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, cooldownMillis)) {
            return context.getDamage();
        }
        return AugmentUtils.applyMultiplier(context.getDamage(), multiplier);
    }
}
