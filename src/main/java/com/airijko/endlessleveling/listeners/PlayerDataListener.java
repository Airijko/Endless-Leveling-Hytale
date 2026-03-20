package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.util.ChatMessageTemplate;
import com.airijko.endlessleveling.util.FixedValue;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.airijko.endlessleveling.util.WorldContextUtil;
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
        if (playerData == null) {
            LOGGER.atWarning().log("Unable to load PlayerData for joining player %s", playerRef.getUsername());
            return;
        }

        var world = player.getWorld();
        if (world == null) {
            boolean inInstanceWorld = false;
            processPlayerReadyOnWorldThread(playerData, playerRef, entityRef, store, inInstanceWorld);
            return;
        }

        try {
            world.execute(() -> {
                boolean inInstanceWorld = WorldContextUtil.isInstanceContext(world, entityRef, store);
                processPlayerReadyOnWorldThread(playerData, playerRef, entityRef, store, inInstanceWorld);
            });
        } catch (Exception ex) {
            LOGGER.atWarning().withCause(ex).log(
                    "Failed to enqueue PlayerReady world task for %s; applying fallback path",
                    playerRef.getUsername());
            boolean inInstanceWorld = WorldContextUtil.isInstanceWorld(world);
            processPlayerReadyOnWorldThread(playerData, playerRef, null, null, inInstanceWorld);
        }
    }

    private void processPlayerReadyOnWorldThread(PlayerData playerData,
            PlayerRef playerRef,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            boolean inInstanceWorld) {
        UUID uuid = playerRef.getUuid();

        MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
        if (mobLevelingManager != null && store != null) {
            mobLevelingManager.syncTierLevelOverridesForDungeon(store, uuid);
        }

        if (passiveManager != null) {
            passiveManager.resetRuntimeState(uuid);
            passiveManager.syncPassives(playerData);
            playerDataManager.save(playerData);
        }

        if (skillManager != null) {
            SkillManager.VanguardCritRestrictionResult restrictionResult = skillManager
                    .enforceVanguardCritRestrictions(playerData);
            if (restrictionResult.adjusted()) {
                LOGGER.atInfo().log(
                        "Applied Vanguard crit restriction refund on login for %s (total=%d precision=%d ferocity=%d)",
                        playerRef.getUsername(),
                        restrictionResult.totalRefunded(),
                        restrictionResult.precisionRefunded(),
                        restrictionResult.ferocityRefunded());
                playerDataManager.save(playerData);
            }
        }

        if (skillManager != null && entityRef != null && store != null) {
            try {
                boolean applied = skillManager.applyAllSkillModifiers(entityRef, store, playerData);
                if (!applied) {
                    scheduleSkillRetry(uuid, playerRef.getUsername());
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to apply skill modifiers for %s: %s", playerRef.getUsername(),
                        e.getMessage());
            }
        } else if (skillManager != null) {
            scheduleSkillRetry(uuid, playerRef.getUsername());
        }

        if (raceManager != null && !inInstanceWorld) {
            raceManager.applyRaceModelOnLogin(playerData);
        }

        if (augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(playerData);

            // Audit after ensureUnlocks so that routine TOO_FEW cases (missing
            // offers that ensureUnlocks just filled) don't trigger false alerts.
            // Only real anomalies (TOO_MANY, or TOO_FEW when the pool was empty)
            // will reach online operators.
            var plugin = EndlessLeveling.getInstance();
            if (plugin != null && plugin.getAugmentSyncValidator() != null) {
                plugin.getAugmentSyncValidator().auditOnLogin(playerData);
            }

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

    private void scheduleSkillRetry(UUID uuid, String username) {
        LOGGER.atFine().log("Skill modifiers scheduled for retry for %s", username);
        var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
        if (retrySystem != null) {
            retrySystem.scheduleRetry(uuid);
        }
    }

    /** Called when a player leaves */
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        var playerRef = event.getPlayerRef();
        UUID uuid = playerRef.getUuid();

        PlayerHud.unregister(uuid);

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
                Message.raw(FixedValue.ROOT_COMMAND.value()).color("#4fd7f7"),
                Message.raw(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.secondary.close", " to invest them"))
                        .color("#ff9d00"));
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);

        var chatMessage = Message.join(
                Message.raw(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_HAVE))
                        .color(ChatMessageTemplate.SKILLS_CHAT_HAVE.colorHex()),
                Message.raw(String.valueOf(skillPoints)).color("#4fd7f7"),
                Message.raw(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_USE))
                        .color(ChatMessageTemplate.SKILLS_CHAT_USE.colorHex()),
                Message.raw(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_COMMAND))
                        .color(ChatMessageTemplate.SKILLS_COMMAND.colorHex()),
                Message.raw(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_END))
                        .color(ChatMessageTemplate.SKILLS_CHAT_END.colorHex()));
        PlayerChatNotifier.send(playerRef, chatMessage);
    }

    private void notifyAvailableAugments(PlayerRef playerRef, PlayerData playerData) {
        if (playerRef == null || playerData == null || augmentUnlockManager == null) {
            return;
        }
        if (!playerData.isAugmentNotifEnabled()) {
            return;
        }
        List<PassiveTier> tiers = augmentUnlockManager.getPendingOfferTiers(playerData);
        if (tiers.isEmpty()) {
            return;
        }

        PlayerChatNotifier.sendAugmentAvailability(playerRef, tiers);
    }
}
