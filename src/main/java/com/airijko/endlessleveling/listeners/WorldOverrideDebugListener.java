package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Dedicated diagnostics listener for world override resolution.
 * Kept separate from player data loading so debug behavior can be iterated independently.
 */
public class WorldOverrideDebugListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public void onPlayerReady(PlayerReadyEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }

        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        Universe universe = Universe.get();
        if (store == null || universe == null) {
            return;
        }

        PlayerRef playerRef = null;
        for (PlayerRef candidate : universe.getPlayers()) {
            Ref<EntityStore> candidateRef = candidate.getReference();
            if (candidateRef != null && candidateRef.equals(entityRef)) {
                playerRef = candidate;
                break;
            }
        }
        if (playerRef == null) {
            return;
        }

        MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
        if (mobLevelingManager == null) {
            return;
        }

        UUID uuid = playerRef.getUuid();
        String username = playerRef.getUsername();
        String universeWorld = "unknown";
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid != null) {
            World world = universe.getWorld(worldUuid);
            if (world != null && world.getName() != null && !world.getName().isBlank()) {
                universeWorld = world.getName();
            }
        }

        String worldId = mobLevelingManager.resolveWorldIdentifier(store);
        String matchedKey = mobLevelingManager.resolveMatchedWorldOverrideKey(store);
        boolean fallback = mobLevelingManager.isDefaultWorldFallback(store);

        String message = String.format(
            "World join debug: player %s (%s) universeWorld='%s' resolvedWorld='%s' matchedKey=%s fallbackToDefault=%s",
            username,
            uuid,
            universeWorld,
            worldId == null || worldId.isBlank() ? "unknown" : worldId,
            matchedKey == null || matchedKey.isBlank() ? "<none>" : matchedKey,
            fallback);

        // Temporary debug bypass: emit to stdout so this is visible regardless of
        // logger base-level filtering.
        System.out.println("[EL_WORLD_DEBUG] " + message);
        LOGGER.atInfo().log("[EL_WORLD_DEBUG] %s", message);
    }
}
