package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.util.Map;

public final class GlassCannonAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "glass_cannon";

    private final double movementSpeedBonus;
    private final double bonusDamage;

    public GlassCannonAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> movementSpeed = AugmentValueReader.getMap(buffs, "movement_speed");
        Map<String, Object> bonusDamageNode = AugmentValueReader.getMap(buffs, "bonus_damage");

        this.movementSpeedBonus = AugmentValueReader.getDouble(movementSpeed, "value", 0.0D);
        this.bonusDamage = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonusDamageNode, "value", 0.0D));
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
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), bonusDamage);
    }
}
