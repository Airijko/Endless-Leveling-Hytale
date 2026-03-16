package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;

import java.util.Map;

public final class SnipersReachAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "snipers_reach";

    private final double maxValue;
    private final double minDistance;
    private final double maxDistance;
    private final String scalingType;

    public SnipersReachAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage_by_distance");
        this.maxValue = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonus, "max_value", 0.0D));
        this.minDistance = Math.max(0.0D, AugmentValueReader.getDouble(bonus, "min_distance", 0.0D));
        this.maxDistance = Math.max(minDistance, AugmentValueReader.getDouble(bonus, "max_distance", minDistance));
        this.scalingType = String.valueOf(bonus.getOrDefault("scaling_type", "linear")).trim().toLowerCase();
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        double distance = resolveDistance(context);
        if (maxValue <= 0.0D) {
            return context.getDamage();
        }

        double normalized;
        if (distance <= minDistance) {
            normalized = 0.0D;
        } else if (distance >= maxDistance) {
            normalized = 1.0D;
        } else if (maxDistance <= minDistance) {
            normalized = 1.0D;
        } else {
            normalized = (distance - minDistance) / (maxDistance - minDistance);
        }
        normalized = clamp01(normalized);

        if (!"linear".equals(scalingType)) {
            // Default to linear for unsupported scaling modes.
        }

        double bonus = maxValue * normalized;
        return AugmentUtils.applyAdditiveBonusFromBase(context.getDamage(), context.getBaseDamage(), bonus);
    }

    private double resolveDistance(AugmentHooks.HitContext context) {
        if (context.getCommandBuffer() == null || context.getAttackerRef() == null || context.getTargetRef() == null) {
            return 0.0D;
        }
        TransformComponent attackerTransform = context.getCommandBuffer().getComponent(
                context.getAttackerRef(),
                TransformComponent.getComponentType());
        TransformComponent targetTransform = context.getCommandBuffer().getComponent(
                context.getTargetRef(),
                TransformComponent.getComponentType());
        if (attackerTransform == null || targetTransform == null
                || attackerTransform.getPosition() == null || targetTransform.getPosition() == null) {
            return 0.0D;
        }
        double dx = attackerTransform.getPosition().getX() - targetTransform.getPosition().getX();
        double dy = attackerTransform.getPosition().getY() - targetTransform.getPosition().getY();
        double dz = attackerTransform.getPosition().getZ() - targetTransform.getPosition().getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
