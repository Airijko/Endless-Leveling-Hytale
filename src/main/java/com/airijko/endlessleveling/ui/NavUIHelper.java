package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.airijko.endlessleveling.util.Lang;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Shared helper for wiring left navigation buttons to UI page navigation.
 */
public final class NavUIHelper {

        private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
        private static final String NAV_VERSION = resolveVersion();
        private static final String BRAND_TITLE = "ENDLESS LEVELING";
        private static final String BRAND_NAV_HEADER = "ENDLESS";
        private static final String BRAND_NAV_SUB_HEADER = "LEVELING";
        private static final String NAV_RESOURCE_PATH = "Common/UI/Custom/Pages/Nav/LeftNavPanel.ui";
        private static final Map<String, String> RESOURCE_TEXT_CACHE = new ConcurrentHashMap<>();
        private static final Set<String> MISSING_SELECTOR_WARNED = ConcurrentHashMap.newKeySet();
        private static final Set<String> MISSING_RESOURCE_WARNED = ConcurrentHashMap.newKeySet();
        private static final Value<String> NAV_BUTTON_STYLE = Value.ref("Pages/Nav/LeftNavPanel.ui",
                        "LeftNavButtonStyle");
        private static final Value<String> NAV_BUTTON_STYLE_SELECTED = Value.ref("Pages/Nav/LeftNavPanel.ui",
                        "LeftNavButtonStyleSelected");

        private NavUIHelper() {
        }

        /**
         * Write the current plugin version into the shared nav panel.
         */
        public static void applyNavVersion(
                        @Nonnull UICommandBuilder ui,
                        @Nonnull PlayerRef playerRef,
                        @Nonnull String activeNav) {
                applyNavVersion(ui, playerRef, activeNav, null, null);
        }

        public static void applyNavVersion(
                        @Nonnull UICommandBuilder ui,
                        @Nonnull PlayerRef playerRef,
                        @Nonnull String activeNav,
                        String pageTitleSelector) {
                applyNavVersion(ui, playerRef, activeNav, null, pageTitleSelector);
        }

        public static void applyNavVersion(
                        @Nonnull UICommandBuilder ui,
                        @Nonnull PlayerRef playerRef,
                        @Nonnull String activeNav,
                        String pageResourcePath,
                        String pageTitleSelector) {
                ui.set("#NavProfile.Text", Lang.tr(playerRef.getUuid(), "ui.nav.profile", "PROFILE"));
                ui.set("#NavSkills.Text", Lang.tr(playerRef.getUuid(), "ui.nav.skills", "SKILLS"));
                ui.set("#NavRaces.Text", Lang.tr(playerRef.getUuid(), "ui.nav.races", "RACES"));
                ui.set("#NavClasses.Text", Lang.tr(playerRef.getUuid(), "ui.nav.classes", "CLASSES"));
                ui.set("#NavAugments.Text", Lang.tr(playerRef.getUuid(), "ui.nav.augments", "AUGMENTS"));
                ui.set("#NavLeaderboards.Text", Lang.tr(playerRef.getUuid(), "ui.nav.leaderboards", "LEADERBOARDS"));
                ui.set("#NavSupport.Text", Lang.tr(playerRef.getUuid(), "ui.nav.support", "SUPPORT"));
                ui.set("#NavSettings.Text", Lang.tr(playerRef.getUuid(), "ui.nav.settings", "SETTINGS"));
                ui.set("#NavVersion.Text", NAV_VERSION);
                applyBrandingEnforcement(ui, pageResourcePath, pageTitleSelector);
                applySelectedNavStyle(ui, activeNav);
        }

        private static void applyBrandingEnforcement(
                        @Nonnull UICommandBuilder ui,
                        String pageResourcePath,
                        String pageTitleSelector) {
                safeSetText(ui, pageResourcePath, pageTitleSelector, BRAND_TITLE);

                // Nav header is split into two labels in LeftNavPanel.ui.
                safeSetText(ui, NAV_RESOURCE_PATH, "#NavHeader", BRAND_NAV_HEADER);
                safeSetText(ui, NAV_RESOURCE_PATH, "#NavSubHeader", BRAND_NAV_SUB_HEADER);
        }

        private static void safeSetText(
                        @Nonnull UICommandBuilder ui,
                        String resourcePath,
                        String selector,
                        String text) {
                if (selector == null || selector.isBlank()) {
                        return;
                }

                if (resourcePath != null && !resourcePath.isBlank()
                                && !resourceContainsSelector(resourcePath, selector)) {
                        return;
                }

                ui.set(selector + ".Text", text);
        }

        private static boolean resourceContainsSelector(String resourcePath, String selector) {
                String resourceContent = RESOURCE_TEXT_CACHE.computeIfAbsent(resourcePath, NavUIHelper::readResourceText);
                if (resourceContent == null) {
                        if (MISSING_RESOURCE_WARNED.add(resourcePath)) {
                                LOGGER.atWarning().log("NavUIHelper: resource '%s' not found for selector guard", resourcePath);
                        }
                        return false;
                }

                String selectorId = selector.startsWith("#") ? selector.substring(1) : selector;
                Pattern selectorPattern = Pattern.compile("#" + Pattern.quote(selectorId) + "\\b");
                boolean exists = selectorPattern.matcher(resourceContent).find();
                if (!exists) {
                        String warningKey = resourcePath + "|" + selector;
                        if (MISSING_SELECTOR_WARNED.add(warningKey)) {
                                LOGGER.atWarning().log("NavUIHelper: selector '%s' missing in %s; skipping write",
                                                selector,
                                                resourcePath);
                        }
                }
                return exists;
        }

        private static String readResourceText(String resourcePath) {
                try (InputStream in = NavUIHelper.class.getClassLoader().getResourceAsStream(resourcePath)) {
                        if (in == null) {
                                return null;
                        }
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                        LOGGER.atWarning().log("NavUIHelper: failed reading resource %s: %s", resourcePath, e.getMessage());
                        return null;
                }
        }

        private static void applySelectedNavStyle(@Nonnull UICommandBuilder ui, @Nonnull String activeNav) {
                setNavButtonSelected(ui, "#NavProfile", "profile".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavSkills", "skills".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavAugments", "augments".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavRaces", "races".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavClasses", "classes".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavLeaderboards", "leaderboards".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavSupport", "support".equalsIgnoreCase(activeNav));
                setNavButtonSelected(ui, "#NavSettings", "settings".equalsIgnoreCase(activeNav));
        }

        private static void setNavButtonSelected(@Nonnull UICommandBuilder ui, @Nonnull String selector,
                        boolean selected) {
                ui.set(selector + ".Style", selected ? NAV_BUTTON_STYLE_SELECTED : NAV_BUTTON_STYLE);
        }

        /**
         * Bind nav button click events for the common left nav panel.
         */
        public static void bindNavEvents(@Nonnull UIEventBuilder events) {
                events.addEventBinding(Activating, "#NavProfile", of("Action", "nav:profile"), false);
                events.addEventBinding(Activating, "#NavRaces", of("Action", "nav:races"), false);
                events.addEventBinding(Activating, "#NavClasses", of("Action", "nav:classes"), false);
                events.addEventBinding(Activating, "#NavSkills", of("Action", "nav:skills"), false);
                events.addEventBinding(Activating, "#NavAugments", of("Action", "nav:augments"), false);
                events.addEventBinding(Activating, "#NavLeaderboards", of("Action", "nav:leaderboards"), false);
                events.addEventBinding(Activating, "#NavSupport", of("Action", "nav:support"), false);
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

                // Resolve the Player entity from the current EntityStore, like other UI pages
                // do
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                        LOGGER.atSevere().log("NavUIHelper: player component is null for %s", playerRef.getUuid());
                        return false;
                }

                LOGGER.atInfo().log("NavUIHelper: navigating to '%s' for %s", target, playerRef.getUuid());

                switch (target) {
                        case "skills" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "profile" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new ProfileUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "races" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new RacesUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "classes" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new ClassesUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "augments" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new AugmentsUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "leaderboards" -> player.getPageManager()
                                        .openCustomPage(ref, store, new LeaderboardsUIPage(playerRef,
                                                        CustomPageLifetime.CanDismiss));
                        case "settings" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new SettingsUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        case "support" -> player.getPageManager()
                                        .openCustomPage(ref, store,
                                                        new SupportUIPage(playerRef, CustomPageLifetime.CanDismiss));
                        default -> {
                                LOGGER.atWarning().log("NavUIHelper: unknown nav target '%s'", target);
                                return false;
                        }
                }

                return true;
        }

        private static String resolveVersion() {
                // Try to read the plugin manifest bundled in resources. Fallback to a safe
                // placeholder if unavailable.
                try (java.io.InputStream in = NavUIHelper.class.getClassLoader().getResourceAsStream("manifest.json")) {
                        if (in == null) {
                                LOGGER.atWarning().log("NavUIHelper: manifest.json not found on classpath");
                                return "v?.?";
                        }

                        String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        int keyIndex = json.indexOf("\"Version\"");
                        if (keyIndex == -1) {
                                LOGGER.atWarning().log("NavUIHelper: Version key missing in manifest.json");
                                return "v?.?";
                        }

                        int colonIndex = json.indexOf(':', keyIndex);
                        if (colonIndex == -1) {
                                return "v?.?";
                        }

                        int firstQuote = json.indexOf('"', colonIndex);
                        int secondQuote = firstQuote >= 0 ? json.indexOf('"', firstQuote + 1) : -1;
                        if (firstQuote >= 0 && secondQuote > firstQuote) {
                                String version = json.substring(firstQuote + 1, secondQuote).trim();
                                if (!version.isEmpty()) {
                                        return "v" + version;
                                }
                        }
                        return "v?.?";
                } catch (Exception e) {
                        LOGGER.atWarning().withCause(e).log("NavUIHelper: failed to read manifest version");
                        return "v?.?";
                }
        }

}
