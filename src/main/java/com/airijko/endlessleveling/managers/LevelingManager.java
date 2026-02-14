package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.Locale;
import java.util.UUID;

public class LevelingManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final ConfigManager configManager;
    private final ArchetypePassiveManager archetypePassiveManager;

    private double baseXp;
    private double multiplier;
    private int levelCap;
    private boolean experienceRulesEnabled;
    private boolean xpLevelRangeEnabled;
    private int xpMaxDifference;
    private double xpBelowRangeMultiplier;
    private double xpAboveRangeMultiplier;
    private XpScalingMode xpScalingMode;
    private double xpScalingBonusAtMax;
    private double xpScalingMinMultiplier;
    private double globalXpMultiplier;
    private boolean playerBasedMode;
    private int playerBasedOffset;

    public LevelingManager(PlayerDataManager playerDataManager, PluginFilesManager filesManager,
            SkillManager skillManager, PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.playerDataManager = playerDataManager;
        this.configManager = new ConfigManager(filesManager.getLevelingFile(), false);
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;

        loadConfigValues();
    }

    /** Reload leveling.yml and refresh cached values. */
    public void reloadConfig() {
        configManager.load();
        loadConfigValues();
    }

    /** Load numeric values from leveling.yml via ConfigManager */
    public void loadConfigValues() {
        baseXp = ((Number) configManager.get("default.base", 50)).doubleValue();
        multiplier = parseMultiplier((String) configManager.get("default.expression",
                "base * ((log(level)+1) * sqrt(level))^1.5"), 1.5);
        int configuredCap = ((Number) configManager.get("player_level_cap", 100)).intValue();
        levelCap = Math.max(1, configuredCap);

        String levelSourceMode = getString("Mob_Leveling.Level_Source.Mode", "FIXED").toUpperCase(Locale.ROOT);
        playerBasedMode = "PLAYER".equals(levelSourceMode);
        playerBasedOffset = getInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0);

        experienceRulesEnabled = getBoolean("Mob_Leveling.Experience.Enabled", true);
        globalXpMultiplier = getDouble("Mob_Leveling.Experience.Global_XP_Multiplier", 1.0);
        xpLevelRangeEnabled = getBoolean("Mob_Leveling.Experience.XP_Level_Range.Enabled", true);
        xpMaxDifference = Math.max(0, getInt("Mob_Leveling.Experience.XP_Level_Range.Max_Difference", 10));
        xpBelowRangeMultiplier = getDouble("Mob_Leveling.Experience.XP_Level_Range.Below_Range.Multiplier", 0.0);
        xpAboveRangeMultiplier = getDouble("Mob_Leveling.Experience.XP_Level_Range.Above_Range.Multiplier", 0.0);
        xpScalingMode = XpScalingMode.fromString(getString("Mob_Leveling.Experience.Scaling.Mode", "NONE"));
        xpScalingBonusAtMax = Math.max(0.0, getDouble("Mob_Leveling.Experience.Scaling.BonusAtMax", 0.0));
        xpScalingMinMultiplier = clampMultiplier(getDouble("Mob_Leveling.Experience.Scaling.MinMultiplier", 0.1));

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

        double adjustedXp = xpAmount;

        // Combine additive XP bonuses: race/class passive XP_BONUS plus Discipline
        // percent.
        double disciplineBonusPercent = skillManager != null
                ? skillManager.getDisciplineXpBonusPercent(
                        player.getPlayerSkillAttributeLevel(
                                com.airijko.endlessleveling.enums.SkillAttributeType.DISCIPLINE))
                : 0.0D;
        double totalBonus = 0.0D;

        if (archetypePassiveManager != null) {
            ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(player);
            double passiveBonus = snapshot.getValue(ArchetypePassiveType.XP_BONUS); // e.g., 1.5 == +150%
            totalBonus += passiveBonus;
        }

        totalBonus += (disciplineBonusPercent / 100.0D);

        if (totalBonus != 0.0D) {
            adjustedXp *= Math.max(0.0D, 1.0D + totalBonus);
        }

        if (adjustedXp <= 0) {
            return;
        }

        if (player.getLevel() >= levelCap) {
            if (player.getXp() != 0) {
                player.setXp(0);
                playerDataManager.save(player);
                refreshHudIfEnabled(player);
            }
            return;
        }

        // Add XP
        player.setXp(player.getXp() + adjustedXp);

        // Notify XP gain
        notifyXpGain(player, adjustedXp);

        // Handle level-ups
        while (player.getLevel() < levelCap && player.getXp() >= getXpForNextLevel(player.getLevel())) {
            levelUp(player);
        }

        if (player.getLevel() >= levelCap && player.getXp() != 0) {
            player.setXp(0);
        }

        playerDataManager.save(player);
        refreshHudIfEnabled(player);
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
        refreshHudIfEnabled(player);
        requestAttributeResync(player);
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

        refreshHudIfEnabled(player);
        requestAttributeResync(player);
    }

    /** Only refresh the HUD when it is enabled for this player. */
    private void refreshHudIfEnabled(PlayerData player) {
        if (player == null || !player.isPlayerHudEnabled()) {
            return;
        }
        PlayerHud.refreshHud(player.getUuid());
    }

    public int getLevelCap() {
        return levelCap;
    }

    public double applyMobKillXpRules(PlayerData player, int mobLevel, double baseXpAmount,
            boolean skipLevelRangeChecks) {
        if (player == null || baseXpAmount <= 0)
            return 0.0;

        double adjustedXp = baseXpAmount;
        if (!experienceRulesEnabled)
            return adjustedXp;

        if (globalXpMultiplier != 1.0d)
            adjustedXp *= globalXpMultiplier;

        // Discipline bonus is now combined additively with passive XP_BONUS inside
        // addXp.

        boolean blockedForBeingTooHigh = false;
        boolean blockedForBeingTooLow = false;
        boolean levelKnown = !skipLevelRangeChecks;
        boolean withinAllowedRange = true;
        Integer relativeDiff = null;
        if (!skipLevelRangeChecks) {
            int mobLvl = Math.max(1, mobLevel);
            int diff = player.getLevel() - mobLvl;
            if (playerBasedMode)
                diff += playerBasedOffset;
            int diffForScaling = diff;

            if (xpLevelRangeEnabled && xpMaxDifference >= 0) {
                if (diff > xpMaxDifference) {
                    adjustedXp *= xpAboveRangeMultiplier;
                    blockedForBeingTooHigh = xpAboveRangeMultiplier <= 0.0;
                    withinAllowedRange = !blockedForBeingTooHigh;
                    diffForScaling = Math.min(diff, xpMaxDifference);
                } else if (diff < -xpMaxDifference) {
                    // If the mob is far above the player, clamp the diff for scaling instead of
                    // zeroing XP so high-level mobs still award XP when over the Max_Difference.
                    diffForScaling = -xpMaxDifference;
                    withinAllowedRange = true;
                }
            }
            relativeDiff = diffForScaling;
        }
        if (adjustedXp <= 0.0) {
            if (blockedForBeingTooHigh)
                notifyXpSuppressed(player, mobLevel, levelKnown, XpSuppressionReason.PLAYER_TOO_HIGH);
            else if (blockedForBeingTooLow)
                notifyXpSuppressed(player, mobLevel, levelKnown, XpSuppressionReason.PLAYER_TOO_LOW);
            return 0.0;
        }

        if (relativeDiff != null && withinAllowedRange) {
            double scalingMultiplier = resolveScalingMultiplier(relativeDiff);
            if (scalingMultiplier != 1.0d)
                adjustedXp *= scalingMultiplier;
        }
        return adjustedXp;
    }

    private void notifyXpSuppressed(PlayerData player, int mobLevel, boolean levelKnown, XpSuppressionReason reason) {
        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null)
            return;

        String mobLabel = levelKnown
                ? String.format("this mob (level %d)", Math.max(1, mobLevel))
                : "this mob";
        String messageText = switch (reason) {
            case PLAYER_TOO_HIGH -> String.format(
                    "No XP awarded: your level (%d) is too high for %s.",
                    player.getLevel(), mobLabel);
            case PLAYER_TOO_LOW -> String.format(
                    "No XP awarded: %s is too far above your level (%d).",
                    mobLabel, player.getLevel());
        };
        playerRef.sendMessage(Message.raw(messageText).color("#ff6666"));
    }

    private boolean getBoolean(String path, boolean defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw instanceof Boolean b)
            return b;
        if (raw instanceof Number n)
            return n.intValue() != 0;
        if (raw instanceof String s)
            return Boolean.parseBoolean(s.trim());
        return defaultValue;
    }

    private double resolveScalingMultiplier(int relativeDiff) {
        if (xpScalingMode == XpScalingMode.NONE || xpMaxDifference <= 0)
            return 1.0;

        return switch (xpScalingMode) {
            case LINEAR -> computeLinearScaling(relativeDiff);
            case NONE -> 1.0; // kept for completeness
        };
    }

    private double computeLinearScaling(int relativeDiff) {
        int maxDiff = Math.max(1, xpMaxDifference);
        double normalizedGap = Math.min(1.0, Math.abs(relativeDiff) / (double) maxDiff);
        if (normalizedGap <= 0.0)
            return 1.0;

        if (relativeDiff >= 0) {
            return lerp(1.0, xpScalingMinMultiplier, normalizedGap);
        }
        double maxBonusMultiplier = Math.max(1.0, 1.0 + xpScalingBonusAtMax);
        return lerp(1.0, maxBonusMultiplier, normalizedGap);
    }

    private double lerp(double start, double end, double ratio) {
        double clampedRatio = Math.max(0.0, Math.min(1.0, ratio));
        return start + ((end - start) * clampedRatio);
    }

    private void requestAttributeResync(PlayerData player) {
        if (player == null) {
            return;
        }
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return;
        }
        PlayerRaceStatSystem retrySystem = plugin.getPlayerRaceStatSystem();
        if (retrySystem != null) {
            retrySystem.scheduleRetry(player.getUuid());
        }
    }

    private double clampMultiplier(double value) {
        if (Double.isNaN(value))
            return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private int getInt(String path, int defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw instanceof Number n)
            return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private double getDouble(String path, double defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        if (raw instanceof Number n)
            return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String getString(String path, String defaultValue) {
        Object raw = configManager.get(path, defaultValue, false);
        return raw != null ? raw.toString() : defaultValue;
    }

    private enum XpSuppressionReason {
        PLAYER_TOO_HIGH,
        PLAYER_TOO_LOW
    }

    private enum XpScalingMode {
        NONE,
        LINEAR;

        static XpScalingMode fromString(String value) {
            if (value == null)
                return NONE;
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "LINEAR" -> LINEAR;
                default -> NONE;
            };
        }
    }
}
