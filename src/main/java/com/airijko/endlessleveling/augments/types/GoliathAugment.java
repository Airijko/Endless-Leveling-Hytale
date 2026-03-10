package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;

import java.util.Map;

public final class GoliathAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "goliath";
    private static final String MAX_HP_BONUS_KEY = ID + "_max_hp_bonus";

    private final double maxHealthPercentBonus;
    private final double strengthPercentBonus;
    private final double sorceryPercentBonus;

    public GoliathAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.maxHealthPercentBonus = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs,
                        AugmentValueReader.getNestedDouble(buffs, 0.0D, "health_percent", "value"),
                        "max_health_percent",
                        "value"));
        this.strengthPercentBonus = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "strength", "value"));
        this.sorceryPercentBonus = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "sorcery", "value"));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        applyMaxHealthBonus(context.getStatMap(), maxHealthPercentBonus);

        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthPercentBonus * 100.0D,
                0L);
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryPercentBonus * 100.0D,
                0L);
    }

    private void applyMaxHealthBonus(EntityStatMap statMap, double percentBonus) {
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

        double totalBonus = hpBaseline.getMax() * Math.max(0.0D, percentBonus);
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
