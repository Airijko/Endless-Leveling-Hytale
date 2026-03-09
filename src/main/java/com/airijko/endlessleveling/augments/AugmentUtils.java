package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.types.OverhealAugment;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

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
        double overflow = Math.max(0.0D, amount - applied);
        if (overflow > 0.0D) {
            OverhealAugment.recordOverhealOverflow(statMap, overflow);
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

    public static float resolveThresholdHp(float maxHealth, double minHealthHp, double thresholdPercent) {
        float clampedMax = Math.max(1.0f, maxHealth);
        if (minHealthHp > 0.0D) {
            return (float) Math.min(clampedMax, minHealthHp);
        }
        return (float) (clampedMax * clampPercent(thresholdPercent));
    }

    public static float resolveSurvivalFloor(float maxHealth, float configuredThresholdHp) {
        float threshold = Math.max(0.0f, configuredThresholdHp);
        float clampedMax = Math.max(1.0f, maxHealth);
        return Math.max(1.0f, Math.min(clampedMax, threshold));
    }

    public static float applyUnkillableThreshold(EntityStatMap statMap,
            float incomingDamage,
            float thresholdHp,
            float survivalFloor) {
        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return incomingDamage;
        }
        float safeIncoming = Math.max(0.0f, incomingDamage);
        float projected = hp.get() - safeIncoming;
        if (projected < thresholdHp) {
            statMap.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(survivalFloor, hp.get()));
            return 0f;
        }
        return safeIncoming;
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
        return consumeCooldown(runtimeState, augmentId, augmentId, cooldownMillis);
    }

    public static boolean consumeCooldown(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            String augmentId,
            String displayName,
            long cooldownMillis) {
        if (!isCooldownReady(runtimeState, augmentId, cooldownMillis)) {
            return false;
        }
        if (runtimeState != null && cooldownMillis > 0L) {
            runtimeState.setCooldown(augmentId, displayName, System.currentTimeMillis() + cooldownMillis);
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

    public static PlayerRef getPlayerRef(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> ref) {
        if (commandBuffer == null || ref == null) {
            return null;
        }
        return commandBuffer.getComponent(ref, PlayerRef.getComponentType());
    }

    public static void sendAugmentMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        playerRef.sendMessage(Message.raw(text).color("#f7c74f"));
    }

    public static int setStacksWithNotify(AugmentRuntimeState runtimeState,
            String augmentId,
            int desiredStacks,
            int maxStacks,
            PlayerRef playerRef,
            String displayName) {
        if (runtimeState == null || augmentId == null) {
            return 0;
        }
        int clampedMax = Math.max(1, maxStacks);
        int current = runtimeState.getState(augmentId).getStacks();
        int newStacks = Math.max(0, Math.min(clampedMax, desiredStacks));
        if (newStacks == current) {
            return newStacks;
        }
        runtimeState.getState(augmentId).setStacks(newStacks);
        if (playerRef != null && playerRef.isValid()) {
            String name = displayName != null && !displayName.isBlank() ? displayName : augmentId;
            if (newStacks >= clampedMax) {
                sendAugmentMessage(playerRef, Lang.tr(playerRef.getUuid(),
                        "augments.stacks.max", "{0} at max stacks ({1}).",
                        name, newStacks));
            } else if (newStacks > current) {
                sendAugmentMessage(playerRef, Lang.tr(playerRef.getUuid(), "augments.stacks.update",
                        "{0}: {1}/{2} stacks.", name, newStacks, clampedMax));
            } else {
                if (newStacks <= 0) {
                    sendAugmentMessage(playerRef, Lang.tr(playerRef.getUuid(),
                            "augments.stacks.expired", "{0} stacks expired.", name));
                } else {
                    sendAugmentMessage(playerRef, Lang.tr(playerRef.getUuid(),
                            "augments.stacks.remaining", "{0}: {1}/{2} stacks remaining.",
                            name, newStacks, clampedMax));
                }
            }
        }
        return newStacks;
    }

    public static void setAttributeBonus(AugmentRuntimeState runtimeState,
            String sourceId,
            SkillAttributeType attributeType,
            double bonusValue,
            long durationMillis) {
        if (runtimeState == null || attributeType == null || sourceId == null) {
            return;
        }
        long expiresAt = durationMillis > 0L ? System.currentTimeMillis() + durationMillis : 0L;
        runtimeState.setAttributeBonus(attributeType, sourceId, bonusValue, expiresAt);
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
