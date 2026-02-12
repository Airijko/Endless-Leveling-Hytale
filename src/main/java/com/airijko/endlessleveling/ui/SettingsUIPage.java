package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Settings page that exposes per-player options stored in playerdata.yml.
 */
public class SettingsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public SettingsUIPage(@Nonnull PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/SettingsPage.ui");
        NavUIHelper.applyNavVersion(ui);

        // Bind navigation on the left side
        NavUIHelper.bindNavEvents(events);

        // Bind toggle buttons
        events.addEventBinding(Activating, "#PlayerHudToggle", of("Action", "toggle:playerHud"), false);
        events.addEventBinding(Activating, "#CriticalNotifToggle", of("Action", "toggle:criticalNotif"), false);
        events.addEventBinding(Activating, "#XpNotifToggle", of("Action", "toggle:xpNotif"), false);
        events.addEventBinding(Activating, "#PassiveLevelUpNotifToggle", of("Action", "toggle:passiveLevelUpNotif"),
                false);
        events.addEventBinding(Activating, "#LuckDoubleDropsNotifToggle", of("Action", "toggle:luckDoubleDropsNotif"),
                false);
        events.addEventBinding(Activating, "#HealthRegenNotifToggle", of("Action", "toggle:healthRegenNotif"), false);
        events.addEventBinding(Activating, "#RaceModelToggle", of("Action", "toggle:raceModel"), false);

        // Populate current values from PlayerData
        PlayerRef player = Universe.get().getPlayer(playerRef.getUuid());
        if (player == null) {
            return;
        }

        PlayerData data = EndlessLeveling.getInstance()
                .getPlayerDataManager()
                .get(playerRef.getUuid());

        if (data == null) {
            return;
        }

        var raceManager = EndlessLeveling.getInstance().getRaceManager();
        boolean raceModelsDisabled = raceManager != null && raceManager.isRaceModelGloballyDisabled();

        ui.set("#PlayerHudLabel.Text", "Player HUD");
        ui.set("#PlayerHudValue.Text", data.isPlayerHudEnabled() ? "ON" : "OFF");

        ui.set("#CriticalNotifLabel.Text", "Critical Hit Notifications");
        ui.set("#CriticalNotifValue.Text", data.isCriticalNotifEnabled() ? "ON" : "OFF");

        ui.set("#XpNotifLabel.Text", "XP Gain Notifications");
        ui.set("#XpNotifValue.Text", data.isXpNotifEnabled() ? "ON" : "OFF");

        ui.set("#PassiveLevelUpNotifLabel.Text", "Passive Level-Up Notifications");
        ui.set("#PassiveLevelUpNotifValue.Text", data.isPassiveLevelUpNotifEnabled() ? "ON" : "OFF");

        ui.set("#LuckDoubleDropsNotifLabel.Text", "Luck Double-Drop Notifications");
        ui.set("#LuckDoubleDropsNotifValue.Text", data.isLuckDoubleDropsNotifEnabled() ? "ON" : "OFF");

        ui.set("#HealthRegenNotifLabel.Text", "Health Regen Notifications");
        ui.set("#HealthRegenNotifValue.Text", data.isHealthRegenNotifEnabled() ? "ON" : "OFF");

        ui.set("#RaceModelLabel.Text", "Race Model Visuals");
        ui.set("#RaceModelValue.Text", raceModelsDisabled ? "DISABLED" : data.isUseRaceModel() ? "ON" : "OFF");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isEmpty()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }
        }

        if (data.action == null || data.action.isEmpty()) {
            return;
        }

        String action = data.action;
        LOGGER.atInfo().log("SettingsUIPage handleDataEvent action=%s for player %s", action, playerRef.getUuid());

        PlayerRef player = Universe.get().getPlayer(playerRef.getUuid());
        if (player == null) {
            LOGGER.atWarning().log("SettingsUIPage: PlayerRef not found in Universe for %s", playerRef.getUuid());
            return;
        }

        PlayerData playerData = EndlessLeveling.getInstance()
                .getPlayerDataManager()
                .get(playerRef.getUuid());
        if (playerData == null) {
            LOGGER.atWarning().log("SettingsUIPage: PlayerData is null for %s", playerRef.getUuid());
            return;
        }
        var raceManager = EndlessLeveling.getInstance().getRaceManager();

        boolean changed = false;
        if ("toggle:playerHud".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isPlayerHudEnabled();
            playerData.setPlayerHudEnabled(newValue);
            changed = true;
            player.sendMessage(Message.raw("Player HUD " + (newValue ? "enabled" : "disabled")).color("#ffc300"));

            Player entityPlayer = store.getComponent(ref, Player.getComponentType());
            if (entityPlayer != null) {
                if (newValue) {
                    PlayerHud.open(entityPlayer, playerRef);
                } else {
                    PlayerHud.close(entityPlayer, playerRef);
                }
            }
        } else if ("toggle:criticalNotif".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isCriticalNotifEnabled();
            playerData.setCriticalNotifEnabled(newValue);
            changed = true;
            player.sendMessage(Message.raw("Critical hit notifications " + (newValue ? "enabled" : "disabled"))
                    .color("#ffc300"));
        } else if ("toggle:xpNotif".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isXpNotifEnabled();
            playerData.setXpNotifEnabled(newValue);
            changed = true;
            player.sendMessage(Message.raw("XP gain notifications " + (newValue ? "enabled" : "disabled"))
                    .color("#ffc300"));
        } else if ("toggle:passiveLevelUpNotif".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isPassiveLevelUpNotifEnabled();
            playerData.setPassiveLevelUpNotifEnabled(newValue);
            changed = true;
            player.sendMessage(Message
                    .raw("Passive level-up notifications " + (newValue ? "enabled" : "disabled"))
                    .color("#ffc300"));
        } else if ("toggle:luckDoubleDropsNotif".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isLuckDoubleDropsNotifEnabled();
            playerData.setLuckDoubleDropsNotifEnabled(newValue);
            changed = true;
            player.sendMessage(Message
                    .raw("Luck double-drop notifications " + (newValue ? "enabled" : "disabled"))
                    .color("#ffc300"));
        } else if ("toggle:healthRegenNotif".equalsIgnoreCase(action)) {
            boolean newValue = !playerData.isHealthRegenNotifEnabled();
            playerData.setHealthRegenNotifEnabled(newValue);
            changed = true;
            player.sendMessage(Message
                    .raw("Health regen notifications " + (newValue ? "enabled" : "disabled"))
                    .color("#ffc300"));
        } else if ("toggle:raceModel".equalsIgnoreCase(action)) {
            if (raceManager != null && raceManager.isRaceModelGloballyDisabled()) {
                player.sendMessage(Message
                        .raw("Race model visuals are disabled by the server configuration.")
                        .color("#ff6666"));
                if (raceManager != null) {
                    raceManager.resetRaceModelIfOnline(playerData);
                }
                rebuild();
                return;
            }
            boolean newValue = !playerData.isUseRaceModel();
            playerData.setUseRaceModel(newValue);
            changed = true;
            if (newValue) {
                if (raceManager != null) {
                    raceManager.applyRaceModelIfEnabled(playerData);
                }
                player.sendMessage(Message
                        .raw("Race model visuals enabled").color("#4fd7f7"));
            } else {
                if (raceManager != null) {
                    raceManager.resetRaceModelIfOnline(playerData);
                }
                player.sendMessage(Message
                        .raw("Race model visuals disabled").color("#ff9900"));
            }
        }

        if (changed) {
            EndlessLeveling.getInstance().getPlayerDataManager().save(playerData);
            rebuild();
        }
    }
}
