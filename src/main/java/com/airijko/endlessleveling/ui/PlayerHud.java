package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.compatibility.MultipleHudCompatibility;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.logger.HytaleLogger;
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

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final PlayerRef targetPlayerRef;

    public PlayerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.targetPlayerRef = playerRef;
        ACTIVE_HUDS.put(playerRef.getUuid(), this);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        uiCommandBuilder.append("Hud/EndlessPlayerHud.ui");
        pushHudState(uiCommandBuilder);
    }

    private void pushHudState(@Nonnull UICommandBuilder uiCommandBuilder) {
        uiCommandBuilder.set("#Level.Text", resolveHudLabel());
        double progress = resolveXpProgress();
        uiCommandBuilder.set("#ProgressBar.Value", progress);
        uiCommandBuilder.set("#ProgressBarEffect.Value", progress);
        update(false, uiCommandBuilder);
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
        if (hud != null) {
            hud.refreshHud();
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
