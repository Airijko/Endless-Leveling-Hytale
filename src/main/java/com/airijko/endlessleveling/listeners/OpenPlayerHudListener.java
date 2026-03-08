package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.util.WorldContextUtil;
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
        Store<EntityStore> store = ref != null ? ref.getStore() : null;

        assert world != null;

        if (WorldContextUtil.isInstanceContext(world, ref, store)) {
            PlayerHud.unregister(player.getUuid());
            return;
        }

        CompletableFuture.runAsync(() -> {
            Store<EntityStore> asyncStore = ref.getStore();
            if (WorldContextUtil.isInstanceContext(player.getWorld(), ref, asyncStore)) {
                PlayerHud.unregister(player.getUuid());
                return;
            }

            PlayerRef playerRef = asyncStore.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }

            PlayerHud.open(player, playerRef);
        }, world);
    }
}
