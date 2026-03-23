package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Periodically refreshes the Endless Leveling HUD so dynamic values (e.g.,
 * local mob level)
 * stay in sync with player position without relying on manual triggers.
 */
public class HudRefreshSystem extends TickingSystem<EntityStore> {

    private static final float REFRESH_INTERVAL_SECONDS = 0.1f;
    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private float timeSinceLastRefresh = 0f;

    public HudRefreshSystem() {
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return;
        }

        if (!PlayerHud.hasActiveHuds()) {
            return;
        }

        timeSinceLastRefresh += deltaSeconds;
        if (timeSinceLastRefresh < REFRESH_INTERVAL_SECONDS) {
            return;
        }
        timeSinceLastRefresh = 0f;

        // Refresh only players that belong to the current store/thread to avoid
        // cross-store thread assertions when resolving components.
        store.forEachChunk(PLAYER_QUERY, (ArchetypeChunk<EntityStore> chunk,
                CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                UUID uuid = playerRef.getUuid();
                if (uuid == null) {
                    continue;
                }

                if (!PlayerHud.isActive(uuid)) {
                    continue;
                }

                // Only refresh HUD for players whose entity store matches the current thread's
                // store to avoid cross-store component access.
                Ref<EntityStore> playerEntityRef = playerRef.getReference();
                if (playerEntityRef == null || playerEntityRef.getStore() != store) {
                    continue;
                }

                PlayerHud.refreshHud(uuid);
            }
        });
    }
}
