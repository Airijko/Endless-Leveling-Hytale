package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.util.Lang;
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
                NavUIHelper.applyNavVersion(ui, playerRef, "settings",
                                "Common/UI/Custom/Pages/SettingsPage.ui",
                                "#SettingsTitle");

                // Bind navigation on the left side
                NavUIHelper.bindNavEvents(events);

                // Bind toggle buttons
                events.addEventBinding(Activating, "#PlayerHudToggle", of("Action", "toggle:playerHud"), false);
                events.addEventBinding(Activating, "#CriticalNotifToggle", of("Action", "toggle:criticalNotif"), false);
                events.addEventBinding(Activating, "#XpNotifToggle", of("Action", "toggle:xpNotif"), false);
                events.addEventBinding(Activating, "#PassiveLevelUpNotifToggle",
                                of("Action", "toggle:passiveLevelUpNotif"),
                                false);
                events.addEventBinding(Activating, "#LuckDoubleDropsNotifToggle",
                                of("Action", "toggle:luckDoubleDropsNotif"),
                                false);
                events.addEventBinding(Activating, "#HealthRegenNotifToggle", of("Action", "toggle:healthRegenNotif"),
                                false);
                events.addEventBinding(Activating, "#AugmentNotifToggle", of("Action", "toggle:augmentNotif"), false);
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

                ui.set("#PlayerHudLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.player_hud.label", "Player HUD"));
                ui.set("#PlayerHudValue.Text", data.isPlayerHudEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#CriticalNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.critical_notif.label",
                                                "Critical Hit Notifications"));
                ui.set("#CriticalNotifValue.Text", data.isCriticalNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#XpNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.xp_notif.label", "XP Gain Notifications"));
                ui.set("#XpNotifValue.Text", data.isXpNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#PassiveLevelUpNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.passive_levelup_notif.label",
                                                "Passive Level-Up Notifications"));
                ui.set("#PassiveLevelUpNotifValue.Text", data.isPassiveLevelUpNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#LuckDoubleDropsNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.luck_double_notif.label",
                                                "Luck Double-Drop Notifications"));
                ui.set("#LuckDoubleDropsNotifValue.Text", data.isLuckDoubleDropsNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#HealthRegenNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.health_regen_notif.label",
                                                "Health Regen Notifications"));
                ui.set("#HealthRegenNotifValue.Text", data.isHealthRegenNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#AugmentNotifLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.augment_notif.label",
                                                "Augment Notifications"));
                ui.set("#AugmentNotifValue.Text", data.isAugmentNotifEnabled()
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#RaceModelLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.race_model.label", "Race Model Visuals"));
                ui.set("#RaceModelValue.Text", raceModelsDisabled
                                ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.disabled", "DISABLED")
                                : data.isUseRaceModel() ? Lang.tr(playerRef.getUuid(), "ui.common.toggle.on", "ON")
                                                : Lang.tr(playerRef.getUuid(), "ui.common.toggle.off", "OFF"));

                ui.set("#SettingsTitleLabel.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.page.title", "Settings"));
                ui.set("#SettingsIntroText.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.page.subtitle",
                                                "Manage personal HUD, notifications, and visual preferences."));

                String toggleText = Lang.tr(playerRef.getUuid(), "ui.settings.page.toggle_button", "TOGGLE");
                ui.set("#PlayerHudToggle.Text", toggleText);
                ui.set("#CriticalNotifToggle.Text", toggleText);
                ui.set("#XpNotifToggle.Text", toggleText);
                ui.set("#PassiveLevelUpNotifToggle.Text", toggleText);
                ui.set("#LuckDoubleDropsNotifToggle.Text", toggleText);
                ui.set("#HealthRegenNotifToggle.Text", toggleText);
                ui.set("#AugmentNotifToggle.Text", toggleText);
                ui.set("#RaceModelToggle.Text", toggleText);

                ui.set("#PlayerHudDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.player_hud",
                                                "Show or hide the Endless Leveling HUD overlay."));
                ui.set("#CriticalNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.critical_notif",
                                                "Toggle floating alerts for critical strike events."));
                ui.set("#XpNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.xp_notif",
                                                "Show notifications whenever you gain experience."));
                ui.set("#PassiveLevelUpNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.passive_levelup_notif",
                                                "Display updates when passives rank up during play."));
                ui.set("#LuckDoubleDropsNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.luck_double_notif",
                                                "Toggle bonus drop proc notifications from luck effects."));
                ui.set("#HealthRegenNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.health_regen_notif",
                                                "Show notifications when health regeneration triggers."));
                ui.set("#AugmentNotifDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.augment_notif",
                                                "Toggle chat notifications for augment status events."));
                ui.set("#RaceModelDescription.Text",
                                Lang.tr(playerRef.getUuid(), "ui.settings.description.race_model",
                                                "Enable race-specific character visuals when available."));
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
                LOGGER.atInfo().log("SettingsUIPage handleDataEvent action=%s for player %s", action,
                                playerRef.getUuid());

                PlayerRef player = Universe.get().getPlayer(playerRef.getUuid());
                if (player == null) {
                        LOGGER.atWarning().log("SettingsUIPage: PlayerRef not found in Universe for %s",
                                        playerRef.getUuid());
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
                Player playerEntity = store.getComponent(ref, Player.getComponentType());

                boolean changed = false;
                if ("toggle:playerHud".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isPlayerHudEnabled();
                        playerData.setPlayerHudEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message.raw(Lang.tr(playerRef.getUuid(),
                                        "ui.settings.player_hud.toggled",
                                        "Player HUD {0}",
                                        newValue ? Lang.tr(playerRef.getUuid(), "ui.common.state.enabled", "enabled")
                                                        : Lang.tr(playerRef.getUuid(), "ui.common.state.disabled",
                                                                        "disabled")))
                                        .color("#ffc300"));

                        if (playerEntity != null) {
                                PlayerHud.openPreferred(playerEntity, playerRef);
                        }
                } else if ("toggle:criticalNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isCriticalNotifEnabled();
                        playerData.setCriticalNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message.raw(Lang.tr(playerRef.getUuid(),
                                        "ui.settings.critical_notif.toggled",
                                        "Critical hit notifications {0}",
                                        newValue ? Lang.tr(playerRef.getUuid(), "ui.common.state.enabled", "enabled")
                                                        : Lang.tr(playerRef.getUuid(), "ui.common.state.disabled",
                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:xpNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isXpNotifEnabled();
                        playerData.setXpNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message.raw(Lang.tr(playerRef.getUuid(), "ui.settings.xp_notif.toggled",
                                        "XP gain notifications {0}",
                                        newValue ? Lang.tr(playerRef.getUuid(), "ui.common.state.enabled", "enabled")
                                                        : Lang.tr(playerRef.getUuid(), "ui.common.state.disabled",
                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:passiveLevelUpNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isPassiveLevelUpNotifEnabled();
                        playerData.setPassiveLevelUpNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message
                                        .raw(Lang.tr(playerRef.getUuid(), "ui.settings.passive_levelup_notif.toggled",
                                                        "Passive level-up notifications {0}",
                                                        newValue ? Lang.tr(playerRef.getUuid(),
                                                                        "ui.common.state.enabled", "enabled")
                                                                        : Lang.tr(playerRef.getUuid(),
                                                                                        "ui.common.state.disabled",
                                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:luckDoubleDropsNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isLuckDoubleDropsNotifEnabled();
                        playerData.setLuckDoubleDropsNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message
                                        .raw(Lang.tr(playerRef.getUuid(), "ui.settings.luck_double_notif.toggled",
                                                        "Luck double-drop notifications {0}",
                                                        newValue ? Lang.tr(playerRef.getUuid(),
                                                                        "ui.common.state.enabled", "enabled")
                                                                        : Lang.tr(playerRef.getUuid(),
                                                                                        "ui.common.state.disabled",
                                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:healthRegenNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isHealthRegenNotifEnabled();
                        playerData.setHealthRegenNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message
                                        .raw(Lang.tr(playerRef.getUuid(), "ui.settings.health_regen_notif.toggled",
                                                        "Health regen notifications {0}",
                                                        newValue ? Lang.tr(playerRef.getUuid(),
                                                                        "ui.common.state.enabled", "enabled")
                                                                        : Lang.tr(playerRef.getUuid(),
                                                                                        "ui.common.state.disabled",
                                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:augmentNotif".equalsIgnoreCase(action)) {
                        boolean newValue = !playerData.isAugmentNotifEnabled();
                        playerData.setAugmentNotifEnabled(newValue);
                        changed = true;
                        player.sendMessage(Message
                                        .raw(Lang.tr(playerRef.getUuid(), "ui.settings.augment_notif.toggled",
                                                        "Augment notifications {0}",
                                                        newValue ? Lang.tr(playerRef.getUuid(),
                                                                        "ui.common.state.enabled", "enabled")
                                                                        : Lang.tr(playerRef.getUuid(),
                                                                                        "ui.common.state.disabled",
                                                                                        "disabled")))
                                        .color("#ffc300"));
                } else if ("toggle:raceModel".equalsIgnoreCase(action)) {
                        if (raceManager != null && raceManager.isRaceModelGloballyDisabled()) {
                                player.sendMessage(Message
                                                .raw(Lang.tr(playerRef.getUuid(),
                                                                "ui.settings.race_model.globally_disabled",
                                                                "Race model visuals are disabled by the server configuration."))
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
                                                .raw(Lang.tr(playerRef.getUuid(), "ui.settings.race_model.enabled",
                                                                "Race model visuals enabled"))
                                                .color("#4fd7f7"));
                        } else {
                                if (raceManager != null) {
                                        raceManager.resetRaceModelIfOnline(playerData);
                                }
                                player.sendMessage(Message
                                                .raw(Lang.tr(playerRef.getUuid(), "ui.settings.race_model.disabled",
                                                                "Race model visuals disabled"))
                                                .color("#ff9900"));
                        }
                }

                if (changed) {
                        EndlessLeveling.getInstance().getPlayerDataManager().save(playerData);
                        rebuild();
                }
        }
}
