package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
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

public final class RaidBossAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "raid_boss";
    private static final String MAX_HP_BONUS_KEY = "EL_" + ID + "_max_hp_bonus";
    private static final String LEGACY_MAX_HP_BONUS_KEY = ID + "_max_hp_bonus";

    private final double maxHealthPercentBonus;
    private final double bonusDamage;

    public RaidBossAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.maxHealthPercentBonus = Math.max(0.0D,
                AugmentValueReader.getNestedDouble(buffs,
                        AugmentValueReader.getNestedDouble(buffs, 0.0D, "health_percent", "value"),
                        "max_health_percent",
                        "value"));
        this.bonusDamage = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getNestedDouble(buffs, 0.0D, "bonus_damage", "value"));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getStatMap() == null) {
            return;
        }
        applyMaxHealthBonus(context.getStatMap(), maxHealthPercentBonus);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), bonusDamage);
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
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), LEGACY_MAX_HP_BONUS_KEY);
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
