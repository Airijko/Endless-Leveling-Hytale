package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.UUID;

public class LevelingManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final ConfigManager configManager;

    private double baseXp;
    private double multiplier;
    private int levelCap;

    public LevelingManager(PlayerDataManager playerDataManager, PluginFilesManager filesManager,
            SkillManager skillManager) {
        this.playerDataManager = playerDataManager;
        this.configManager = new ConfigManager(filesManager.getLevelingFile());
        this.skillManager = skillManager;

        loadConfigValues();
    }

    /** Load numeric values from leveling.yml via ConfigManager */
    public void loadConfigValues() {
        baseXp = ((Number) configManager.get("default.base", 50)).doubleValue();
        multiplier = parseMultiplier((String) configManager.get("default.expression",
                "base * ((log(level)+1) * sqrt(level))^1.5"), 1.5);
        int configuredCap = ((Number) configManager.get("player_level_cap", 100)).intValue();
        levelCap = Math.max(1, configuredCap);

        LOGGER.atInfo().log("Leveling config loaded: base=%f, multiplier=%f, cap=%d", baseXp, multiplier, levelCap);
    }

    private double parseMultiplier(String expr, double fallback) {
        try {
            int powIndex = expr.indexOf('^');
            if (powIndex >= 0 && powIndex + 1 < expr.length()) {
                return Double.parseDouble(expr.substring(powIndex + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public void addXp(UUID uuid, double xpAmount) {
        if (xpAmount <= 0)
            return;

        PlayerData player = playerDataManager.get(uuid);
        if (player == null)
            return;

        if (player.getLevel() >= levelCap) {
            if (player.getXp() != 0) {
                player.setXp(0);
                playerDataManager.save(player);
                PlayerHud.refreshHud(player.getUuid());
            }
            return;
        }

        // Add XP
        player.setXp(player.getXp() + xpAmount);

        // Notify XP gain
        notifyXpGain(player, xpAmount);

        // Handle level-ups
        while (player.getLevel() < levelCap && player.getXp() >= getXpForNextLevel(player.getLevel())) {
            levelUp(player);
        }

        if (player.getLevel() >= levelCap && player.getXp() != 0) {
            player.setXp(0);
        }

        playerDataManager.save(player);
        PlayerHud.refreshHud(player.getUuid());
    }

    public double getXpForNextLevel(int level) {
        if (level >= levelCap) {
            return Double.POSITIVE_INFINITY;
        }
        return baseXp * Math.pow((Math.log(level) + 1) * Math.sqrt(level), multiplier);
    }

    private void levelUp(PlayerData player) {
        if (player.getLevel() >= levelCap) {
            player.setLevel(levelCap);
            player.setXp(0);
            return;
        }

        double xpForLevel = getXpForNextLevel(player.getLevel());
        player.setXp(player.getXp() - xpForLevel);
        player.setLevel(player.getLevel() + 1);

        if (player.getLevel() >= levelCap) {
            player.setLevel(levelCap);
            player.setXp(0);
        }

        // Delegate skill point addition to SkillManager
        skillManager.addSkillPoints(player);

        LOGGER.atInfo().log("Player %s leveled up to %d! Total skill points: %d",
                player.getPlayerName(), player.getLevel(), skillManager.calculateTotalSkillPoints(player.getLevel()));

        notifyLevelUp(player);
        PlayerHud.refreshHud(player.getUuid());
    }

    private void notifyXpGain(PlayerData player, double xpAmount) {
        if (!player.isXpNotifEnabled()) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null)
            return;

        var packetHandler = playerRef.getPacketHandler();
        int displayedXp = (int) Math.round(xpAmount);
        var primaryMessage = Message.raw("Gained " + displayedXp + " XP!").color("#00FF00");
        var secondaryMessage = Message.raw("Current level: " + player.getLevel()).color("#228B22");
        var icon = new ItemStack("Ingredient_Life_Essence", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primaryMessage, secondaryMessage, icon);
    }

    private void notifyLevelUp(PlayerData player) {
        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null)
            return;

        // Level-Up Title
        Message primaryMessage = Message.raw("Level Up!").color("#FFD700");
        Message secondaryMessage = Message.raw("You are now level " + player.getLevel()).color("#FFFFFF");
        EventTitleUtil.showEventTitleToPlayer(playerRef, primaryMessage, secondaryMessage, true, null, 5, 1, 1);

        // Skill point notification
        var notifPrimary = Message.raw("You gained " + skillManager.getSkillPointsPerLevel() + " skill points!")
                .color("#ffc300");
        var notifSecondary = Message.join(
                Message.raw("Use ").color("#ff9d00"),
                Message.raw("/skills").color("#4fd7f7"),
                Message.raw(" to allocate your points").color("#ff9d00"));
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), notifPrimary, notifSecondary, icon);
    }

    public void setPlayerLevel(PlayerData player, int newLevel) {
        if (player == null)
            return;
        if (newLevel < 1)
            newLevel = 1;
        if (newLevel > levelCap)
            newLevel = levelCap;

        int oldLevel = player.getLevel();
        player.setLevel(newLevel);
        player.setXp(0);

        // Reset skill attributes and recalc skill points
        skillManager.resetSkillAttributes(player);

        playerDataManager.save(player);
        LOGGER.atInfo().log("Player %s level changed from %d to %d",
                player.getPlayerName(), oldLevel, newLevel);

        PlayerHud.refreshHud(player.getUuid());
    }

    public int getLevelCap() {
        return levelCap;
    }
}
