package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Locale;
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
        if (!AugmentUtils.isCooldownReady(context.getRuntimeState(), ID, cooldownMillis)) {
            return context.getDamage();
        }
        double classMultiplier = resolveClassValue(context.getPlayerData().getPrimaryClassId());
        double multiplier = baseMultiplier > 0 ? baseMultiplier : classMultiplier;
        if (classMultiplier > 0) {
            multiplier = classMultiplier;
        }
        if (multiplier <= 0.0D) {
            return context.getDamage();
        }
        AugmentUtils.markProc(context.getRuntimeState(), ID, cooldownMillis);
        return (float) (context.getDamage() * (1.0D + multiplier));
    }

    private double resolveClassValue(String classId) {
        if (classId == null || classValues == null || classValues.isEmpty()) {
            return 0.0D;
        }
        Object val = classValues.get(classId.trim().toLowerCase(Locale.ROOT));
        if (val instanceof Map<?, ?> inner) {
            Object value = inner.get("value");
            if (value instanceof Number num) {
                return num.doubleValue();
            }
        }
        return 0.0D;
    }
}
