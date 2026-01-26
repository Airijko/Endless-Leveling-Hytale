package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endlessleveling;
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
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        World world = player.getWorld();

        assert world != null;
        assert playerRef != null;

        PlayerData data = Endlessleveling.getInstance()
                .getPlayerDataManager()
                .get(playerRef.getUuid());

        if (data == null || !data.isPlayerHudEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> PlayerHud.open(player, playerRef), world);
    }
}
