package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Periodically reapplies EndlessLeveling skill modifiers for all players to
 * ensure
 * that outside sources (armor, potions, etc.) are always combined with mod
 * stats.
 */
public class PeriodicSkillModifierSystem extends TickingSystem<EntityStore> {
    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private static final float REAPPLY_INTERVAL_SECONDS = 2.0f; // every 2 seconds
    private float timeSinceLastReapply = 0f;

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;

    public PeriodicSkillModifierSystem(PlayerDataManager playerDataManager, SkillManager skillManager) {
        this.playerDataManager = playerDataManager;
        this.skillManager = skillManager;
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (playerDataManager == null || skillManager == null || store == null || store.isShutdown()) {
            return;
        }
        timeSinceLastReapply += deltaSeconds;
        if (timeSinceLastReapply < REAPPLY_INTERVAL_SECONDS) {
            return;
        }
        timeSinceLastReapply = 0f;
        store.forEachChunk(PLAYER_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef == null || !playerRef.isValid()) {
                            continue;
                        }
                        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
                        if (playerData == null) {
                            continue;
                        }

                        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
                        if (hp != null && hp.get() <= 1.0f) {
                            continue;
                        }

                        skillManager.applyAllSkillModifiers(ref, commandBuffer, playerData);
                    }
                });
    }
}
