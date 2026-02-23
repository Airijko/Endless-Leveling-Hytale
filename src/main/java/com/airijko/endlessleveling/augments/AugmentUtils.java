package com.airijko.endlessleveling.augments;

import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;

import java.util.Map;

/**
 * Small helpers shared across augment implementations.
 */
public final class AugmentUtils {

    private AugmentUtils() {
    }

    public static float heal(EntityStatMap statMap, double amount) {
        if (statMap == null || amount <= 0) {
            return 0f;
        }
        EntityStatValue health = statMap.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return 0f;
        }
        float current = health.get();
        float max = health.getMax();
        if (max <= 0f || current <= 0f) {
            return 0f;
        }
        float applied = (float) Math.min(max - current, amount);
        if (applied > 0f) {
            statMap.setStatValue(DefaultEntityStatTypes.getHealth(), current + applied);
        }
        return applied;
    }

    public static float healPercentOfMax(EntityStatMap statMap, double percent) {
        if (statMap == null || percent <= 0) {
            return 0f;
        }
        EntityStatValue health = statMap.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return 0f;
        }
        float max = health.getMax();
        return heal(statMap, max * percent);
    }

    public static float getCurrentHealth(EntityStatMap statMap) {
        EntityStatValue health = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        return health != null ? health.get() : 0f;
    }

    public static float getMaxHealth(EntityStatMap statMap) {
        EntityStatValue health = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        return health != null ? health.getMax() : 0f;
    }

    public static float getCurrentMana(EntityStatMap statMap) {
        EntityStatValue mana = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getMana());
        return mana != null ? mana.get() : 0f;
    }

    public static float getMaxMana(EntityStatMap statMap) {
        EntityStatValue mana = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getMana());
        return mana != null ? mana.getMax() : 0f;
    }

    public static float getCurrentStamina(EntityStatMap statMap) {
        EntityStatValue stamina = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getStamina());
        return stamina != null ? stamina.get() : 0f;
    }

    public static float clampPercent(double value) {
        return (float) Math.max(0.0D, Math.min(1.0D, value));
    }

    public static long secondsToMillis(double seconds) {
        if (seconds <= 0) {
            return 0L;
        }
        return (long) Math.round(seconds * 1000.0D);
    }

    public static boolean isCooldownReady(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            String augmentId,
            long cooldownMillis) {
        if (runtimeState == null || augmentId == null || cooldownMillis <= 0) {
            return true;
        }
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(augmentId);
        long now = System.currentTimeMillis();
        return state.getLastProc() <= 0L || now - state.getLastProc() >= cooldownMillis;
    }

    /**
     * Returns true if the cooldown is ready and marks the proc; otherwise false.
     */
    public static boolean consumeCooldown(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            String augmentId,
            long cooldownMillis) {
        if (!isCooldownReady(runtimeState, augmentId, cooldownMillis)) {
            return false;
        }
        markProc(runtimeState, augmentId, cooldownMillis);
        return true;
    }

    public static void markProc(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            String augmentId,
            long cooldownMillis) {
        if (runtimeState == null || augmentId == null) {
            return;
        }
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(augmentId);
        long now = System.currentTimeMillis();
        state.setLastProc(now);
        if (cooldownMillis > 0L) {
            state.setExpiresAt(now + cooldownMillis);
        }
    }

    public static void applyLifeSteal(EntityStatMap attackerStats, float damageDealt, double lifeStealPercent) {
        if (attackerStats == null || damageDealt <= 0f || lifeStealPercent <= 0) {
            return;
        }
        double healAmount = damageDealt * (lifeStealPercent / 100.0D);
        heal(attackerStats, healAmount);
    }

    public static float applyMultiplier(float baseDamage, double bonusMultiplier) {
        if (baseDamage <= 0f || bonusMultiplier == 0.0D) {
            return baseDamage;
        }
        return (float) (baseDamage * (1.0D + bonusMultiplier));
    }

    public static double resolveClassValue(Map<String, Object> classValues, String classId) {
        if (classValues == null || classValues.isEmpty() || classId == null) {
            return 0.0D;
        }
        Object val = classValues.get(classId.trim().toLowerCase());
        if (val instanceof Map<?, ?> inner) {
            Object value = inner.get("value");
            if (value instanceof Number num) {
                return num.doubleValue();
            }
        }
        return 0.0D;
    }
}
