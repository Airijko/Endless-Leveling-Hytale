package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.augments.AugmentHooks.HitContext;
import com.airijko.endlessleveling.augments.AugmentHooks.OnLowHpAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared dispatch helpers used by both player and mob augment executors.
 */
public final class AugmentDispatch {

    private AugmentDispatch() {
    }

    public record OnHitResult(float damage, double trueDamageBonus) {
    }

    static boolean isMiss(HitContext context) {
        if (context == null || context.getDamage() <= 0.0f) {
            return true;
        }
        if (context.getTargetRef() == null || !context.getTargetRef().isValid()) {
            return true;
        }
        EntityStatMap targetStats = context.getTargetStats();
        if (targetStats == null) {
            return true;
        }
        EntityStatValue targetHp = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
            return true;
        }
        return context.getAttackerRef() != null && context.getAttackerRef().equals(context.getTargetRef());
    }

    static int lowHpPriority(Augment augment) {
        String id = augment == null ? null : augment.getId();
        if (id == null) {
            return Integer.MAX_VALUE;
        }
        return switch (id.trim().toLowerCase()) {
            case "rebirth" -> 0;
            case "fortress" -> 1;
            case "undying_rage" -> 2;
            case "nesting_doll" -> 3;
            case "bailout" -> 4;
            default -> Integer.MAX_VALUE;
        };
    }

    static List<Augment> resolveLowHpTriggerOrder(List<Augment> augments) {
        List<Augment> lowHpAugments = new ArrayList<>();
        for (Augment augment : augments) {
            if (augment instanceof OnLowHpAugment) {
                lowHpAugments.add(augment);
            }
        }
        if (lowHpAugments.size() <= 1) {
            return lowHpAugments;
        }
        lowHpAugments.sort((a, b) -> Integer.compare(lowHpPriority(a), lowHpPriority(b)));
        return lowHpAugments;
    }
}
