package com.airijko.endlessleveling.mob;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.leveling.XpKillCreditTracker;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive.SummonInheritedStats;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Objects;

/**
 * Scales damage dealt by non-player entities (mobs) using LevelingManager
 * multipliers.
 */
public class MobDamageScalingSystem extends DamageEventSystem {

    private final MobLevelingManager levelingManager;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long SUMMON_DEBUG_LOG_COOLDOWN_MS = 2000L;
    private static final Map<Integer, Long> OUTGOING_DEBUG_LAST_LOG = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> INCOMING_DEBUG_LAST_LOG = new ConcurrentHashMap<>();

    public MobDamageScalingSystem(MobLevelingManager levelingManager) {
        this.levelingManager = Objects.requireNonNull(levelingManager);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Nonnull
    @Override
    public Set<com.hypixel.hytale.component.dependency.Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
            if (!EntityRefUtil.isUsable(attackerRef) || !EntityRefUtil.isUsable(targetRef)) {
                return;
            }

            if (ArmyOfTheDeadPassive.shouldPreventFriendlyDamage(attackerRef, targetRef, store, commandBuffer)) {
                damage.setAmount(0.0f);
                return;
            }

            XpKillCreditTracker.recordDamage(targetRef, attackerRef, store, commandBuffer);

            ArmyOfTheDeadPassive.focusSummonsOnSummonAttacker(targetRef, attackerRef, store, commandBuffer);

            boolean managedSummonAttacker = ArmyOfTheDeadPassive.isManagedSummon(attackerRef, store, commandBuffer);
            if (managedSummonAttacker) {
                applySummonOutgoingScaling(damage, attackerRef, store, commandBuffer);
                applySummonAttackerAugments(damage, attackerRef, targetRef, store, commandBuffer);
            }

            PlayerRef attackerPlayer = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef,
                    PlayerRef.getComponentType());
            if ((attackerPlayer == null || !attackerPlayer.isValid())
                    && !managedSummonAttacker
                    && levelingManager.isMobDamageScalingEnabled(store)) {

                // Treat as mob source
                int mobLevel = levelingManager.resolveMobLevel(attackerRef, commandBuffer);
                PlayerRef defenderPlayer = EntityRefUtil.tryGetComponent(commandBuffer, targetRef,
                        PlayerRef.getComponentType());

                double mult;
                if (defenderPlayer != null && defenderPlayer.isValid()) {
                    int playerLevel = levelingManager.getPlayerLevel(defenderPlayer);
                    mult = levelingManager.getMobDamageMultiplierForLevels(
                            attackerRef,
                            commandBuffer,
                            mobLevel,
                            playerLevel);
                } else {
                    mult = levelingManager.getMobDamageMultiplierForLevel(attackerRef, commandBuffer, mobLevel);
                }

                float old = damage.getAmount();
                float updated = (float) (old * mult);
                damage.setAmount(updated);
                try {
                    LOGGER.atFiner().log("MobDamageScaling: scaled damage from %f to %f for entity %d", old, updated,
                            attackerRef.getIndex());
                } catch (Throwable ignored) {
                }
            }

            if (ArmyOfTheDeadPassive.isManagedSummon(targetRef, store, commandBuffer)) {
                applySummonIncomingDefenseScaling(damage, targetRef, store, commandBuffer);
                applySummonDefenderAugments(damage, targetRef, attackerRef, store, commandBuffer);
            }
        }
    }

    private void applySummonAttackerAugments(@Nonnull Damage damage,
            @Nonnull Ref<EntityStore> attackerRef,
            @Nonnull Ref<EntityStore> targetRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return;
        }

        MobAugmentExecutor executor = plugin.getMobAugmentExecutor();
        if (executor == null) {
            return;
        }

        UUID attackerUuid = resolveEntityUuid(attackerRef, store, commandBuffer);
        if (attackerUuid == null || !executor.hasMobAugments(attackerUuid)) {
            return;
        }

        EntityStatMap attackerStats = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef,
                EntityStatMap.getComponentType());
        EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer, targetRef,
                EntityStatMap.getComponentType());

        float before = Math.max(0.0f, damage.getAmount());
        var onHit = executor.applyOnHit(attackerUuid,
                attackerRef,
                targetRef,
                commandBuffer,
                attackerStats,
                targetStats,
                before);

        // Preserve legacy behavior where true damage is represented as a flat add-on.
        double trueDamage = Math.max(0.0D, onHit.trueDamageBonus());
        float after = Math.max(0.0f, onHit.damage() + (float) trueDamage);
        damage.setAmount(after);
    }

    private void applySummonDefenderAugments(@Nonnull Damage damage,
            @Nonnull Ref<EntityStore> targetRef,
            @Nonnull Ref<EntityStore> attackerRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return;
        }

        MobAugmentExecutor executor = plugin.getMobAugmentExecutor();
        if (executor == null) {
            return;
        }

        UUID targetUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (targetUuid == null || !executor.hasMobAugments(targetUuid)) {
            return;
        }

        EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer, targetRef,
                EntityStatMap.getComponentType());
        if (targetStats == null) {
            return;
        }

        float before = Math.max(0.0f, damage.getAmount());
        float afterDamageTaken = executor.applyOnDamageTaken(
                targetUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                before);
        float afterLowHp = executor.applyOnLowHp(
                targetUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                afterDamageTaken);

        damage.setAmount(Math.max(0.0f, afterLowHp));
    }

    private void applySummonOutgoingScaling(@Nonnull Damage damage,
            @Nonnull Ref<EntityStore> summonRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        SummonInheritedStats inherited = ArmyOfTheDeadPassive.resolveManagedSummonInheritedStats(
                summonRef,
                store,
                commandBuffer);

        double inheritanceValue = ArmyOfTheDeadPassive.getManagedSummonStatInheritance(
                summonRef,
                store,
                commandBuffer);
        double baseDamageValue = ArmyOfTheDeadPassive.getManagedSummonBaseDamage(
            summonRef,
            store,
            commandBuffer);
        java.util.UUID ownerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(summonRef, store, commandBuffer);

        float before = Math.max(0.0f, damage.getAmount());
        float boostedBase = before + Math.max(0.0f, (float) baseDamageValue);
        float amount = boostedBase * Math.max(0.0f, inherited.damageMultiplier());
        boolean critApplied = false;

        if (amount > 0.0f && inherited.critChance() > 0.0f
                && ThreadLocalRandom.current().nextDouble() < inherited.critChance()) {
            amount *= Math.max(1.0f, inherited.critDamageMultiplier());
            critApplied = true;
        }

        float after = Math.max(0.0f, amount);
        damage.setAmount(after);

        if (shouldLogSummonDebug(OUTGOING_DEBUG_LAST_LOG, summonRef.getIndex())) {
            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-HIT][OUT] summonRef=%d owner=%s inheritance=%.3f before=%.3f baseDamage=%.3f boostedBase=%.3f after=%.3f dmgMult=%.3f critChance=%.3f critDmgMult=%.3f critApplied=%s",
                    summonRef.getIndex(),
                    ownerUuid,
                    inheritanceValue,
                    before,
                    baseDamageValue,
                    boostedBase,
                    after,
                    inherited.damageMultiplier(),
                    inherited.critChance(),
                    inherited.critDamageMultiplier(),
                    critApplied);
        }
    }

    private void applySummonIncomingDefenseScaling(@Nonnull Damage damage,
            @Nonnull Ref<EntityStore> summonRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        SummonInheritedStats inherited = ArmyOfTheDeadPassive.resolveManagedSummonInheritedStats(
                summonRef,
                store,
                commandBuffer);

        double inheritanceValue = ArmyOfTheDeadPassive.getManagedSummonStatInheritance(
                summonRef,
                store,
                commandBuffer);
        java.util.UUID ownerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(summonRef, store, commandBuffer);

        float before = Math.max(0.0f, damage.getAmount());
        float amount = before;
        float reduction = Math.max(0.0f, Math.min(0.95f, inherited.defenseReduction()));
        float after = Math.max(0.0f, amount * (1.0f - reduction));
        damage.setAmount(after);

        if (shouldLogSummonDebug(INCOMING_DEBUG_LAST_LOG, summonRef.getIndex())) {
            LOGGER.atInfo().log(
                    "[ARMY_OF_THE_DEAD][DEBUG-HIT][IN] summonRef=%d owner=%s inheritance=%.3f before=%.3f after=%.3f defenseReduction=%.3f",
                    summonRef.getIndex(),
                    ownerUuid,
                    inheritanceValue,
                    before,
                    after,
                    reduction);
        }
    }

    private boolean shouldLogSummonDebug(Map<Integer, Long> logMap, int entityIndex) {
        long now = System.currentTimeMillis();
        Long last = logMap.get(entityIndex);
        if (last != null && now - last < SUMMON_DEBUG_LOG_COOLDOWN_MS) {
            return false;
        }
        logMap.put(entityIndex, now);
        return true;
    }

    private UUID resolveEntityUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return null;
        }

        UUIDComponent uuidComponent = commandBuffer != null
                ? commandBuffer.getComponent(ref, UUIDComponent.getComponentType())
                : null;
        if (uuidComponent == null && store != null) {
            uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        }
        if (uuidComponent == null) {
            return null;
        }

        UUID uuid = uuidComponent.getUuid();
        return uuid != null ? uuid : null;
    }
}
