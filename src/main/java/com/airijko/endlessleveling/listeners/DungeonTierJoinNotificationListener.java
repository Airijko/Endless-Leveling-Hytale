package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Listener focused on tiered instance join notification.
 */
public class DungeonTierJoinNotificationListener {

    private static final long TIER_NOTIFICATION_DELAY_MS = 5000L;

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

        String universeWorld = null;
        World playerWorld = null;
        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid != null) {
            playerWorld = universe.getWorld(worldUuid);
            if (playerWorld != null && playerWorld.getName() != null && !playerWorld.getName().isBlank()) {
                universeWorld = playerWorld.getName();
            }
        }

        if (playerWorld == null) {
            return;
        }

        String worldId = mobLevelingManager.resolveWorldIdentifier(store);
        String effectiveWorldId = worldId != null && !worldId.isBlank() ? worldId : universeWorld;
        if (!isInstanceWorld(effectiveWorldId)) {
            return;
        }

        MobLevelingManager.TieredWorldSummary summary = mobLevelingManager.resolveTieredWorldSummary(store, playerRef);
        if (summary == null) {
            return;
        }

        final PlayerRef finalPlayerRef = playerRef;
        final Store<EntityStore> finalStore = store;
        final MobLevelingManager finalMobLevelingManager = mobLevelingManager;
        World delayedWorld = playerWorld;
        CompletableFuture.runAsync(() -> {
            if (!finalPlayerRef.isValid()) {
                return;
            }

            MobLevelingManager.TieredWorldSummary delayedSummary = finalMobLevelingManager.resolveTieredWorldSummary(finalStore, finalPlayerRef);
            if (delayedSummary == null) {
                return;
            }

            int tierNumber = delayedSummary.tierOffset() + 1;
            Message chat = Message.join(
                    Message.raw("Tiered instance").color("#ffcf66"),
                    Message.raw("\nTier ").color("#ffcf66"),
                    Message.raw(String.valueOf(tierNumber)).color("#4fd7f7"),
                    Message.raw("\nMob Lv ").color("#ffcf66"),
                    Message.raw(delayedSummary.tierMinLevel() + "-" + delayedSummary.tierMaxLevel()).color("#4fd7f7"),
                    Message.raw(" | Boss Lv ").color("#ffcf66"),
                    Message.raw(String.valueOf(delayedSummary.bossLevel())).color("#ff6b6b"));
            PlayerChatNotifier.send(finalPlayerRef, chat);
        }, CompletableFuture.delayedExecutor(TIER_NOTIFICATION_DELAY_MS, TimeUnit.MILLISECONDS, delayedWorld));
    }

    private boolean isInstanceWorld(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return false;
        }
        String normalized = worldId.trim().toLowerCase();
        return normalized.startsWith("instance-") || normalized.contains("_instance_");
    }
}
