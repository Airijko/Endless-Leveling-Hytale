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

        // Bind navigation on the left side
        NavUIHelper.bindNavEvents(events);

        // Bind toggle buttons
        events.addEventBinding(Activating, "#PlayerHudToggle", of("Action", "toggle:playerHud"), false);
        events.addEventBinding(Activating, "#CriticalNotifToggle", of("Action", "toggle:criticalNotif"), false);
        events.addEventBinding(Activating, "#XpNotifToggle", of("Action", "toggle:xpNotif"), false);

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

        ui.set("#PlayerHudLabel.Text", "Player HUD");
        ui.set("#PlayerHudValue.Text", data.isPlayerHudEnabled() ? "ON" : "OFF");

        ui.set("#CriticalNotifLabel.Text", "Critical Hit Notifications");
        ui.set("#CriticalNotifValue.Text", data.isCriticalNotifEnabled() ? "ON" : "OFF");

        ui.set("#XpNotifLabel.Text", "XP Gain Notifications");
        ui.set("#XpNotifValue.Text", data.isXpNotifEnabled() ? "ON" : "OFF");
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
        }

        if (changed) {
            EndlessLeveling.getInstance().getPlayerDataManager().save(playerData);
            rebuild();
        }
    }
}
