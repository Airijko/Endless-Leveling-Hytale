package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.compatibility.MultipleHudCompatibility;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerHud extends CustomUIHud {

    public static final String ID = "EndlessLeveling:PlayerHud";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Map<UUID, PlayerHud> ACTIVE_HUDS = new ConcurrentHashMap<>();
    private static final Map<String, String> DEFAULT_CLASS_ICONS = Map.ofEntries(
            Map.entry("*", "Weapon_Longsword_Adamantite_Saurian"),
            Map.entry("adventurer", "Ingredient_Life_Essence"),
            Map.entry("assassin", "Weapon_Daggers_Mithril"),
            Map.entry("juggernaut", "Weapon_Battleaxe_Mithril"),
            Map.entry("mage", "Weapon_Spellbook_Grimoire_Brown"),
            Map.entry("arcanist", "Weapon_Staff_Bronze"),
            Map.entry("battlemage", "Weapon_Staff_Onyxium"),
            Map.entry("oracle", "Weapon_Daggers_Mithril"),
            Map.entry("marksman", "Weapon_Shortbow_Combat"),
            Map.entry("skirmisher", "Weapon_Longsword_Mithril"),
            Map.entry("vanguard", "Weapon_Mace_Prisma"),
            Map.entry("example", "Potion_Health"));

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final MobLevelingManager mobLevelingManager;
    private final RaceManager raceManager;
    private final ClassManager classManager;
    private final AugmentHudOverlayController augmentHudOverlayController;
    private final PlayerRef targetPlayerRef;
    private final java.util.concurrent.atomic.AtomicBoolean built = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    public PlayerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
        this.raceManager = EndlessLeveling.getInstance().getRaceManager();
        this.classManager = EndlessLeveling.getInstance().getClassManager();
        AugmentManager augmentManager = EndlessLeveling.getInstance().getAugmentManager();
        AugmentRuntimeManager augmentRuntimeManager = EndlessLeveling.getInstance().getAugmentRuntimeManager();
        this.augmentHudOverlayController = new AugmentHudOverlayController(augmentManager, augmentRuntimeManager);
        this.targetPlayerRef = playerRef;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        UUID uuid = targetPlayerRef.getUuid();
        // Build can run asynchronously after a newer HUD replaced this one. Ignore
        // stale
        // builds so old overlays cannot re-append.
        if (uuid == null || ACTIVE_HUDS.get(uuid) != this) {
            LOGGER.atFine().log("Ignoring stale PlayerHud build for %s", uuid);
            return;
        }

        // Build may be invoked more than once by the UI pipeline; append exactly once
        // per HUD instance to prevent stacked duplicate overlays.
        if (!built.compareAndSet(false, true)) {
            LOGGER.atFine().log("Skipping duplicate PlayerHud build for %s", uuid);
            return;
        }

        uiCommandBuilder.append("Hud/EndlessPlayerHud.ui");
        // Avoid calling update() during build. The initial build packet should only
        // append
        // the UI, while dynamic values are pushed on the next refresh tick.
    }

    private void pushHudState(@Nonnull UICommandBuilder uiCommandBuilder) {
        if (!built.get()) {
            return; // HUD has not finished building, so skip pushing state to avoid missing element
                    // errors.
        }
        if (!targetPlayerRef.isValid()) {
            unregister(targetPlayerRef.getUuid());
            return; // Player is no longer connected; avoid sending UI commands to stale HUD roots.
        }
        PlayerData data = getPlayerData();
        if (data == null) {
            return; // Do not push updates when the HUD is disabled or data is unavailable.
        }
        uiCommandBuilder.set("#Level.Text", resolveHudLabel());
        double progress = resolveXpProgress();
        uiCommandBuilder.set("#ProgressBar.Value", progress);
        uiCommandBuilder.set("#InfoRaceValue.Text", resolveRaceLabel());
        uiCommandBuilder.set("#InfoMobLevelValue.Text", resolveMobLevelLabel());
        uiCommandBuilder.set("#PrimaryClass.Text", resolveClassLabel(true));
        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        uiCommandBuilder.set("#SecondaryClassRow.Visible", secondaryEnabled);
        uiCommandBuilder.set("#SecondaryClass.Visible", secondaryEnabled);
        if (secondaryEnabled) {
            uiCommandBuilder.set("#SecondaryClass.Text", resolveClassLabel(false));
        } else {
            uiCommandBuilder.set("#SecondaryClass.Text", "");
        }

        // Runtime ItemIcon.ItemId updates on HUDs can hard-fail CustomUI command
        // application on some clients. Keep HUD icons hidden to prioritize stability.
        uiCommandBuilder.set("#PrimaryIcon.Visible", false);
        uiCommandBuilder.set("#SecondaryIcon.Visible", false);

        applyAugmentOverlay(uiCommandBuilder);

        update(false, uiCommandBuilder);
    }

    private void applyAugmentOverlay(@Nonnull UICommandBuilder uiCommandBuilder) {
        AugmentHudOverlayController.HudOverlayState overlayState = augmentHudOverlayController != null
                ? augmentHudOverlayController.resolve(targetPlayerRef)
                : AugmentHudOverlayController.HudOverlayState.hidden();

        applyOverlayBar(uiCommandBuilder,
                "#ActiveAugmentPanel",
                "#ActiveAugmentName",
                "#ActiveAugmentProgress",
                overlayState.durationBar());
        applyOverlayBar(uiCommandBuilder,
                "#ShieldStatusPanel",
                "#ShieldStatusName",
                "#ShieldStatusProgress",
                overlayState.shieldBar());

        uiCommandBuilder.set("#ConquerorIconPanel.Visible", overlayState.conquerorActive());
        uiCommandBuilder.set("#ConquerorIcon.Visible", overlayState.conquerorActive());
        uiCommandBuilder.set("#ConquerorStackCount.Visible", overlayState.conquerorActive());
        uiCommandBuilder.set("#ConquerorStackCount.Text",
                overlayState.conquerorActive() ? Integer.toString(Math.max(0, overlayState.conquerorStacks())) : "");
    }

    private void applyOverlayBar(@Nonnull UICommandBuilder uiCommandBuilder,
            @Nonnull String panelSelector,
            @Nonnull String labelSelector,
            @Nonnull String progressSelector,
            @Nonnull AugmentHudOverlayController.BarState state) {
        boolean visible = state != null && state.visible();
        uiCommandBuilder.set(panelSelector + ".Visible", visible);
        if (!visible) {
            uiCommandBuilder.set(labelSelector + ".Text", "");
            uiCommandBuilder.set(progressSelector + ".Value", 0.0D);
            return;
        }

        uiCommandBuilder.set(labelSelector + ".Text", state.label());
        uiCommandBuilder.set(progressSelector + ".Value", state.progress());
    }

    private String resolveRaceLabel() {
        PlayerData data = getPlayerData();
        if (data == null) {
            return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        }
        String raceId = data.getRaceId();
        if (raceId == null || raceId.isBlank() || raceId.equalsIgnoreCase("none")) {
            return Lang.tr(targetPlayerRef.getUuid(), "hud.race.none", "No Race");
        }

        if (raceManager != null && raceManager.isEnabled()) {
            RaceDefinition race = raceManager.getRace(raceId);
            if (race != null) {
                String display = race.getDisplayName();
                if (display != null && !display.isBlank()) {
                    return display;
                }
                return race.getId();
            }
        }
        return raceId;
    }

    private String resolveClassLabel(boolean primary) {
        PlayerData data = getPlayerData();
        if (data == null) {
            return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        }
        if (classManager != null && classManager.isEnabled()) {
            CharacterClassDefinition def = primary
                    ? classManager.getPlayerPrimaryClass(data)
                    : classManager.getPlayerSecondaryClass(data);
            if (def != null) {
                String display = def.getDisplayName();
                if (display != null && !display.isBlank()) {
                    return display;
                }
                return def.getId();
            }
        }
        String id = primary ? data.getPrimaryClassId() : data.getSecondaryClassId();
        return (id == null || id.isBlank()) ? Lang.tr(targetPlayerRef.getUuid(), "hud.class.none", "None") : id;
    }

    private String resolveMobLevelLabel() {
        if (mobLevelingManager == null || !mobLevelingManager.isMobLevelingEnabled()) {
            return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        }

        Ref<EntityStore> ref = targetPlayerRef.getReference();
        if (ref == null) {
            return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        }

        // When player-based mob leveling is enabled, show the expected range for this
        // player instead of a single level sample.
        if (mobLevelingManager.isPlayerBasedMode()) {
            PlayerData data = getPlayerData();
            if (data == null) {
                return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
            }
            var range = mobLevelingManager.getPlayerBasedLevelRange(data.getLevel());
            if (range != null) {
                if (range.min() == range.max()) {
                    return Lang.tr(targetPlayerRef.getUuid(), "hud.mob.level.single", "Lv. {0}", range.min());
                }
                return Lang.tr(targetPlayerRef.getUuid(), "hud.mob.level.range", "Lv. {0}-{1}", range.min(),
                        range.max());
            }
        }

        Store<EntityStore> store = ref.getStore();
        try {
            TransformComponent transform = store != null
                    ? store.getComponent(ref, TransformComponent.getComponentType())
                    : null;
            if (transform == null || transform.getPosition() == null) {
                return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
            }

            int level = mobLevelingManager.resolveMobLevel(store, transform.getPosition());
            return level > 0
                    ? Lang.tr(targetPlayerRef.getUuid(), "hud.mob.level.single_compact", "Lv {0}", level)
                    : Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        } catch (IllegalStateException ex) {
            // Store.assertThread can throw when XP events fire on a different world thread;
            // fall back to no label instead of crashing the server.
            LOGGER.atFine().withCause(ex).log("Skipping mob level resolution off-thread for %s",
                    targetPlayerRef.getUuid());
            return Lang.tr(targetPlayerRef.getUuid(), "hud.common.unavailable", "--");
        }
    }

    private void setClassIcon(UICommandBuilder uiCommandBuilder, String selector, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            uiCommandBuilder.set(selector + ".Visible", false);
            return;
        }
        uiCommandBuilder.set(selector + ".ItemId", itemId);
        uiCommandBuilder.set(selector + ".Visible", true);
    }

    private String resolveClassIconId(boolean primary) {
        PlayerData data = getPlayerData();
        if (data == null) {
            return null;
        }

        CharacterClassDefinition definition = null;
        if (classManager != null && classManager.isEnabled()) {
            definition = primary ? classManager.getPlayerPrimaryClass(data)
                    : classManager.getPlayerSecondaryClass(data);
        }

        if (definition != null) {
            String configured = definition.getIconItemId();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
            String fallback = DEFAULT_CLASS_ICONS.get(normalizeClassId(definition.getId()));
            if (fallback != null && !fallback.isBlank()) {
                return fallback;
            }
        }

        String id = primary ? data.getPrimaryClassId() : data.getSecondaryClassId();
        if (id != null && !id.isBlank()) {
            String fallback = DEFAULT_CLASS_ICONS.get(normalizeClassId(id));
            if (fallback != null && !fallback.isBlank()) {
                return fallback;
            }
        }

        return DEFAULT_CLASS_ICONS.get("*");
    }

    private static String normalizeClassId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String resolveHudLabel() {
        PlayerData data = getPlayerData();
        if (data == null) {
            return "LVL --   XP: 0 / --";
        }

        double currentXp = Math.max(0.0, data.getXp());
        if (levelingManager == null) {
            return "LVL " + data.getLevel() + "   XP: " + formatXpValue(currentXp);
        }

        int effectiveCap = levelingManager.getLevelCap(data);

        if (data.getLevel() >= effectiveCap) {
            return "LVL " + effectiveCap + "   MAX LEVEL";
        }

        double xpNeeded = levelingManager.getXpForNextLevel(data, data.getLevel());
        if (!Double.isFinite(xpNeeded) || xpNeeded <= 0.0) {
            return "LVL " + data.getLevel() + "   MAX LEVEL";
        }

        return "LVL " + data.getLevel() + "   XP: "
                + formatXpValue(currentXp) + " / " + formatXpValue(xpNeeded);
    }

    private double resolveXpProgress() {
        PlayerData data = getPlayerData();
        if (data == null || levelingManager == null) {
            return 0.0;
        }

        int effectiveCap = levelingManager.getLevelCap(data);
        if (data.getLevel() >= effectiveCap) {
            return 1.0;
        }

        double xpNeeded = levelingManager.getXpForNextLevel(data, data.getLevel());
        if (!Double.isFinite(xpNeeded) || xpNeeded <= 0.0) {
            return 0.0;
        }

        double currentXp = Math.max(0.0, data.getXp());
        double ratio = currentXp / xpNeeded;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private PlayerData getPlayerData() {
        if (playerDataManager == null) {
            return null;
        }
        return playerDataManager.get(targetPlayerRef.getUuid());
    }

    private String formatXpValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }

        long rounded = Math.round(value);
        return Long.toString(rounded);
    }

    public void refreshHud() {
        pushHudState(new UICommandBuilder());
    }

    public static void refreshHud(UUID uuid) {
        if (uuid == null) {
            return;
        }

        PlayerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return;
        }
        if (!hud.targetPlayerRef.isValid()) {
            unregister(uuid);
            return;
        }

        PlayerData data = hud.getPlayerData();
        if (data == null) {
            return;
        }

        hud.refreshHud();
    }

    /** Refresh all active HUDs (used after config reloads). */
    public static void refreshAll() {
        for (UUID uuid : ACTIVE_HUDS.keySet()) {
            refreshHud(uuid);
        }
    }

    public static void unregister(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (HudSlotManager.getHudLock(uuid)) {
            unregisterInternal(uuid);
        }
    }

    private static void unregisterInternal(UUID uuid) {
        PlayerHud removed = ACTIVE_HUDS.remove(uuid);
        if (removed != null) {
            removed.built.set(false);
        }
    }

    public static boolean isActive(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return ACTIVE_HUDS.containsKey(uuid);
    }

    public static void openPreferred(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        PlayerDataManager playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        PlayerData playerData = playerDataManager == null ? null : playerDataManager.get(playerRef.getUuid());
        if (playerData != null && !playerData.isPlayerHudEnabled()) {
            PlayerHudHide.open(player, playerRef);
            return;
        }
        open(player, playerRef);
    }

    public static void open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null) {
            return;
        }

        synchronized (HudSlotManager.getHudLock(uuid)) {
            PlayerHudHide.unregister(uuid);

            PlayerHud trackedHud = ACTIVE_HUDS.get(uuid);
            if (trackedHud != null) {
                if (!trackedHud.targetPlayerRef.isValid()) {
                    unregisterInternal(uuid);
                } else {
                    LOGGER.atFine().log("PlayerHud already tracked for %s; skipping duplicate open", uuid);
                    return;
                }
            }

            PlayerHud newHud = new PlayerHud(playerRef);
            ACTIVE_HUDS.put(uuid, newHud);

            // Prefer MultipleHUD if available so EndlessLeveling can coexist with
            // other custom HUD mods.
            if (MultipleHudCompatibility.showHud(player, playerRef, HudSlotManager.MULTI_HUD_SLOT, newHud)) {
                LOGGER.atInfo().log("Opening PlayerHud via MultipleHUD for %s", uuid);
                return;
            }

            var hudManager = player.getHudManager();
            var existingHud = hudManager.getCustomHud();
            if (existingHud instanceof PlayerHud existingPlayerHud) {
                ACTIVE_HUDS.put(uuid, existingPlayerHud);
                LOGGER.atFine().log("PlayerHud already active for %s; skipping duplicate open", uuid);
                return;
            }

            // HudManager does not hide the previous HUD when replacing non-null ->
            // non-null.
            // Explicitly clear first to avoid stacked overlays.
            hudManager.setCustomHud(playerRef, null);

            LOGGER.atInfo().log("Opening PlayerHud via default HudManager for %s", uuid);
            hudManager.setCustomHud(playerRef, newHud);
        }
    }

}
