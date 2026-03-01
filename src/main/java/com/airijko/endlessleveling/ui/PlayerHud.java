package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.compatibility.MultipleHudCompatibility;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.RaceManager;
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
        this.targetPlayerRef = playerRef;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        uiCommandBuilder.append("Hud/EndlessPlayerHud.ui");
        built.set(true);
        ACTIVE_HUDS.put(targetPlayerRef.getUuid(), this);
        pushHudState(uiCommandBuilder);
    }

    private void pushHudState(@Nonnull UICommandBuilder uiCommandBuilder) {
        if (!built.get()) {
            return; // HUD has not finished building, so skip pushing state to avoid missing element
                    // errors.
        }
        PlayerData data = getPlayerData();
        if (data == null || !data.isPlayerHudEnabled()) {
            return; // Do not push updates when the HUD is disabled or data is unavailable.
        }
        uiCommandBuilder.set("#Level.Text", resolveHudLabel());
        double progress = resolveXpProgress();
        uiCommandBuilder.set("#ProgressBar.Value", progress);
        uiCommandBuilder.set("#ProgressBarEffect.Value", progress);
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
            uiCommandBuilder.set("#SecondaryIcon.Visible", false);
        }
        setClassIcon(uiCommandBuilder, "#PrimaryIcon", resolveClassIconId(true));
        if (secondaryEnabled) {
            setClassIcon(uiCommandBuilder, "#SecondaryIcon", resolveClassIconId(false));
        }
        update(false, uiCommandBuilder);
    }

    private String resolveLevelValue() {
        PlayerData data = getPlayerData();
        if (data == null) {
            return "--";
        }
        return Integer.toString(data.getLevel());
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

        if (data.getLevel() >= levelingManager.getLevelCap()) {
            return "LVL " + levelingManager.getLevelCap() + "   MAX LEVEL";
        }

        double xpNeeded = levelingManager.getXpForNextLevel(data.getLevel());
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

        if (data.getLevel() >= levelingManager.getLevelCap()) {
            return 1.0;
        }

        double xpNeeded = levelingManager.getXpForNextLevel(data.getLevel());
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

        PlayerData data = hud.getPlayerData();
        if (data == null || !data.isPlayerHudEnabled()) {
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
        ACTIVE_HUDS.remove(uuid);
    }

    public static boolean isActive(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return ACTIVE_HUDS.containsKey(uuid);
    }

    public static void open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        // Prefer MultipleHUD if installed, fall back to vanilla HudManager.
        if (MultipleHudCompatibility.showHud(player, playerRef, new PlayerHud(playerRef))) {
            LOGGER.atInfo().log("Opened PlayerHud via MultipleHUD for %s", playerRef.getUuid());
            return;
        }

        LOGGER.atInfo().log("Opening PlayerHud via default HudManager for %s", playerRef.getUuid());
        player.getHudManager().setCustomHud(playerRef, new PlayerHud(playerRef));
    }

    public static void close(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        LOGGER.atInfo().log("Closing PlayerHud for %s", playerRef.getUuid());

        // Prefer MultipleHUD if installed, fall back to vanilla HudManager.
        if (MultipleHudCompatibility.showHud(player, playerRef, new EmptyHud(playerRef))) {
            unregister(playerRef.getUuid());
            return;
        }

        player.getHudManager().setCustomHud(playerRef, new EmptyHud(playerRef));
        unregister(playerRef.getUuid());
    }
}
