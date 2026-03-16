package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Map;

public final class AbsoluteFocusAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "absolute_focus";

    private final long guaranteedCritCooldownMillis;
    private final double guaranteedCritChance;
    private final double conversionRatio;

    public AbsoluteFocusAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> guaranteedCrit = AugmentValueReader.getMap(passives, "guaranteed_crit");
        Map<String, Object> excessCrit = AugmentValueReader.getMap(passives, "excess_crit_conversion");

        this.guaranteedCritCooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(guaranteedCrit, "cooldown", 0.0D));
        this.guaranteedCritChance = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(guaranteedCrit, "crit_chance", 1.0D)));
        this.conversionRatio = Math.max(0.0D, AugmentValueReader.getDouble(excessCrit, "conversion_ratio", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        SkillManager skillManager = context.getSkillManager();
        if (skillManager == null || context.getPlayerData() == null) {
            return context.getDamage();
        }

        float originalDamage = context.getDamage();
        double totalBonusMultiplier = resolveExcessCritDamageBonus(skillManager, context);
        boolean activated = false;

        if (guaranteedCritChance > 0.0D
                && AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(),
                        guaranteedCritCooldownMillis)) {
            activated = true;
            if (!context.isCritical()) {
                double ferocity = skillManager.calculatePlayerFerocity(context.getPlayerData());
                totalBonusMultiplier += (ferocity / 100.0D) * guaranteedCritChance;
            }
        }
        float finalDamage = AugmentUtils.applyAdditiveBonusFromBase(
                originalDamage,
                context.getBaseDamage(),
                totalBonusMultiplier);
        if (activated) {
            AugmentUtils.sendAugmentMessage(
                    AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef()),
                    String.format("%s activated! Damage %.2f -> %.2f (+%.2f%%)",
                            getName(),
                            originalDamage,
                            finalDamage,
                            Math.max(0.0D, totalBonusMultiplier * 100.0D)));
        }
        return finalDamage;
    }

    private double resolveExcessCritDamageBonus(SkillManager skillManager, AugmentHooks.HitContext context) {
        if (conversionRatio <= 0.0D) {
            return 0.0D;
        }
        SkillManager.PrecisionBreakdown breakdown = skillManager.getPrecisionBreakdown(context.getPlayerData());
        double rawCritPercent = breakdown.racePercent() + breakdown.skillPercent();
        double rawCritChance = Math.max(0.0D, rawCritPercent / 100.0D);
        double excessCritChance = Math.max(0.0D, rawCritChance - 1.0D);
        return excessCritChance * conversionRatio;
    }
}
