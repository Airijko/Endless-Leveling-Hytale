package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Map;

public final class MagicBladeAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
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

        SkillManager skillManager = context.getSkillManager();
        if (skillManager != null && context.getPlayerData() != null && sorceryWeaponConversionPercent > 0.0D) {
            double sorceryPercent = skillManager.calculatePlayerSorcery(context.getPlayerData());
            totalMultiplierBonus += (sorceryPercent * sorceryWeaponConversionPercent) / 100.0D;
        }

        if (bonusWeaponType != null && context.getWeaponType() == bonusWeaponType) {
            totalMultiplierBonus += weaponBonusDamage;
        }

        return AugmentUtils.applyAdditiveBonusFromBase(
                context.getDamage(),
                context.getBaseDamage(),
                totalMultiplierBonus);
    }
}
