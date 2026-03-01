package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.util.Lang;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.UUID;
import java.util.List;

public class PlayerDataListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final SkillManager skillManager;
    private final RaceManager raceManager;
    private final AugmentUnlockManager augmentUnlockManager;

    public PlayerDataListener(PlayerDataManager playerDataManager, PassiveManager passiveManager,
            SkillManager skillManager, RaceManager raceManager, AugmentUnlockManager augmentUnlockManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.skillManager = skillManager;
        this.raceManager = raceManager;
        this.augmentUnlockManager = augmentUnlockManager;
    }

    /** Called when a player joins */
    public void onPlayerReady(PlayerReadyEvent event) {
        var player = event.getPlayer();
        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef != null ? entityRef.getStore() : null;
        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null) {
            LOGGER.atWarning().log("Unable to find PlayerRef for joining player %s", player.getUuid());
            return;
        }
        UUID uuid = playerRef.getUuid();

        // Load or create PlayerData
        PlayerData playerData = playerDataManager.loadOrCreate(uuid, playerRef.getUsername());

        if (passiveManager != null) {
            passiveManager.resetRuntimeState(uuid);
            passiveManager.syncPassives(playerData);
            playerDataManager.save(playerData);
        }

        if (skillManager != null && entityRef != null && store != null) {
            try {
                boolean applied = skillManager.applyAllSkillModifiers(entityRef, store, playerData);
                if (!applied) {
                    LOGGER.atFine().log("Skill modifiers scheduled for retry for %s", playerRef.getUsername());
                    var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
                    if (retrySystem != null) {
                        retrySystem.scheduleRetry(uuid);
                    }
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to apply skill modifiers for %s: %s", playerRef.getUsername(),
                        e.getMessage());
            }
        }

        if (raceManager != null) {
            raceManager.applyRaceModelOnLogin(playerData);
        }

        if (augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(playerData);
            notifyAvailableAugments(playerRef, playerData);
        }

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(playerData);
        }

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
            playerDataManager.save(data); // persist to uuid.yml
            playerDataManager.remove(uuid); // remove from cache
            LOGGER.atInfo().log("Saved and removed PlayerData for %s on disconnect.", uuid);
        }

        if (raceManager != null) {
            raceManager.clearModelApplyGuard(uuid);
        }

        if (passiveManager != null) {
            passiveManager.resetRuntimeState(uuid);
        }
    }

    private void notifyAvailableSkillPoints(PlayerRef playerRef, int skillPoints) {
        if (playerRef == null || skillPoints <= 0) {
            return;
        }

        var packetHandler = playerRef.getPacketHandler();
        var primaryMessage = Message.raw(
                Lang.tr(playerRef.getUuid(), "notify.skills.unspent.primary",
                        "You have {0} unspent skill points!", skillPoints))
                .color("#ffc300");
        var secondaryMessage = Message.join(
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.secondary.open", "Open "))
                        .color("#ff9d00"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.command", "/skills")).color("#4fd7f7"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.secondary.close", " to invest them"))
                        .color("#ff9d00"));
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);

        var chatMessage = Message.join(
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.prefix", "[EndlessLeveling] ")).color("#4fd7f7"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.chat.have", "You still have "))
                        .color("#ffc300"),
                Message.raw(String.valueOf(skillPoints)).color("#4fd7f7"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.chat.use", " skill points. Use "))
                        .color("#ffc300"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.command", "/skills")).color("#4fd7f7"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.chat.end", " to spend them."))
                        .color("#ffc300"));
        playerRef.sendMessage(chatMessage);
    }

    private void notifyAvailableAugments(PlayerRef playerRef, PlayerData playerData) {
        if (playerRef == null || playerData == null || augmentUnlockManager == null) {
            return;
        }
        List<PassiveTier> tiers = augmentUnlockManager.getPendingOfferTiers(playerData);
        if (tiers.isEmpty()) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(Lang.tr(playerRef.getUuid(), "notify.augments.available.header",
                "[EndlessLeveling] You have augments available to choose from:"))
                .append("\n");
        for (PassiveTier tier : tiers) {
            builder.append("- ").append(tier.name()).append("\n");
        }
        builder.append(Lang.tr(playerRef.getUuid(), "notify.augments.available.footer", "Use /el augments to choose."));
        playerRef.sendMessage(Message.raw(builder.toString()).color("#4fd7f7"));
    }
}
