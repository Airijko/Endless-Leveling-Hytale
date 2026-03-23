package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.ClassWeaponType;

import java.util.Map;

public final class MagicBladeAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "magic_blade";

    private final double sorceryWeaponConversionPercent;
    private final ClassWeaponType bonusWeaponType;
    private final double weaponBonusDamage;

    public MagicBladeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> conversion = AugmentValueReader.getMap(passives, "sorcery_weapon_conversion");
        Map<String, Object> weaponBonus = AugmentValueReader.getMap(passives, "weapon_bonus");

        this.sorceryWeaponConversionPercent = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(conversion,
                        "conversion_percent",
                        0.0D));

        String weaponTypeKey = String.valueOf(weaponBonus.getOrDefault("weapon_type", "")).trim();
        this.bonusWeaponType = ClassWeaponType.fromConfigKey(weaponTypeKey);
        this.weaponBonusDamage = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(weaponBonus,
                        "bonus_damage",
                        0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        double totalMultiplierBonus = 0.0D;

        if (sorceryWeaponConversionPercent > 0.0D) {
            double sorceryPercent = AugmentUtils.resolveSorcery(context);
            totalMultiplierBonus += (sorceryPercent * sorceryWeaponConversionPercent) / 100.0D;
        }

        if (bonusWeaponType != null && context.getWeaponType() == bonusWeaponType) {
            float classWeaponMultiplier = context.getClassWeaponMultiplier();
            double normalizedWeaponBonus = weaponBonusDamage;
            if (classWeaponMultiplier > 0.0f) {
                normalizedWeaponBonus = weaponBonusDamage / classWeaponMultiplier;
            }
            totalMultiplierBonus += normalizedWeaponBonus;
        }

        return AugmentUtils.applyAdditiveBonusFromBase(
                context.getDamage(),
                context.getBaseDamage(),
                totalMultiplierBonus);
    }
}
