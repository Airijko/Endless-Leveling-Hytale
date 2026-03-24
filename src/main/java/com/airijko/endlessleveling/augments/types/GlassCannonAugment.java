package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;

import java.util.Map;

public final class GlassCannonAugment extends Augment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "glass_cannon";
    private static final String MAX_HP_PENALTY_KEY = "EL_" + ID + "_max_hp_penalty";
    private static final String LEGACY_MAX_HP_PENALTY_KEY = ID + "_max_hp_penalty";

    private final double movementSpeedBonus;
    private final double bonusDamage;
    private final double maxHealthPenaltyPercent;

    public GlassCannonAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> healthPenalty = AugmentValueReader.getMap(passives, "health_penalty");
        Map<String, Object> movementSpeed = AugmentValueReader.getMap(buffs, "movement_speed");
        Map<String, Object> bonusDamageNode = AugmentValueReader.getMap(buffs, "bonus_damage");

        this.movementSpeedBonus = AugmentValueReader.getDouble(movementSpeed, "value", 0.0D);
        this.bonusDamage = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonusDamageNode, "value", 0.0D));
        this.maxHealthPenaltyPercent = AugmentUtils.normalizeConfiguredDebuffMultiplier(
            AugmentValueReader.getDouble(healthPenalty, "max_health_percent", 0.0D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_haste",
                SkillAttributeType.HASTE,
                movementSpeedBonus * 100.0D,
                0L);

        if (context.getPlayerData() == null) {
            applyMaxHealthPenalty(context.getStatMap());
        }
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), bonusDamage);
    }

    private void applyMaxHealthPenalty(EntityStatMap statMap) {
        if (statMap == null) {
            return;
        }

        EntityStatValue hpBefore = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBefore == null || hpBefore.getMax() <= 0f) {
            return;
        }
        float previousMax = hpBefore.getMax();
        float previousCurrent = hpBefore.get();

        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_PENALTY_KEY);
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), LEGACY_MAX_HP_PENALTY_KEY);
        statMap.update();

        EntityStatValue hpBaseline = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBaseline == null || hpBaseline.getMax() <= 0f) {
            return;
        }

        double baselineMax = hpBaseline.getMax();
        double targetMax = baselineMax * Math.max(0.0D, 1.0D + maxHealthPenaltyPercent);
        double totalPenalty = targetMax - baselineMax;

        if (Math.abs(totalPenalty) > 0.0001D) {
            statMap.putModifier(DefaultEntityStatTypes.getHealth(),
                    MAX_HP_PENALTY_KEY,
                    new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, (float) totalPenalty));
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
