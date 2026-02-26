package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class ArcaneInstabilityAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "arcane_instability";

    private final double highManaBonus;
    private final double highManaThreshold;
    private final double lowManaPenalty;
    private final double lowManaThreshold;

    private static double normalizePercentThreshold(double threshold) {
        if (!Double.isFinite(threshold)) {
            return 0.0D;
        }
        double normalized = threshold > 1.0D ? threshold / 100.0D : threshold;
        return AugmentUtils.clampPercent(normalized);
    }

    public ArcaneInstabilityAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        var debuffs = AugmentValueReader.getMap(passives, "debuffs");
        var high = AugmentValueReader.getMap(buffs, "sorcery_bonus_high");
        var highCond = AugmentValueReader.getMap(high, "condition");
        this.highManaBonus = AugmentValueReader.getDouble(high, "value", 0.0D);
        this.highManaThreshold = normalizePercentThreshold(
                AugmentValueReader.getNestedDouble(highCond, 1.0D, "min_percent"));

        var low = AugmentValueReader.getMap(debuffs, "sorcery_penalty_low");
        var lowCond = AugmentValueReader.getMap(low, "condition");
        this.lowManaPenalty = AugmentValueReader.getDouble(low, "value", 0.0D);
        this.lowManaThreshold = normalizePercentThreshold(
                AugmentValueReader.getNestedDouble(lowCond, 0.0D, "max_percent"));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        var runtime = context.getRuntimeState();
        var statMap = context.getStatMap();
        if (runtime == null || statMap == null) {
            return;
        }

        double maxMana = AugmentUtils.getMaxMana(statMap);
        if (maxMana <= 0.0D) {
            AugmentUtils.setAttributeBonus(runtime, ID + "_sorc", SkillAttributeType.SORCERY, 0.0D, 0L);
            return;
        }

        double currentMana = AugmentUtils.getCurrentMana(statMap);
        double manaPercent = AugmentUtils.clampPercent(currentMana / maxMana);
        double sorceryDelta = 0.0D;

        if (manaPercent >= highManaThreshold) {
            sorceryDelta = highManaBonus * 100.0D;
        } else if (manaPercent <= lowManaThreshold) {
            sorceryDelta = lowManaPenalty * 100.0D;
        }

        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryDelta,
                0L);
    }
}
