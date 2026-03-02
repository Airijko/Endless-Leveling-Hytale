package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatListener extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final MobLevelingManager mobLevelingManager;
    private final CombatHookProcessor combatHookProcessor;

    public PlayerCombatListener(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull SkillManager skillManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            AugmentExecutor augmentExecutor,
            MobLevelingManager mobLevelingManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.mobLevelingManager = mobLevelingManager;
        this.combatHookProcessor = new CombatHookProcessor(skillManager,
                passiveManager,
                archetypePassiveManager,
                classManager,
                augmentExecutor);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
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
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayer == null || !attackerPlayer.isValid()) {
            return;
        }

        PlayerData playerData = playerDataManager.get(attackerPlayer.getUuid());
        if (playerData == null) {
            return;
        }

        PassiveRuntimeState runtimeState = passiveManager != null
                ? passiveManager.getRuntimeState(playerData.getUuid())
                : null;
        ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();

        Player player = commandBuffer.getComponent(attackerRef, Player.getComponentType());
        ItemStack weapon = player != null && player.getInventory() != null
                ? player.getInventory().getItemInHand()
                : null;

        EntityStatMap attackerStats = commandBuffer.getComponent(attackerRef, EntityStatMap.getComponentType());
        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());

        CombatHookProcessor.OutgoingResult result = combatHookProcessor.processOutgoing(
                new CombatHookProcessor.OutgoingContext(
                        playerData,
                        attackerPlayer,
                        attackerRef,
                        targetRef,
                        commandBuffer,
                        damage,
                        weapon,
                        runtimeState,
                        archetypeSnapshot,
                        attackerStats,
                        targetStats));

        float adjusted = result.finalDamage();
        float incomingBeforeDefense = adjusted;
        int mobLevel = -1;
        int playerLevel = Math.max(1, playerData.getLevel());
        double reduction = 0.0D;
        boolean targetIsPlayer = false;
        if (mobLevelingManager != null
                && mobLevelingManager.isMobLevelingEnabled()
                && mobLevelingManager.isMobDefenseScalingEnabled()) {
            PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
            targetIsPlayer = targetPlayer != null && targetPlayer.isValid();
            if (!targetIsPlayer) {
                mobLevel = mobLevelingManager.resolveMobLevel(targetRef, commandBuffer);
                reduction = mobLevelingManager.getMobDefenseReductionForLevels(mobLevel, playerLevel);
                if (reduction > 0.0D) {
                    adjusted = (float) (adjusted * (1.0D - reduction));
                }
            }
        }

        float targetHp = Float.NaN;
        float targetMax = Float.NaN;
        boolean targetDead = commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) != null;
        if (targetStats != null) {
            EntityStatValue hp = targetStats.get(DefaultEntityStatTypes.getHealth());
            if (hp != null) {
                targetHp = hp.get();
                targetMax = hp.getMax();
            }
        }

        float finalAdjusted = Math.max(0.0f, adjusted);
        float predictedPostHp = Float.NaN;
        boolean predictedLethal = false;
        if (Float.isFinite(targetHp)) {
            predictedPostHp = targetHp - finalAdjusted;
            predictedLethal = predictedPostHp <= 0.0001f;
        }

        LOGGER.atInfo().log(
                "PlayerHit target=%d attacker=%d dmg=%.3f->%.3f mobLevel=%d playerLevel=%d reduction=%.4f hp=%.3f max=%.3f predictedPostHp=%.3f predictedLethal=%s dead=%s targetIsPlayer=%s",
                targetRef.getIndex(),
                attackerRef.getIndex(),
                incomingBeforeDefense,
                finalAdjusted,
                mobLevel,
                playerLevel,
                reduction,
                targetHp,
                targetMax,
                predictedPostHp,
                predictedLethal,
                targetDead,
                targetIsPlayer);

        damage.setAmount(finalAdjusted);
    }
}
