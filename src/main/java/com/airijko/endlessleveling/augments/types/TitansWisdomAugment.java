package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

public final class TitansWisdomAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "titans_wisdom";

    private final double percentOfHealthToSorcery;
    private final double hasteDebuff;

    public TitansWisdomAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var buffs = AugmentValueReader.getMap(passives, "buffs");
        double percentFromValue = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery_from_max_health", "value"));
        // Backward-compatible fallback for older config naming.
        double conversionPercentFallback = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery_from_max_health", "conversion_percent"));
        this.percentOfHealthToSorcery = percentFromValue > 0.0D
                ? percentFromValue
                : conversionPercentFallback;
        var debuffs = AugmentValueReader.getMap(passives, "debuffs");
        this.hasteDebuff = AugmentUtils.normalizeConfiguredDebuffMultiplier(
            AugmentValueReader.getNestedDouble(debuffs, 0.0D, "haste", "value"));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null) {
            return;
        }
        var runtime = context.getRuntimeState();
        var statMap = context.getStatMap();
        if (runtime == null || statMap == null) {
            return;
        }

        double maxHealth = AugmentUtils.getMaxHealth(statMap);
        double sorceryBonus = maxHealth * percentOfHealthToSorcery;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBonus,
                0L);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteDebuff * 100.0D,
                0L);
    }
}
