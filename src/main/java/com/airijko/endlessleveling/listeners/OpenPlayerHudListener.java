package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.CompletableFuture;

public class OpenPlayerHudListener {

    public static void openGui(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        Ref<EntityStore> ref = event.getPlayerRef();
        World world = player.getWorld();
        if (world == null || ref == null) {
            PlayerHud.unregister(player.getUuid());
            return;
        }

        CompletableFuture.runAsync(() -> {
            Store<EntityStore> asyncStore = ref.getStore();
            if (asyncStore == null) {
                return;
            }

            PlayerRef playerRef = asyncStore.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }

            PlayerHud.open(player, playerRef);
        }, world);
    }
}
