package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Hook interfaces and context objects consumed by augment logic classes.
 */
public interface AugmentHooks {

    interface PassiveStatAugment {
        void applyPassive(@Nonnull PassiveStatContext context);
    }

    interface OnHitAugment {
        float onHit(@Nonnull HitContext context);
    }

    interface OnCritAugment {
        void onCrit(@Nonnull HitContext context);
    }

    interface OnMissAugment {
        void onMiss(@Nonnull HitContext context);
    }

    interface OnDamageTakenAugment {
        float onDamageTaken(@Nonnull DamageTakenContext context);
    }

    interface OnKillAugment {
        void onKill(@Nonnull KillContext context);
    }

    interface OnLowHpAugment {
        float onLowHp(@Nonnull DamageTakenContext context);
    }

    interface OnTargetConditionAugment {
        float onTargetCondition(@Nonnull HitContext context);
    }

    /** Common base data for augment invocations. */
    abstract class BaseContext {
        private final PlayerData playerData;
        private final AugmentRuntimeManager.AugmentRuntimeState runtimeState;
        private final SkillManager skillManager;

        protected BaseContext(PlayerData playerData,
                AugmentRuntimeManager.AugmentRuntimeState runtimeState,
                SkillManager skillManager) {
            this.playerData = playerData;
            this.runtimeState = runtimeState;
            this.skillManager = skillManager;
        }

        public PlayerData getPlayerData() {
            return playerData;
        }

        public AugmentRuntimeManager.AugmentRuntimeState getRuntimeState() {
            return runtimeState;
        }

        public SkillManager getSkillManager() {
            return skillManager;
        }
    }

    public static final class HitContext extends BaseContext {
        private final Ref<EntityStore> attackerRef;
        private final Ref<EntityStore> targetRef;
        private final CommandBuffer<EntityStore> commandBuffer;
        private final EntityStatMap attackerStats;
        private final EntityStatMap targetStats;
        private final boolean critical;
        private final boolean rangedAttack;
        private final ClassWeaponType weaponType;
        private float damage;

        public HitContext(PlayerData playerData,
                AugmentRuntimeManager.AugmentRuntimeState runtimeState,
                SkillManager skillManager,
                Ref<EntityStore> attackerRef,
                Ref<EntityStore> targetRef,
                CommandBuffer<EntityStore> commandBuffer,
                EntityStatMap attackerStats,
                EntityStatMap targetStats,
                float damage,
                boolean critical,
                boolean rangedAttack,
                ClassWeaponType weaponType) {
            super(playerData, runtimeState, skillManager);
            this.attackerRef = attackerRef;
            this.targetRef = targetRef;
            this.commandBuffer = commandBuffer;
            this.attackerStats = attackerStats;
            this.targetStats = targetStats;
            this.damage = damage;
            this.critical = critical;
            this.rangedAttack = rangedAttack;
            this.weaponType = weaponType;
        }

        public Ref<EntityStore> getAttackerRef() {
            return attackerRef;
        }

        public Ref<EntityStore> getTargetRef() {
            return targetRef;
        }

        public CommandBuffer<EntityStore> getCommandBuffer() {
            return commandBuffer;
        }

        @Nullable
        public EntityStatMap getAttackerStats() {
            return attackerStats;
        }

        public EntityStatMap getTargetStats() {
            return targetStats;
        }

        public boolean isCritical() {
            return critical;
        }

        public boolean isRangedAttack() {
            return rangedAttack;
        }

        public ClassWeaponType getWeaponType() {
            return weaponType;
        }

        public float getDamage() {
            return damage;
        }

        public void setDamage(float damage) {
            this.damage = damage;
        }
    }

    public static final class DamageTakenContext extends BaseContext {
        private final Ref<EntityStore> defenderRef;
        private final Ref<EntityStore> attackerRef;
        private final CommandBuffer<EntityStore> commandBuffer;
        private final EntityStatMap statMap;
        private float incomingDamage;

        public DamageTakenContext(PlayerData playerData,
                AugmentRuntimeManager.AugmentRuntimeState runtimeState,
                SkillManager skillManager,
                Ref<EntityStore> defenderRef,
                Ref<EntityStore> attackerRef,
                CommandBuffer<EntityStore> commandBuffer,
                EntityStatMap statMap,
                float incomingDamage) {
            super(playerData, runtimeState, skillManager);
            this.defenderRef = defenderRef;
            this.attackerRef = attackerRef;
            this.commandBuffer = commandBuffer;
            this.statMap = statMap;
            this.incomingDamage = incomingDamage;
        }

        public Ref<EntityStore> getDefenderRef() {
            return defenderRef;
        }

        public Ref<EntityStore> getAttackerRef() {
            return attackerRef;
        }

        public CommandBuffer<EntityStore> getCommandBuffer() {
            return commandBuffer;
        }

        public EntityStatMap getStatMap() {
            return statMap;
        }

        public float getIncomingDamage() {
            return incomingDamage;
        }

        public void setIncomingDamage(float incomingDamage) {
            this.incomingDamage = incomingDamage;
        }
    }

    public static final class KillContext extends BaseContext {
        private final Ref<EntityStore> killerRef;
        private final Ref<EntityStore> victimRef;
        private final CommandBuffer<EntityStore> commandBuffer;
        private final EntityStatMap victimStats;

        public KillContext(PlayerData playerData,
                AugmentRuntimeManager.AugmentRuntimeState runtimeState,
                SkillManager skillManager,
                Ref<EntityStore> killerRef,
                Ref<EntityStore> victimRef,
                CommandBuffer<EntityStore> commandBuffer,
                EntityStatMap victimStats) {
            super(playerData, runtimeState, skillManager);
            this.killerRef = killerRef;
            this.victimRef = victimRef;
            this.commandBuffer = commandBuffer;
            this.victimStats = victimStats;
        }

        public Ref<EntityStore> getKillerRef() {
            return killerRef;
        }

        public Ref<EntityStore> getVictimRef() {
            return victimRef;
        }

        public CommandBuffer<EntityStore> getCommandBuffer() {
            return commandBuffer;
        }

        public EntityStatMap getVictimStats() {
            return victimStats;
        }
    }

    public static final class PassiveStatContext extends BaseContext {
        private final Ref<EntityStore> playerRef;
        private final CommandBuffer<EntityStore> commandBuffer;
        private final EntityStatMap statMap;
        private final double deltaSeconds;

        public PassiveStatContext(PlayerData playerData,
                AugmentRuntimeManager.AugmentRuntimeState runtimeState,
                SkillManager skillManager,
                Ref<EntityStore> playerRef,
                CommandBuffer<EntityStore> commandBuffer,
                EntityStatMap statMap,
                double deltaSeconds) {
            super(playerData, runtimeState, skillManager);
            this.playerRef = playerRef;
            this.commandBuffer = commandBuffer;
            this.statMap = statMap;
            this.deltaSeconds = deltaSeconds;
        }

        public Ref<EntityStore> getPlayerRef() {
            return playerRef;
        }

        public CommandBuffer<EntityStore> getCommandBuffer() {
            return commandBuffer;
        }

        public EntityStatMap getStatMap() {
            return statMap;
        }

        public double getDeltaSeconds() {
            return deltaSeconds;
        }
    }
}
