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
    private final PassiveManager passiveManager;
    private final ConfigManager configManager;

    private double baseXp;
    private double multiplier;
    private int levelCap;

    public LevelingManager(PlayerDataManager playerDataManager, PluginFilesManager filesManager,
            SkillManager skillManager, PassiveManager passiveManager) {
        this.playerDataManager = playerDataManager;
        this.configManager = new ConfigManager(filesManager.getLevelingFile(), false);
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;

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

        if (passiveManager != null) {
            var passiveResult = passiveManager.syncPassives(player);
            passiveManager.notifyPassiveChanges(player, passiveResult);
        }

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

        if (passiveManager != null) {
            passiveManager.syncPassives(player);
        }

        playerDataManager.save(player);
        LOGGER.atInfo().log("Player %s level changed from %d to %d",
                player.getPlayerName(), oldLevel, newLevel);

        PlayerHud.refreshHud(player.getUuid());
    }

    public int getLevelCap() {
        return levelCap;
    }

    /**
     * Whether mob leveling is enabled in the leveling.yml under
     * Mob_Leveling.Enabled
     */
    public boolean isMobLevelingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Enabled", Boolean.TRUE, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /**
     * Whether passive mobs are allowed to be leveled
     * (Mob_Leveling.allow_passive_mob_leveling)
     */
    public boolean allowPassiveMobLeveling() {
        Object raw = configManager.get("Mob_Leveling.allow_passive_mob_leveling", Boolean.FALSE, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /**
     * Returns true if the provided mob type (string) is present in the blacklist
     * defined at Mob_Leveling.Blacklist_Mob_Types. Comparison is case-insensitive.
     */
    public boolean isMobTypeBlacklisted(String mobType) {
        if (mobType == null || mobType.isBlank())
            return false;
        Object raw = configManager.get("Mob_Leveling.Blacklist_Mob_Types", null, false);
        if (raw == null)
            return false;

        if (raw instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry == null)
                    continue;
                if (mobType.equalsIgnoreCase(entry.toString())) {
                    return true;
                }
            }
            return false;
        }

        // Single value case
        String single = raw.toString();
        return mobType.equalsIgnoreCase(single);
    }

    /**
     * Returns a health multiplier for a given mob level using the config values:
     * Mob_Leveling.Scaling.Health.Base_Multiplier and Per_Level.
     */
    public double getMobHealthMultiplierForLevel(int level) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Health.Base_Multiplier", 1.0);
        double per = getConfigDouble("Mob_Leveling.Scaling.Health.Per_Level", 0.05);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    /**
     * Returns a damage multiplier for a given mob level using the config values:
     * Mob_Leveling.Scaling.Damage.Base_Multiplier and Per_Level.
     */
    public double getMobDamageMultiplierForLevel(int level) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Damage.Base_Multiplier", 1.0);
        double per = getConfigDouble("Mob_Leveling.Scaling.Damage.Per_Level", 0.03);
        int effectiveLevel = Math.max(1, level);
        return base * (1.0 + per * (effectiveLevel - 1));
    }

    /**
     * Whether damage-scaling should be applied to mobs via MobDamageScalingSystem
     */
    public boolean isMobDamageScalingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Scaling.Damage.Enabled", Boolean.FALSE, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    /** Whether health-scaling should be applied to mobs via MobLevelingSystem */
    public boolean isMobHealthScalingEnabled() {
        Object raw = configManager.get("Mob_Leveling.Scaling.Health.Enabled", Boolean.FALSE, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return false;
    }

    private double getConfigDouble(String path, double defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw == null)
            return defaultValue;
        try {
            if (raw instanceof Number)
                return ((Number) raw).doubleValue();
            return Double.parseDouble(raw.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
