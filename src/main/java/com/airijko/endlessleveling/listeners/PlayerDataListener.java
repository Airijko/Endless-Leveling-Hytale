package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.UUID;

public class PlayerDataListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final PlayerDataManager playerDataManager;

    public PlayerDataListener(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    /** Called when a player joins */
    public void onPlayerReady(PlayerReadyEvent event) {
        var player = event.getPlayer();
        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null) {
            LOGGER.atWarning().log("Unable to find PlayerRef for joining player %s", player.getUuid());
            return;
        }
        UUID uuid = playerRef.getUuid();

        // Load or create PlayerData
        PlayerData playerData = playerDataManager.loadOrCreate(uuid, playerRef.getUsername());

        LOGGER.atInfo().log("Loaded PlayerData for player: %s", playerRef.getUsername());

        if (playerData.getSkillPoints() > 0) {
            notifyAvailableSkillPoints(playerRef, playerData.getSkillPoints());
        }
    }

    /** Called when a player leaves */
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        var playerRef = event.getPlayerRef();
        UUID uuid = playerRef.getUuid();

        PlayerData data = playerDataManager.get(uuid);
        if (data != null) {
            playerDataManager.save(data);   // persist to uuid.yml
            playerDataManager.remove(uuid); // remove from cache
            LOGGER.atInfo().log("Saved and removed PlayerData for %s on disconnect.", uuid);
        }
    }

    private void notifyAvailableSkillPoints(PlayerRef playerRef, int skillPoints) {
        if (playerRef == null || skillPoints <= 0) {
            return;
        }

        var packetHandler = playerRef.getPacketHandler();
        var primaryMessage = Message.raw("You have " + skillPoints + " unspent skill points!").color("#ffc300");
        var secondaryMessage = Message.join(
                Message.raw("Open ").color("#ff9d00"),
                Message.raw("/skills").color("#4fd7f7"),
                Message.raw(" to invest them").color("#ff9d00")
        );
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);

        var chatMessage = Message.join(
            Message.raw("[EndlessLeveling] ").color("#4fd7f7"),
            Message.raw("You still have ").color("#ffc300"),
            Message.raw(String.valueOf(skillPoints)).color("#4fd7f7"),
            Message.raw(" skill points. Use ").color("#ffc300"),
            Message.raw("/skills").color("#4fd7f7"),
            Message.raw(" to spend them.").color("#ffc300")
        );
        playerRef.sendMessage(chatMessage);
    }
}
