package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bson.BsonString;

public class LevelingManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final ConfigManager configManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final PassiveManager passiveManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final EventHookManager eventHookManager;

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
    private boolean prestigeEnabled;
    private int prestigeLevelCapIncrease;
    private double prestigeBaseXpIncrease;

    public LevelingManager(PlayerDataManager playerDataManager, PluginFilesManager filesManager,
            SkillManager skillManager, ArchetypePassiveManager archetypePassiveManager,
            PassiveManager passiveManager, AugmentUnlockManager augmentUnlockManager,
            EventHookManager eventHookManager) {
        this.playerDataManager = playerDataManager;
        this.configManager = new ConfigManager(filesManager, filesManager.getLevelingFile());
        this.skillManager = skillManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.passiveManager = passiveManager;
        this.augmentUnlockManager = augmentUnlockManager;
        this.eventHookManager = eventHookManager;

        loadConfigValues();
    }

    /** Reload leveling.yml and refresh cached values. */
    public void reloadConfig() {
        configManager.load();
        loadConfigValues();
    }

    /** Load numeric values from leveling.yml via ConfigManager */
    public void loadConfigValues() {
        baseXp = getDouble("default.base", 50.0D);
        multiplier = parseMultiplier((String) configManager.get("default.expression",
                "base * ((log(level)+1) * sqrt(level))^1.5"), 1.5);
        int configuredCap = getInt("player_level_cap", 100);
        levelCap = Math.max(1, configuredCap);
        prestigeEnabled = getBoolean("prestige.enabled", true);
        prestigeLevelCapIncrease = Math.max(0, getInt("prestige.level_cap_increase_per_prestige", 10));
        prestigeBaseXpIncrease = Math.max(0.0D, getDouble("prestige.base_xp_increase_per_prestige", 10.0D));

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

        LOGGER.atInfo().log(
                "Leveling config loaded: base=%f, multiplier=%f, cap=%d, prestigeEnabled=%s, cap+%d/base+%.2f",
                baseXp,
                multiplier,
                levelCap,
                prestigeEnabled,
                prestigeLevelCapIncrease,
                prestigeBaseXpIncrease);
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
                ? skillManager.getDisciplineXpBonusPercent(player)
                : 0.0D;
        double totalBonus = 0.0D;

        if (archetypePassiveManager != null) {
            ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(player);
            double passiveBonus = snapshot.getValue(ArchetypePassiveType.XP_BONUS); // e.g., 1.5 == +150%
            totalBonus += passiveBonus;
        }

        totalBonus += (disciplineBonusPercent / 100.0D);

        double totalLuck = passiveManager != null ? passiveManager.getLuckValue(player) : 0.0D;
        double luckXpBonusPercent = getLuckXpBonusPercent(totalLuck);
        totalBonus += (luckXpBonusPercent / 100.0D);

        if (totalBonus != 0.0D) {
            adjustedXp *= Math.max(0.0D, 1.0D + totalBonus);
        }

        if (adjustedXp <= 0) {
            return;
        }

        int effectiveCap = getLevelCap(player);

        if (player.getLevel() >= effectiveCap) {
            if (player.getXp() != 0) {
                player.setXp(0);
                playerDataManager.save(player);
                refreshHud(player);
            }
            return;
        }

        // Add XP
        player.setXp(player.getXp() + adjustedXp);

        // Notify XP gain
        notifyXpGain(player, adjustedXp);

        // Handle level-ups
        boolean leveledUp = false;
        while (player.getLevel() < effectiveCap
                && player.getXp() >= getXpForNextLevel(player, player.getLevel())) {
            levelUp(player);
            leveledUp = true;
        }

        if (leveledUp && augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(player);
            notifyAvailableAugments(player);
        }

        if (player.getLevel() >= effectiveCap && player.getXp() != 0) {
            player.setXp(0);
        }

        playerDataManager.save(player);
        refreshHud(player);
    }

    public double getLuckXpBonusPercent(double totalLuckPercent) {
        double clampedLuck = Math.max(0.0D, totalLuckPercent);
        if (clampedLuck <= 100.0D) {
            return clampedLuck;
        }
        double extraLuck = clampedLuck - 100.0D;
        return 100.0D + (extraLuck * 2.0D);
    }

    public double getXpForNextLevel(int level) {
        if (level >= getLevelCap()) {
            return Double.POSITIVE_INFINITY;
        }
        return baseXp * Math.pow((Math.log(level) + 1) * Math.sqrt(level), multiplier);
    }

    public double getXpForNextLevel(PlayerData player, int level) {
        int effectiveCap = getLevelCap(player);
        if (level >= effectiveCap) {
            return Double.POSITIVE_INFINITY;
        }
        int prestigeLevel = player != null ? Math.max(0, player.getPrestigeLevel()) : 0;
        double effectiveBaseXp = getBaseXpForPrestige(prestigeLevel);
        return effectiveBaseXp * Math.pow((Math.log(level) + 1) * Math.sqrt(level), multiplier);
    }

    private void levelUp(PlayerData player) {
        int effectiveCap = getLevelCap(player);
        if (player.getLevel() >= effectiveCap) {
            player.setLevel(effectiveCap);
            player.setXp(0);
            return;
        }

        int oldLevel = player.getLevel();
        double xpForLevel = getXpForNextLevel(player, player.getLevel());
        player.setXp(player.getXp() - xpForLevel);
        player.setLevel(oldLevel + 1);

        if (player.getLevel() >= effectiveCap) {
            player.setLevel(effectiveCap);
            player.setXp(0);
        }

        int newLevel = player.getLevel();

        // Delegate skill point addition to SkillManager
        skillManager.addSkillPoints(player);

        LOGGER.atInfo().log("Player %s leveled up to %d! Total skill points: %d",
                player.getPlayerName(), player.getLevel(), skillManager.calculateTotalSkillPoints(player.getLevel()));

        if (passiveManager != null) {
            PassiveManager.PassiveSyncResult passiveResult = passiveManager.syncPassives(player);
            passiveManager.notifyPassiveChanges(player, passiveResult);
        }

        notifyLevelUp(player);
        refreshHud(player);
        pushPartyProHudText(player);
        requestAttributeResync(player);

        if (eventHookManager != null && newLevel > oldLevel) {
            eventHookManager.onPlayerLevelUp(player, oldLevel, newLevel);
        }
    }

    private void notifyAvailableAugments(PlayerData player) {
        if (player == null || augmentUnlockManager == null) {
            return;
        }
        List<PassiveTier> tiers = augmentUnlockManager.getPendingOfferTiers(player);
        if (tiers.isEmpty()) {
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
        if (playerRef == null) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(Lang.tr(playerRef.getUuid(), "notify.augments.available.header",
                "[EndlessLeveling] You have augments available to choose from:")).append("\n");
        for (PassiveTier tier : tiers) {
            builder.append("- ").append(tier.name()).append("\n");
        }
        builder.append(Lang.tr(playerRef.getUuid(), "notify.augments.available.footer",
                "Use /el augments choose."));
        playerRef.sendMessage(Message.raw(builder.toString()).color("#4fd7f7"));
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
        ItemStack iconStack = new ItemStack("Ingredient_Life_Essence", 1)
                .withMetadata("el:xp_notification_nonce", new BsonString(UUID.randomUUID().toString()));
        var icon = iconStack.toPacket();
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
                Message.raw("/el").color("#4fd7f7"),
                Message.raw(" to allocate your points").color("#ff9d00"));
        var icon = new ItemStack("Ingredient_Ice_Essence", 1).toPacket();
        NotificationUtil.sendNotification(playerRef.getPacketHandler(), notifPrimary, notifSecondary, icon);
    }

    public void setPlayerLevel(PlayerData player, int newLevel) {
        if (player == null)
            return;
        if (newLevel < 1)
            newLevel = 1;
        int effectiveCap = getLevelCap(player);
        if (newLevel > effectiveCap)
            newLevel = effectiveCap;

        int oldLevel = player.getLevel();
        player.setLevel(newLevel);
        player.setXp(0);

        // Reset skill attributes and recalc skill points
        skillManager.resetSkillAttributes(player);

        playerDataManager.save(player);
        LOGGER.atInfo().log("Player %s level changed from %d to %d",
                player.getPlayerName(), oldLevel, newLevel);

        refreshHud(player);
        pushPartyProHudText(player);
        requestAttributeResync(player);
    }

    /** Refresh the HUD after level changes. */
    private void refreshHud(PlayerData player) {
        if (player == null) {
            return;
        }
        PlayerHud.refreshHud(player.getUuid());
    }

    private void pushPartyProHudText(PlayerData player) {
        try {
            PartyManager partyManager = EndlessLeveling.getInstance().getPartyManager();
            if (partyManager != null) {
                partyManager.updatePartyHudCustomText(player);
            }
        } catch (Throwable ignored) {
            // PartyPro optional; ignore failures
        }
    }

    public int getLevelCap() {
        return levelCap;
    }

    public int getLevelCap(PlayerData player) {
        int prestigeLevel = player != null ? Math.max(0, player.getPrestigeLevel()) : 0;
        return getLevelCapForPrestige(prestigeLevel);
    }

    public int getLevelCapForPrestige(int prestigeLevel) {
        int safePrestige = Math.max(0, prestigeLevel);
        return Math.max(1, levelCap + (prestigeLevelCapIncrease * safePrestige));
    }

    public double getBaseXpForPrestige(int prestigeLevel) {
        int safePrestige = Math.max(0, prestigeLevel);
        return Math.max(1.0D, baseXp + (prestigeBaseXpIncrease * safePrestige));
    }

    public boolean isPrestigeEnabled() {
        return prestigeEnabled;
    }

    public PrestigeResult tryGainPrestige(PlayerData player) {
        if (player == null) {
            return PrestigeResult.INVALID_PLAYER;
        }
        if (!prestigeEnabled) {
            return PrestigeResult.DISABLED;
        }

        int currentCap = getLevelCap(player);
        if (player.getLevel() < currentCap) {
            return PrestigeResult.NOT_AT_CAP;
        }

        int oldPrestigeLevel = Math.max(0, player.getPrestigeLevel());
        int nextPrestigeLevel = oldPrestigeLevel + 1;
        player.setPrestigeLevel(nextPrestigeLevel);
        player.setLevel(1);
        player.setXp(0.0D);

        skillManager.resetSkillAttributes(player);
        if (passiveManager != null) {
            passiveManager.syncPassives(player);
        }
        if (augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(player);
        }

        playerDataManager.save(player);

        if (eventHookManager != null) {
            eventHookManager.onPrestigeLevelUp(player, oldPrestigeLevel, nextPrestigeLevel);
        }

        refreshHud(player);
        pushPartyProHudText(player);
        requestAttributeResync(player);

        LOGGER.atInfo().log("Player %s gained prestige %d (new cap=%d, baseXP=%.2f)",
                player.getPlayerName(),
                nextPrestigeLevel,
                getLevelCap(player),
                getBaseXpForPrestige(nextPrestigeLevel));

        return PrestigeResult.SUCCESS;
    }

    /**
     * Check if two player levels are within the configured XP share/suppression
     * range. When disabled, all levels are allowed.
     */
    public boolean isWithinXpShareRange(int recipientLevel, int sourceLevel) {
        if (!xpLevelRangeEnabled) {
            return true;
        }
        int diff = Math.abs(recipientLevel - sourceLevel);
        return diff <= xpMaxDifference;
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

        UUID uuid = playerRef.getUuid();
        String mobLabel = levelKnown
                ? Lang.tr(uuid, "notify.xp_suppressed.mob_label_level",
                        "this mob (level {0})", Math.max(1, mobLevel))
                : Lang.tr(uuid, "notify.xp_suppressed.mob_label", "this mob");
        String messageText = switch (reason) {
            case PLAYER_TOO_HIGH -> Lang.tr(uuid,
                    "notify.xp_suppressed.player_too_high",
                    "No XP awarded: your level ({0}) is too high for {1}.",
                    player.getLevel(), mobLabel);
            case PLAYER_TOO_LOW -> Lang.tr(uuid,
                    "notify.xp_suppressed.player_too_low",
                    "No XP awarded: {0} is too far above your level ({1}).",
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
        double maxBonusMultiplier = Math.max(1.0, xpScalingBonusAtMax);
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

    public enum PrestigeResult {
        SUCCESS,
        NOT_AT_CAP,
        DISABLED,
        INVALID_PLAYER
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
