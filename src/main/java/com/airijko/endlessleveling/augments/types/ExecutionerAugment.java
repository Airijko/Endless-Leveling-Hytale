package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class ExecutionerAugment extends YamlAugment implements AugmentHooks.OnTargetConditionAugment {
    public static final String ID = "executioner";

    private final double bonusMultiplier;
    private final double thresholdRatio;
    private final long cooldownMillis;

    public ExecutionerAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");
        this.bonusMultiplier = AugmentValueReader.getDouble(bonus, "value", 0.0D);
        this.thresholdRatio = AugmentValueReader.getDouble(bonus, "threshold", 0.0D);
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bonus, "cooldown", 0.0D));
    }

    @Override
    public float onTargetCondition(AugmentHooks.HitContext context) {
        EntityStatMap targetStats = context.getTargetStats();
        if (targetStats == null) {
            return context.getDamage();
        }
        var hp = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getDamage();
        }
        if (thresholdRatio <= 0.0D) {
            return context.getDamage();
        }

        float currentHp = hp.get();
        float thresholdHp = (float) (hp.getMax() * thresholdRatio);
        float incomingDamage = Math.max(0f, context.getDamage());
        float predictedHp = Math.max(0f, currentHp - incomingDamage);
        if (currentHp > thresholdHp && predictedHp > thresholdHp) {
            return context.getDamage();
        }

        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.executioner.triggered",
                            "{0} triggered! +{1}% damage.",
                            getName(), bonusMultiplier * 100.0D));
        }
        return AugmentUtils.applyMultiplier(context.getDamage(), bonusMultiplier);
    }
}
