package com.airijko.endlessleveling.augments;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages and executes augments for mobs/bosses loaded from world
 * configuration.
 * Stores mob augment instances keyed by entity UUID.
 */
public final class MobAugmentExecutor {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    // Map: Entity UUID -> List of active augments for that entity
    private final Map<UUID, MobAugmentInstance> mobAugments = new HashMap<>();
    private final Map<UUID, Long> mobPassiveAppliedAtMillis = new HashMap<>();

    private static final String CATEGORY_ON_HIT = "ON_HIT";
    private static final String CATEGORY_ON_CRIT = "ON_CRIT";
    private static final String CATEGORY_ON_MISS = "ON_MISS";
    private static final String CATEGORY_ON_TARGET_CONDITION = "ON_TARGET_CONDITION";
    private static final String CATEGORY_ON_DAMAGE_TAKEN = "ON_DAMAGE_TAKEN";
    private static final String CATEGORY_ON_LOW_HP = "ON_LOW_HP";
    private static final String CATEGORY_ON_KILL = "ON_KILL";
    private static final String CATEGORY_PASSIVE_STAT = "PASSIVE_STAT";

    public MobAugmentExecutor() {
    }

    /**
     * Register augments for a mob entity.
     * Called when a mob with configured augments is initialized.
     */
    public void registerMobAugments(
            UUID entityId,
            List<String> augmentIds,
            AugmentManager augmentManager,
            AugmentRuntimeManager runtimeManager) {
        if (entityId == null || augmentIds == null || augmentIds.isEmpty()) {
            return;
        }

        List<Augment> augments = new ArrayList<>();
        for (String augmentId : augmentIds) {
            if (augmentId == null || augmentId.isBlank()) {
                continue;
            }

            try {
                Augment augment = augmentManager.createAugment(augmentId.trim());
                if (augment == null) {
                    LOGGER.atWarning().log("Failed to create augment '%s' for mob %s", augmentId, entityId);
                    continue;
                }
                augments.add(augment);
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e)
                        .log("Error creating augment '%s' for mob %s: %s", augmentId, entityId, e.getMessage());
            }
        }

        if (!augments.isEmpty()) {
            // Create a runtime state for the mob using its UUID
            var runtimeState = runtimeManager.getRuntimeState(entityId);
            mobAugments.put(entityId, new MobAugmentInstance(augments, runtimeState));
            LOGGER.atInfo().log("[MOB_OVERRIDE_AUGMENTS] Bound %d augments to mob %s: %s",
                    augments.size(), entityId, augmentIds);
            LOGGER.atInfo().log("[MOB_AUGMENT_CATEGORIES] mob=%s categories=%s",
                    entityId, summarizeCategories(augments));
        }
    }

    /**
     * Apply on-hit effects for a mob attacker.
     */
    public AugmentDispatch.OnHitResult applyOnHit(
            UUID entityId,
            Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap attackerStats,
            EntityStatMap targetStats,
            float startingDamage) {

        MobAugmentInstance instance = mobAugments.get(entityId);
        if (instance == null || instance.augments.isEmpty()) {
            return new AugmentDispatch.OnHitResult(startingDamage, 0.0D);
        }

        applyPassiveHooks(entityId, instance, attackerRef, commandBuffer, attackerStats);

        AugmentHooks.HitContext context = new AugmentHooks.HitContext(
                null,
                instance.runtimeState,
                null,
                attackerRef,
                targetRef,
                commandBuffer,
                attackerStats,
                targetStats,
                startingDamage,
                false,
                false,
                null);

        if (AugmentDispatch.isMiss(context)) {
            for (Augment augment : instance.augments) {
                if (augment instanceof AugmentHooks.OnMissAugment onMiss) {
                    try {
                        float beforeDamage = context.getDamage();
                        double beforeTrueDamage = context.getTrueDamageBonus();
                        onMiss.onMiss(context);
                        logCategoryExecution(CATEGORY_ON_MISS,
                                entityId,
                                augment,
                                beforeDamage,
                                context.getDamage(),
                                beforeTrueDamage,
                                context.getTrueDamageBonus());
                    } catch (Exception e) {
                        LOGGER.atSevere().withCause(e)
                                .log("[AUGMENT] Error executing OnMiss %s for mob %s: %s", augment.getId(),
                                        entityId, e.getMessage());
                    }
                }
            }
            return new AugmentDispatch.OnHitResult(Math.max(0.0f, context.getDamage()), Math.max(0.0D, context.getTrueDamageBonus()));
        }

        for (Augment augment : instance.augments) {
            if (augment instanceof AugmentHooks.OnHitAugment onHit) {
                try {
                    float beforeDamage = context.getDamage();
                    double beforeTrueDamage = context.getTrueDamageBonus();
                    float updated = onHit.onHit(context);
                    context.setDamage(updated);
                    logCategoryExecution(CATEGORY_ON_HIT,
                            entityId,
                            augment,
                            beforeDamage,
                            context.getDamage(),
                            beforeTrueDamage,
                            context.getTrueDamageBonus());
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e)
                            .log("[AUGMENT] Error executing OnHit %s for mob %s: %s", augment.getId(),
                                    entityId, e.getMessage());
                }
            }
            if (augment instanceof AugmentHooks.OnCritAugment onCrit && context.isCritical()) {
                try {
                    float beforeDamage = context.getDamage();
                    double beforeTrueDamage = context.getTrueDamageBonus();
                    onCrit.onCrit(context);
                    logCategoryExecution(CATEGORY_ON_CRIT,
                            entityId,
                            augment,
                            beforeDamage,
                            context.getDamage(),
                            beforeTrueDamage,
                            context.getTrueDamageBonus());
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e)
                            .log("[AUGMENT] Error executing OnCrit %s for mob %s: %s", augment.getId(),
                                    entityId, e.getMessage());
                }
            }
            if (augment instanceof AugmentHooks.OnTargetConditionAugment onTargetCondition) {
                try {
                    float beforeDamage = context.getDamage();
                    double beforeTrueDamage = context.getTrueDamageBonus();
                    float updated = onTargetCondition.onTargetCondition(context);
                    context.setDamage(updated);
                    logCategoryExecution(CATEGORY_ON_TARGET_CONDITION,
                            entityId,
                            augment,
                            beforeDamage,
                            context.getDamage(),
                            beforeTrueDamage,
                            context.getTrueDamageBonus());
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e)
                            .log("[AUGMENT] Error executing OnTargetCondition %s for mob %s: %s",
                                    augment.getId(), entityId, e.getMessage());
                }
            }
        }

        return new AugmentDispatch.OnHitResult(Math.max(0.0f, context.getDamage()), Math.max(0.0D, context.getTrueDamageBonus()));
    }

    /**
     * Apply onLowHp augment effects when a mob takes damage and reaches low HP.
     * Returns the modified damage (may be 0 if rebirth/etc. activate).
     */
    public float applyOnLowHp(
            UUID entityId,
            Ref<EntityStore> mobRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage) {

        MobAugmentInstance instance = mobAugments.get(entityId);
        if (instance == null || instance.augments.isEmpty()) {
            return incomingDamage;
        }

        applyPassiveHooks(entityId, instance, mobRef, commandBuffer, statMap);

        AugmentHooks.DamageTakenContext context = new AugmentHooks.DamageTakenContext(
                null, // No PlayerData for mobs
                instance.runtimeState,
                null, // No SkillManager context
                mobRef,
                attackerRef,
                commandBuffer,
                statMap,
                incomingDamage);

        float damage = incomingDamage;

        // Execute low-HP augments in priority order
        for (Augment augment : AugmentDispatch.resolveLowHpTriggerOrder(instance.augments)) {
            AugmentHooks.OnLowHpAugment lowHpHandler = (AugmentHooks.OnLowHpAugment) augment;
            try {
                float beforeDamage = context.getIncomingDamage();
                float result = lowHpHandler.onLowHp(context);
                context.setIncomingDamage(result);
                damage = result;
                logCategoryExecution(CATEGORY_ON_LOW_HP,
                        entityId,
                        augment,
                        beforeDamage,
                        result,
                        0.0D,
                        0.0D);

                if (result <= 0f) {
                    LOGGER.atInfo().log("[AUGMENT] Mob %s %s activated! Blocked damage.", entityId,
                            augment.getId());
                    return 0f;
                } else if (result != incomingDamage) {
                    LOGGER.atInfo().log("[AUGMENT] Mob %s %s reduced damage from %.3f to %.3f",
                            entityId, augment.getId(), incomingDamage, result);
                }
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("[AUGMENT] Error executing %s for mob %s: %s", augment.getId(),
                        entityId, e.getMessage());
            }
        }

        return damage;
    }

    /**
     * Apply kill augment effects for a mob killer.
     */
    public void handleKill(
            UUID entityId,
            Ref<EntityStore> killerRef,
            Ref<EntityStore> victimRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap victimStats) {
        MobAugmentInstance instance = mobAugments.get(entityId);
        if (instance == null || instance.augments.isEmpty()) {
            return;
        }

        AugmentHooks.KillContext context = new AugmentHooks.KillContext(
                null,
                instance.runtimeState,
                null,
                killerRef,
                victimRef,
                commandBuffer,
                victimStats);

        for (Augment augment : instance.augments) {
            if (augment instanceof AugmentHooks.OnKillAugment onKill) {
                try {
                    onKill.onKill(context);
                    logCategoryExecution(CATEGORY_ON_KILL, entityId, augment);
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e)
                            .log("[AUGMENT] Error executing OnKill %s for mob %s: %s", augment.getId(),
                                    entityId, e.getMessage());
                }
            }
        }
    }

    /**
     * Apply onDamageTaken augment effects for mobs.
     */
    public float applyOnDamageTaken(
            UUID entityId,
            Ref<EntityStore> mobRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage) {

        MobAugmentInstance instance = mobAugments.get(entityId);
        if (instance == null || instance.augments.isEmpty()) {
            return incomingDamage;
        }

        AugmentHooks.DamageTakenContext context = new AugmentHooks.DamageTakenContext(
                null, // No PlayerData for mobs
                instance.runtimeState,
                null, // No SkillManager context
                mobRef,
                attackerRef,
                commandBuffer,
                statMap,
                incomingDamage);

        float damage = incomingDamage;

        for (Augment augment : instance.augments) {
            if (augment instanceof AugmentHooks.OnDamageTakenAugment handler) {
                try {
                    float beforeDamage = context.getIncomingDamage();
                    float result = handler.onDamageTaken(context);
                    context.setIncomingDamage(result);
                    damage = result;
                    logCategoryExecution(CATEGORY_ON_DAMAGE_TAKEN,
                            entityId,
                            augment,
                            beforeDamage,
                            result,
                            0.0D,
                            0.0D);

                    if (result != incomingDamage) {
                        LOGGER.atInfo().log("[AUGMENT] Mob %s %s modified damage from %.3f to %.3f",
                                entityId, augment.getId(), incomingDamage, result);
                    }
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e).log("[AUGMENT] Error executing %s for mob %s: %s",
                            augment.getId(), entityId, e.getMessage());
                }
            }
        }

        return damage;
    }

    /**
     * Clean up augments for a mob when it's removed.
     */
    public void unregisterMob(UUID entityId) {
        mobAugments.remove(entityId);
        mobPassiveAppliedAtMillis.remove(entityId);
    }

    /**
     * Check if a mob has any registered augments.
     */
    public boolean hasMobAugments(UUID entityId) {
        return mobAugments.containsKey(entityId);
    }

    private void applyPassiveHooks(UUID entityId,
            MobAugmentInstance instance,
            Ref<EntityStore> mobRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap) {
        if (instance == null || instance.augments.isEmpty() || statMap == null) {
            return;
        }

        double deltaSeconds = resolvePassiveDeltaSeconds(entityId);
        for (Augment augment : instance.augments) {
            if (augment instanceof AugmentHooks.PassiveStatAugment passive) {
                try {
                    AugmentHooks.PassiveStatContext context = new AugmentHooks.PassiveStatContext(
                            null,
                            instance.runtimeState,
                            null,
                            "mob::" + augment.getId(),
                            mobRef,
                            commandBuffer,
                            statMap,
                            deltaSeconds);
                    passive.applyPassive(context);
                    logCategoryExecution(CATEGORY_PASSIVE_STAT, entityId, augment);
                } catch (Exception e) {
                    LOGGER.atSevere().withCause(e)
                            .log("[AUGMENT] Error executing Passive %s for mob %s: %s", augment.getId(),
                                    entityId, e.getMessage());
                }
            }
        }
    }

    private void logCategoryExecution(String category, UUID entityId, Augment augment) {
        LOGGER.atFine().log("[MOB_AUGMENT_CATEGORY] mob=%s category=%s augment=%s",
                entityId,
                category,
                augment != null ? augment.getId() : "unknown");
    }

    private void logCategoryExecution(
            String category,
            UUID entityId,
            Augment augment,
            float beforeDamage,
            float afterDamage,
            double beforeTrueDamage,
            double afterTrueDamage) {
        LOGGER.atFine().log(
                "[MOB_AUGMENT_CATEGORY] mob=%s category=%s augment=%s damage=%.3f->%.3f true=%.3f->%.3f",
                entityId,
                category,
                augment != null ? augment.getId() : "unknown",
                beforeDamage,
                afterDamage,
                beforeTrueDamage,
                afterTrueDamage);
    }

    private String summarizeCategories(List<Augment> augments) {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put(CATEGORY_ON_HIT, new ArrayList<>());
        categories.put(CATEGORY_ON_CRIT, new ArrayList<>());
        categories.put(CATEGORY_ON_MISS, new ArrayList<>());
        categories.put(CATEGORY_ON_TARGET_CONDITION, new ArrayList<>());
        categories.put(CATEGORY_ON_DAMAGE_TAKEN, new ArrayList<>());
        categories.put(CATEGORY_ON_LOW_HP, new ArrayList<>());
        categories.put(CATEGORY_ON_KILL, new ArrayList<>());
        categories.put(CATEGORY_PASSIVE_STAT, new ArrayList<>());

        for (Augment augment : augments) {
            String augmentId = augment != null ? augment.getId() : "unknown";
            if (augment instanceof AugmentHooks.OnHitAugment) {
                categories.get(CATEGORY_ON_HIT).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnCritAugment) {
                categories.get(CATEGORY_ON_CRIT).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnMissAugment) {
                categories.get(CATEGORY_ON_MISS).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnTargetConditionAugment) {
                categories.get(CATEGORY_ON_TARGET_CONDITION).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnDamageTakenAugment) {
                categories.get(CATEGORY_ON_DAMAGE_TAKEN).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnLowHpAugment) {
                categories.get(CATEGORY_ON_LOW_HP).add(augmentId);
            }
            if (augment instanceof AugmentHooks.OnKillAugment) {
                categories.get(CATEGORY_ON_KILL).add(augmentId);
            }
            if (augment instanceof AugmentHooks.PassiveStatAugment) {
                categories.get(CATEGORY_PASSIVE_STAT).add(augmentId);
            }
        }

        StringBuilder builder = new StringBuilder();
        boolean firstCategory = true;
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (!firstCategory) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            firstCategory = false;
        }
        return firstCategory ? "none" : builder.toString();
    }

    private double resolvePassiveDeltaSeconds(UUID entityId) {
        long now = System.currentTimeMillis();
        Long previous = mobPassiveAppliedAtMillis.put(entityId, now);
        if (previous == null || previous <= 0L) {
            return 0.1D;
        }
        double deltaSeconds = (now - previous) / 1000.0D;
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0D) {
            return 0.1D;
        }
        return Math.min(1.0D, deltaSeconds);
    }

    /**
     * Represents a mob's active augments and runtime state.
     */
    private static class MobAugmentInstance {
        final List<Augment> augments;
        final AugmentRuntimeManager.AugmentRuntimeState runtimeState;

        MobAugmentInstance(List<Augment> augments, AugmentRuntimeManager.AugmentRuntimeState runtimeState) {
            this.augments = augments;
            this.runtimeState = runtimeState;
        }
    }
}
