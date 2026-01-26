package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.PartyManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;

import javax.annotation.Nonnull;

public class PartyListener {

    private final PartyManager partyManager;

    public PartyListener(@Nonnull PartyManager partyManager) {
        this.partyManager = partyManager;
    }

    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        var playerRef = event.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        partyManager.removePlayer(playerRef.getUuid());
    }
}
