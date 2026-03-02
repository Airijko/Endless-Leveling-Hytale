package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diagnostic probe that runs after ApplyDamage to inspect final HP/death state.
 */
public class PlayerCombatPostApplyProbeSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final Map<Integer, Integer> lethalWithoutDeathStreak = new ConcurrentHashMap<>();
    private final Map<Integer, Float> lastObservedHpByTarget = new ConcurrentHashMap<>();
    private final MobLevelingManager mobLevelingManager;

    public PlayerCombatPostApplyProbeSystem(MobLevelingManager mobLevelingManager) {
        this.mobLevelingManager = mobLevelingManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null) {
            return;
        }

        PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayer == null || !attackerPlayer.isValid()) {
            return;
        }

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        boolean targetIsPlayer = targetPlayer != null && targetPlayer.isValid();
        if (targetIsPlayer) {
            return;
        }

        float hp = Float.NaN;
        float max = Float.NaN;
        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (targetStats != null) {
            EntityStatValue targetHp = targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp != null) {
                hp = targetHp.get();
                max = targetHp.getMax();
            }
        }

        boolean dead = commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) != null;
        boolean shouldBeDead = Float.isFinite(hp) && hp <= 0.0001f;
        int targetId = targetRef.getIndex();
        int mobLevel = -1;
        if (mobLevelingManager != null && mobLevelingManager.isMobLevelingEnabled()) {
            mobLevel = mobLevelingManager.resolveMobLevel(targetRef, commandBuffer);
        }

        if (shouldBeDead && !dead) {
            int streak = lethalWithoutDeathStreak.getOrDefault(targetId, 0) + 1;
            lethalWithoutDeathStreak.put(targetId, streak);
            lastObservedHpByTarget.remove(targetId);

            LOGGER.atWarning().log(
                    "PlayerHitPostApply target=%d attacker=%d finalDamage=%.3f hp=%.3f max=%.3f shouldBeDead=%s dead=%s streak=%d mobLevel=%d",
                    targetId,
                    attackerRef.getIndex(),
                    damage.getAmount(),
                    hp,
                    max,
                    shouldBeDead,
                    dead,
                    streak,
                    mobLevel);

            if (streak >= 2) {
                LOGGER.atWarning().log(
                        "UnkillableCandidate target=%d streak=%d hp=%.3f max=%.3f dead=%s mobLevel=%d",
                        targetId,
                        streak,
                        hp,
                        max,
                        dead,
                        mobLevel);
            }
            return;
        }

        if (dead) {
            lethalWithoutDeathStreak.remove(targetId);
            lastObservedHpByTarget.remove(targetId);
        }

        lethalWithoutDeathStreak.remove(targetId);

        Float previousObservedHp = lastObservedHpByTarget.get(targetId);
        if (Float.isFinite(hp)) {
            lastObservedHpByTarget.put(targetId, hp);
        }

        if (!dead && previousObservedHp != null && Float.isFinite(previousObservedHp) && Float.isFinite(hp)) {
            if (hp > previousObservedHp + 1.0f) {
                LOGGER.atWarning().log(
                        "HpJumpWithoutDeath target=%d previousHp=%.3f currentHp=%.3f max=%.3f dead=%s",
                        targetId,
                        previousObservedHp,
                        hp,
                        max,
                        dead);
            }
            if (previousObservedHp <= 0.0001f && max > 0.0f && hp >= max - 0.001f) {
                LOGGER.atWarning().log(
                        "HpResetAfterZeroWithoutDeath target=%d previousHp=%.3f currentHp=%.3f max=%.3f dead=%s",
                        targetId,
                        previousObservedHp,
                        hp,
                        max,
                        dead);
            }
        }

        LOGGER.atInfo().log(
                "PlayerHitPostApply target=%d attacker=%d finalDamage=%.3f hp=%.3f max=%.3f shouldBeDead=%s dead=%s mobLevel=%d",
                targetId,
                attackerRef.getIndex(),
                damage.getAmount(),
                hp,
                max,
                shouldBeDead,
                dead,
                mobLevel);
    }
}
