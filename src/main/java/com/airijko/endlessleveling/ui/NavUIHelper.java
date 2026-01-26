package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Shared helper for wiring left navigation buttons to UI page navigation.
 */
public final class NavUIHelper {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private NavUIHelper() {
    }

    /**
     * Bind nav button click events for the common left nav panel.
     */
    public static void bindNavEvents(@Nonnull UIEventBuilder events) {
        events.addEventBinding(Activating, "#NavProfile", of("Action", "nav:profile"), false);
        events.addEventBinding(Activating, "#NavSkills", of("Action", "nav:skills"), false);
        events.addEventBinding(Activating, "#NavParty", of("Action", "nav:party"), false);
        events.addEventBinding(Activating, "#NavLeaderboards", of("Action", "nav:leaderboards"), false);
        events.addEventBinding(Activating, "#NavSettings", of("Action", "nav:settings"), false);
    }

    /**
     * Handle a nav: action and open the appropriate page.
     *
     * @return true if the action was a navigation action and a page was opened.
     */
    public static boolean handleNavAction(
            @Nonnull String action,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef) {

        if (!action.startsWith("nav:")) {
            return false;
        }

        String target = action.substring("nav:".length()).toLowerCase();

        // Resolve the Player entity from the current EntityStore, like other UI pages do
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            LOGGER.atSevere().log("NavUIHelper: player component is null for %s", playerRef.getUuid());
            return false;
        }

        LOGGER.atInfo().log("NavUIHelper: navigating to '%s' for %s", target, playerRef.getUuid());

        switch (target) {
            case "skills" -> player.getPageManager()
                    .openCustomPage(ref, store, new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
            case "profile" -> player.getPageManager()
                    .openCustomPage(ref, store, new ProfileUIPage(playerRef, CustomPageLifetime.CanDismiss));
            case "party" -> player.getPageManager()
                    .openCustomPage(ref, store, new PartyUIPage(playerRef, CustomPageLifetime.CanDismiss));
            case "leaderboards" -> player.getPageManager()
                    .openCustomPage(ref, store, new LeaderboardsUIPage(playerRef, CustomPageLifetime.CanDismiss));
            case "settings" -> player.getPageManager()
                    .openCustomPage(ref, store, new SettingsUIPage(playerRef, CustomPageLifetime.CanDismiss));
            default -> {
                LOGGER.atWarning().log("NavUIHelper: unknown nav target '%s'", target);
                return false;
            }
        }

        return true;
    }
}
