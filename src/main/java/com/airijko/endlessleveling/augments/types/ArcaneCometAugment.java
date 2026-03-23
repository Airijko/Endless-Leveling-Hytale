package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.player.SkillManager;

import java.util.Map;

public final class ArcaneCometAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "arcane_comet";

    private final double flatBonusDamage;
    private final double sorceryRatio;
    private final long cooldownMillis;

    public ArcaneCometAugment(AugmentDefinition definition) {
        super(definition);

        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");

        this.flatBonusDamage = Math.max(0.0D, AugmentValueReader.getDouble(bonus, "value", 0.0D));
        this.sorceryRatio = Math.max(0.0D, AugmentValueReader.getDouble(bonus, "sorcery_ratio", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bonus, "cooldown", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        if (flatBonusDamage <= 0.0D && sorceryRatio <= 0.0D) {
            return context.getDamage();
        }

        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        double sorcery = AugmentUtils.resolveSorcery(context);

        double bonusDamage = flatBonusDamage + (sorcery * sorceryRatio);
        if (bonusDamage <= 0.0D) {
            return context.getDamage();
        }

        return context.getDamage() + (float) bonusDamage;
    }
}
