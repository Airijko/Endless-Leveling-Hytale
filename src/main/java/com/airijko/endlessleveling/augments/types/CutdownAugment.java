package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class CutdownAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "cutdown";

    private record Threshold(double targetHealthPercentAbove, double value) {
    }

    private final List<Threshold> thresholds;

    public CutdownAugment(AugmentDefinition definition) {
        super(definition);
        this.thresholds = parseThresholds(
                AugmentValueReader.getMap(definition.getPassives(), "bonus_damage_vs_high_hp"));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || thresholds.isEmpty() || context.getTargetStats() == null) {
            return context != null ? context.getDamage() : 0f;
        }
        EntityStatValue hp = context.getTargetStats().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }

        double ratio = hp.get() / hp.getMax();
        for (Threshold threshold : thresholds) {
            if (ratio > threshold.targetHealthPercentAbove()) {
                return AugmentUtils.applyAdditiveBonusFromBase(
                        context.getDamage(),
                        context.getBaseDamage(),
                        threshold.value());
            }
        }
        return context.getDamage();
    }

    private List<Threshold> parseThresholds(Map<String, Object> bonusDamageVsHighHp) {
        List<Threshold> parsed = new ArrayList<>();
        Object rawThresholds = bonusDamageVsHighHp.get("thresholds");
        if (!(rawThresholds instanceof List<?> list)) {
            return parsed;
        }

        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> thresholdNode = (Map<String, Object>) map;
            double healthAbove = Math.max(0.0D,
                    Math.min(1.0D, AugmentValueReader.getDouble(thresholdNode, "target_health_percent_above", 0.0D)));
            double value = AugmentUtils
                    .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(thresholdNode, "value", 0.0D));
            if (value > 0.0D) {
                parsed.add(new Threshold(healthAbove, value));
            }
        }

        parsed.sort(Comparator.comparingDouble(Threshold::targetHealthPercentAbove).reversed());
        return parsed;
    }
}
