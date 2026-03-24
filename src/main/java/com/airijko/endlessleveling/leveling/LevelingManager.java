package com.airijko.endlessleveling.leveling;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.util.ChatMessageTemplate;
import com.airijko.endlessleveling.util.FixedValue;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bson.BsonString;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.managers.EventHookManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.managers.ConfigManager;

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
    private double xpAdditiveMinimum;
    private XpScalingMode xpScalingMode;
    private double xpScalingBonusAtMax;
    private double xpScalingMinMultiplier;
    private double globalXpMultiplier;
    private boolean playerBasedMode;
    private int playerBasedOffset;
    private boolean partySharedXpEnabled;
    private double partySharedXpRange;
    private double partyMemberSharedXpMultiplier;
    private boolean prestigeEnabled;
    private Integer prestigeCap;
    private int prestigeLevelCapIncrease;
    private double prestigeBaseXpIncrease;
    private LogMode logMode;

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
        logMode = LogMode.fromString(getString("default.log_mode", "LOG10"));
        int configuredCap = getInt("player_level_cap", 100);
        levelCap = Math.max(1, configuredCap);
        prestigeEnabled = getBoolean("prestige.enabled", true);
        prestigeCap = parsePrestigeCap(configManager.get("prestige.prestige_level_cap", "ENDLESS", false));
        prestigeLevelCapIncrease = Math.max(0, getInt("prestige.level_cap_increase_per_prestige", 10));
        prestigeBaseXpIncrease = Math.max(0.0D, getDouble("prestige.base_xp_increase_per_prestige", 10.0D));

        String levelSourceMode = getString("Mob_Leveling.Level_Source.Mode", "FIXED").toUpperCase(Locale.ROOT);
        playerBasedMode = "PLAYER".equals(levelSourceMode);
        playerBasedOffset = getInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0);

        partySharedXpEnabled = getBoolean("party_xp_share.enabled", true);
        partySharedXpRange = Math.max(0.0D, getDouble("party_xp_share.range", 25.0D));
        double configuredMemberShare = getDouble("party_xp_share.member_share_percent", 20.0D);
        if (configuredMemberShare >= 0.0D && configuredMemberShare <= 1.0D) {
            // Allow either 20 (percent) or 0.20 (fraction) formats.
            configuredMemberShare *= 100.0D;
        }
        partyMemberSharedXpMultiplier = Math.max(0.0D, configuredMemberShare / 100.0D);

        experienceRulesEnabled = getBoolean("Mob_Leveling.Experience.Enabled", true);
        globalXpMultiplier = getDouble("Mob_Leveling.Experience.Global_XP_Multiplier", 1.0);
        xpLevelRangeEnabled = getBoolean("Mob_Leveling.Experience.XP_Level_Range.Enabled", true);
        xpMaxDifference = Math.max(0, getInt("Mob_Leveling.Experience.XP_Level_Range.Max_Difference", 10));
        xpBelowRangeMultiplier = getDouble("Mob_Leveling.Experience.XP_Level_Range.Below_Range.Multiplier", 0.0);
        xpAboveRangeMultiplier = getDouble("Mob_Leveling.Experience.XP_Level_Range.Above_Range.Multiplier", 0.0);
        xpAdditiveMinimum = Math.max(0.0D, getDouble("Mob_Leveling.Experience.Additive_Minimum_XP", 50.0D));
        xpScalingMode = XpScalingMode.fromString(getString("Mob_Leveling.Experience.Scaling.Mode", "NONE"));
        xpScalingBonusAtMax = Math.max(0.0, getDouble("Mob_Leveling.Experience.Scaling.BonusAtMax", 0.0));
        xpScalingMinMultiplier = clampMultiplier(getDouble("Mob_Leveling.Experience.Scaling.MinMultiplier", 0.1));

        LOGGER.atInfo().log(
            "Leveling config loaded: base=%f, multiplier=%f, logMode=%s, cap=%d, prestigeEnabled=%s, prestigeCap=%s, cap+%d/base+%.2f",
                baseXp,
                multiplier,
            logMode,
                levelCap,
                prestigeEnabled,
                prestigeCap == null ? "ENDLESS" : prestigeCap,
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
        double passiveXpBonus = 0.0D;

        if (archetypePassiveManager != null) {
            ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(player);
            passiveXpBonus = snapshot.getValue(ArchetypePassiveType.XP_BONUS); // e.g., 1.5 == +150%
            totalBonus += passiveXpBonus;
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
            LOGGER.atInfo().log(
                    "[XP_APPLY] player=%s level=%d cap=%d incoming=%.3f passiveBonus=%.4f disciplinePct=%.3f luckPct=%.3f final=0.000 blocked=level_cap",
                    uuid,
                    player.getLevel(),
                    effectiveCap,
                    xpAmount,
                    passiveXpBonus,
                    disciplineBonusPercent,
                    luckXpBonusPercent);
            if (player.getXp() != 0) {
                player.setXp(0);
                playerDataManager.save(player);
                refreshHud(player);
            }
            return;
        }

        // Add XP
        player.setXp(player.getXp() + adjustedXp);

        LOGGER.atInfo().log(
            "[XP_APPLY] player=%s level=%d cap=%d incoming=%.3f passiveBonus=%.4f disciplinePct=%.3f luckPct=%.3f final=%.3f blocked=none",
            uuid,
            player.getLevel(),
            effectiveCap,
            xpAmount,
            passiveXpBonus,
            disciplineBonusPercent,
            luckXpBonusPercent,
            adjustedXp);

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
        return baseXp * computeLevelCurve(level);
    }

    public double getXpForNextLevel(PlayerData player, int level) {
        int effectiveCap = getLevelCap(player);
        if (level >= effectiveCap) {
            return Double.POSITIVE_INFINITY;
        }
        int prestigeLevel = player != null ? Math.max(0, player.getPrestigeLevel()) : 0;
        double effectiveBaseXp = getBaseXpForPrestige(prestigeLevel);
        return effectiveBaseXp * computeLevelCurve(level);
    }

    private double computeLevelCurve(int level) {
        int safeLevel = Math.max(1, level);
        double logValue = switch (logMode) {
            case LN -> Math.log(safeLevel);
            case LOG10 -> Math.log10(safeLevel);
        };
        return Math.pow((logValue + 1.0D) * Math.sqrt(safeLevel), multiplier);
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
        if (!player.isAugmentNotifEnabled()) {
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

        PlayerChatNotifier.sendAugmentAvailability(playerRef, tiers);
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
            Message.raw(FixedValue.ROOT_COMMAND.value()).color("#4fd7f7"),
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
        skillManager.clearHardResetAugmentRuntimeState(player);

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

    public Integer getPrestigeCap() {
        return prestigeCap;
    }

    public boolean hasPrestigeCap() {
        return prestigeCap != null;
    }

    public PrestigeResult tryGainPrestige(PlayerData player) {
        if (player == null) {
            return PrestigeResult.INVALID_PLAYER;
        }
        if (!prestigeEnabled) {
            return PrestigeResult.DISABLED;
        }

        int oldPrestigeLevel = Math.max(0, player.getPrestigeLevel());
        if (prestigeCap != null && oldPrestigeLevel >= prestigeCap) {
            return PrestigeResult.AT_MAX_PRESTIGE;
        }

        int currentCap = getLevelCap(player);
        if (player.getLevel() < currentCap) {
            return PrestigeResult.NOT_AT_CAP;
        }

        int nextPrestigeLevel = oldPrestigeLevel + 1;
        player.setPrestigeLevel(nextPrestigeLevel);
        player.setLevel(1);
        player.setXp(0.0D);

        skillManager.resetSkillAttributes(player);
        skillManager.clearHardResetAugmentRuntimeState(player);
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

    public boolean isPartySharedXpEnabled() {
        return partySharedXpEnabled;
    }

    public double getPartyXpShareRange() {
        return partySharedXpRange;
    }

    public double getPartyMemberSharedXpMultiplier() {
        return partyMemberSharedXpMultiplier;
    }

    public double applyMobKillXpRules(PlayerData player, int mobLevel, double baseXpAmount,
            boolean skipLevelRangeChecks) {
        return applyMobKillXpRules(player, mobLevel, baseXpAmount, skipLevelRangeChecks, null, null);
    }

    public double applyMobKillXpRules(PlayerData player,
            int mobLevel,
            double baseXpAmount,
            boolean skipLevelRangeChecks,
            Store<EntityStore> store,
            MobLevelingManager mobLevelingManager) {
        if (player == null || baseXpAmount <= 0)
            return 0.0;

        MobXpRuleSet rules = resolveMobXpRuleSet(store, mobLevelingManager);

        double adjustedXp = baseXpAmount;
        if (!rules.experienceRulesEnabled())
            return adjustedXp;

        if (rules.globalXpMultiplier() != 1.0d)
            adjustedXp *= rules.globalXpMultiplier();

        // Discipline bonus is now combined additively with passive XP_BONUS inside
        // addXp.

        boolean blockedForBeingTooHigh = false;
        boolean blockedForBeingTooLow = false;
        boolean levelKnown = !skipLevelRangeChecks;
        boolean eligibleForScaling = true;
        Integer relativeDiff = null;
        if (!skipLevelRangeChecks) {
            int mobLvl = Math.max(1, mobLevel);
            int diff = player.getLevel() - mobLvl;
            if (rules.playerBasedMode())
                diff += rules.playerBasedOffset();
            relativeDiff = diff;

            if (rules.xpLevelRangeEnabled() && rules.xpMaxDifference() >= 0) {
                if (diff > rules.xpMaxDifference()) {
                    adjustedXp *= rules.xpAboveRangeMultiplier();
                    blockedForBeingTooHigh = rules.xpAboveRangeMultiplier() <= 0.0;
                    eligibleForScaling = false;
                } else if (diff < -rules.xpMaxDifference()) {
                    adjustedXp *= rules.xpBelowRangeMultiplier();
                    blockedForBeingTooLow = rules.xpBelowRangeMultiplier() <= 0.0;
                    eligibleForScaling = false;
                }
            }
        }

        // Always add the configured flat amount before scaling so max-difference
        // multipliers can affect it as well.
        if (rules.xpAdditiveMinimum() > 0.0D) {
            adjustedXp += rules.xpAdditiveMinimum();
        }

        if (adjustedXp <= 0.0) {
            if (blockedForBeingTooHigh)
                notifyXpSuppressed(player, mobLevel, levelKnown, XpSuppressionReason.PLAYER_TOO_HIGH);
            else if (blockedForBeingTooLow)
                notifyXpSuppressed(player, mobLevel, levelKnown, XpSuppressionReason.PLAYER_TOO_LOW);
            return 0.0;
        }

        if (relativeDiff != null && eligibleForScaling) {
            double scalingMultiplier = resolveScalingMultiplier(relativeDiff, rules);
            if (scalingMultiplier != 1.0d)
                adjustedXp *= scalingMultiplier;
        }
        return adjustedXp;
    }

    private MobXpRuleSet resolveMobXpRuleSet(Store<EntityStore> store, MobLevelingManager source) {
        MobLevelingManager effectiveSource = source;
        if (effectiveSource == null && store != null) {
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin != null) {
                effectiveSource = plugin.getMobLevelingManager();
            }
        }

        if (effectiveSource == null || store == null) {
            return new MobXpRuleSet(
                    experienceRulesEnabled,
                    globalXpMultiplier,
                    xpLevelRangeEnabled,
                    xpMaxDifference,
                    xpBelowRangeMultiplier,
                    xpAboveRangeMultiplier,
                    xpAdditiveMinimum,
                    xpScalingMode,
                    xpScalingBonusAtMax,
                    xpScalingMinMultiplier,
                    playerBasedMode,
                    playerBasedOffset);
        }

        return new MobXpRuleSet(
                effectiveSource.isMobExperienceRulesEnabled(store),
                effectiveSource.getMobGlobalXpMultiplier(store),
                effectiveSource.isMobXpLevelRangeEnabled(store),
                Math.max(0, effectiveSource.getMobXpMaxDifference(store)),
                effectiveSource.getMobXpBelowRangeMultiplier(store),
                effectiveSource.getMobXpAboveRangeMultiplier(store),
                Math.max(0.0D, effectiveSource.getMobXpAdditiveMinimum(store)),
                XpScalingMode.fromString(effectiveSource.getMobXpScalingMode(store)),
                Math.max(0.0D, effectiveSource.getMobXpScalingBonusAtMax(store)),
                clampMultiplier(effectiveSource.getMobXpScalingMinMultiplier(store)),
                effectiveSource.isLevelSourcePlayerMode(store),
                effectiveSource.getPlayerBasedOffset(store));
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
        PlayerChatNotifier.send(playerRef, Message.raw(messageText).color("#ff6666"));
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

    private double resolveScalingMultiplier(int relativeDiff, MobXpRuleSet rules) {
        if (rules.xpScalingMode() == XpScalingMode.NONE || rules.xpMaxDifference() <= 0)
            return 1.0;

        return switch (rules.xpScalingMode()) {
            case LINEAR -> computeLinearScaling(relativeDiff, rules.xpMaxDifference(), rules.xpScalingMinMultiplier(),
                    rules.xpScalingBonusAtMax());
            case NONE -> 1.0; // kept for completeness
        };
    }

    private double computeLinearScaling(int relativeDiff,
            int maxDifference,
            double minMultiplier,
            double bonusAtMax) {
        int maxDiff = Math.max(1, maxDifference);
        double normalizedGap = Math.min(1.0, Math.abs(relativeDiff) / (double) maxDiff);
        if (normalizedGap <= 0.0)
            return 1.0;

        if (relativeDiff >= 0) {
            return lerp(1.0, minMultiplier, normalizedGap);
        }
        double maxBonusMultiplier = Math.max(1.0, bonusAtMax);
        return lerp(1.0, maxBonusMultiplier, normalizedGap);
    }

    private record MobXpRuleSet(boolean experienceRulesEnabled,
            double globalXpMultiplier,
            boolean xpLevelRangeEnabled,
            int xpMaxDifference,
            double xpBelowRangeMultiplier,
            double xpAboveRangeMultiplier,
            double xpAdditiveMinimum,
            XpScalingMode xpScalingMode,
            double xpScalingBonusAtMax,
            double xpScalingMinMultiplier,
            boolean playerBasedMode,
            int playerBasedOffset) {
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

        PlayerRef playerRef = player.getUuid() != null ? Universe.get().getPlayer(player.getUuid()) : null;
        if (playerRef != null && skillManager != null) {
            UUID worldUuid = playerRef.getWorldUuid();
            World world = worldUuid != null ? Universe.get().getWorld(worldUuid) : null;
            if (world != null) {
                try {
                    world.execute(() -> {
                        Ref<EntityStore> entityRef = playerRef.getReference();
                        if (entityRef == null) {
                            scheduleAttributeRetry(plugin, player);
                            return;
                        }

                        Store<EntityStore> entityStore = entityRef.getStore();
                        boolean applied = skillManager.applyAllSkillModifiers(entityRef, entityStore, player);
                        if (!applied) {
                            scheduleAttributeRetry(plugin, player);
                        }
                    });
                    return;
                } catch (Exception ex) {
                    LOGGER.atFine().withCause(ex).log(
                            "Immediate attribute resync failed for %s; scheduling retry",
                            player.getPlayerName());
                }
            }
        }

        scheduleAttributeRetry(plugin, player);
    }

    private void scheduleAttributeRetry(EndlessLeveling plugin, PlayerData player) {
        if (plugin == null || player == null || player.getUuid() == null) {
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

    private Integer parsePrestigeCap(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (rawValue instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty() || normalized.equalsIgnoreCase("ENDLESS")) {
                return null;
            }
            try {
                return Math.max(0, Integer.parseInt(normalized));
            } catch (NumberFormatException ignored) {
                LOGGER.atWarning().log(
                        "Invalid prestige.prestige_level_cap value '%s'; defaulting to ENDLESS.",
                        normalized);
                return null;
            }
        }
        LOGGER.atWarning().log(
                "Unsupported prestige.prestige_level_cap value type %s; defaulting to ENDLESS.",
                rawValue.getClass().getSimpleName());
        return null;
    }

    private enum XpSuppressionReason {
        PLAYER_TOO_HIGH,
        PLAYER_TOO_LOW
    }

    private enum LogMode {
        LOG10,
        LN;

        static LogMode fromString(String value) {
            if (value == null) {
                return LOG10;
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "LN", "NATURAL", "NATURAL_LOG", "LOG_E" -> LN;
                case "LOG10", "LOG_10", "BASE10", "10" -> LOG10;
                default -> LOG10;
            };
        }
    }

    public enum PrestigeResult {
        SUCCESS,
        NOT_AT_CAP,
        AT_MAX_PRESTIGE,
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
