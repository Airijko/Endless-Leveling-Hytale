package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.ui.PlayerHudHide;
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

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.List;
import java.util.Objects;

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
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            LOGGER.atWarning().log("Unable to resolve joining player UUID.");
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
        if (playerRef == null) {
            LOGGER.atWarning().log("Unable to find PlayerRef for joining player %s", playerUuid);
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

        private void processPlayerReadyOnWorldThread(@Nonnull PlayerData playerData,
            @Nonnull PlayerRef playerRef,
            Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            boolean inInstanceWorld) {
        UUID uuid = playerRef.getUuid();

        MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
        if (mobLevelingManager != null && store != null) {
            mobLevelingManager.syncTierLevelOverridesForDungeon(store, uuid);
        }

        var movementHasteSystem = EndlessLeveling.getInstance().getMovementHasteSystem();
        if (movementHasteSystem != null && entityRef != null) {
            movementHasteSystem.registerPlayer(uuid, entityRef);
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
            augmentUnlockManager.ensureUnlocksForAllProfiles(playerData);

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

    private void scheduleSkillRetry(@Nonnull UUID uuid, @Nonnull String username) {
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

        try {
            PlayerHud.unregister(uuid);
            PlayerHudHide.unregister(uuid);
        } catch (LinkageError | RuntimeException ex) {
            LOGGER.atWarning().withCause(ex).log("Failed to clean up HUD state for %s on disconnect.", uuid);
        }

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

		var movementHasteSystem = EndlessLeveling.getInstance().getMovementHasteSystem();
		if (movementHasteSystem != null) {
			movementHasteSystem.clearPlayer(uuid);
		}

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        Store<EntityStore> playerStore = playerEntityRef != null ? playerEntityRef.getStore() : null;
        ArmyOfTheDeadPassive.cleanupOwnerSummonsOnDisconnect(uuid, playerStore);
    }

        private void notifyAvailableSkillPoints(@Nonnull PlayerRef playerRef, int skillPoints) {
        if (skillPoints <= 0) {
            return;
        }

        var packetHandler = playerRef.getPacketHandler();
        var primaryMessage = Message.raw(
            nn(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.primary",
                "You have {0} unspent skill points!", skillPoints),
                "You have " + skillPoints + " unspent skill points!"))
                .color("#ffc300");
        var secondaryMessage = Message.join(
            Message.raw(nn(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.secondary.open", "Open "),
                "Open "))
                        .color("#ff9d00"),
            Message.raw(nn(FixedValue.ROOT_COMMAND.value(), "/lvl"))
                .color("#4fd7f7"),
            Message.raw(nn(Lang.tr(playerRef.getUuid(), "notify.skills.unspent.secondary.close", " to invest them"),
                " to invest them"))
                        .color("#ff9d00"));
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);

        var chatMessage = Message.join(
            Message.raw(nn(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_HAVE),
                "You have "))
                .color(nn(ChatMessageTemplate.SKILLS_CHAT_HAVE.colorHex(), "#ff9d00")),
                Message.raw(String.valueOf(skillPoints)).color("#4fd7f7"),
            Message.raw(nn(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_USE),
                " skill points. Use "))
                .color(nn(ChatMessageTemplate.SKILLS_CHAT_USE.colorHex(), "#ff9d00")),
            Message.raw(nn(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_COMMAND),
                "/lvl skills"))
                .color(nn(ChatMessageTemplate.SKILLS_COMMAND.colorHex(), "#4fd7f7")),
            Message.raw(nn(PlayerChatNotifier.text(playerRef, ChatMessageTemplate.SKILLS_CHAT_END),
                " to invest them."))
                .color(nn(ChatMessageTemplate.SKILLS_CHAT_END.colorHex(), "#ff9d00")));
        PlayerChatNotifier.send(playerRef, chatMessage);
    }

        private static String nn(String value, String fallback) {
        return Objects.requireNonNullElse(value, fallback);
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
