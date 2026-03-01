package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatListener extends DamageEventSystem {
    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final ClassManager classManager;
    private final AugmentExecutor augmentExecutor;
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
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.classManager = classManager;
        this.augmentExecutor = augmentExecutor;
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
        // Run before vanilla damage is applied
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        // Only notify if the source is a player
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
            PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayer != null && attackerPlayer.isValid()) {
                PlayerData playerData = playerDataManager.get(attackerPlayer.getUuid());
                if (playerData != null) {
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

                    EntityStatMap attackerStats = commandBuffer.getComponent(attackerRef,
                            EntityStatMap.getComponentType());
                    EntityStatMap targetStats = commandBuffer.getComponent(targetRef,
                            EntityStatMap.getComponentType());

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
                    if (mobLevelingManager != null
                            && mobLevelingManager.isMobLevelingEnabled()
                            && mobLevelingManager.isMobDefenseScalingEnabled()) {
                        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
                        if (targetPlayer == null || !targetPlayer.isValid()) {
                            int mobLevel = mobLevelingManager.resolveMobLevel(targetRef, commandBuffer);
                            int playerLevel = Math.max(1, playerData.getLevel());
                            double reduction = mobLevelingManager.getMobDefenseReductionForLevels(mobLevel,
                                    playerLevel);
                            if (reduction > 0.0D) {
                                adjusted = (float) (adjusted * (1.0D - reduction));
                            }
                        }
                    }

                    damage.setAmount(Math.max(0.0f, adjusted));
                }
            }
        }
    }

}