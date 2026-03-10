package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.augments.types.ExecutionerAugment;
import com.airijko.endlessleveling.augments.types.FirstStrikeAugment;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Handles augment unlock rolls at configured level milestones. Rolls are
 * persisted per-player and only rerolled when explicitly refreshed.
 */
public class AugmentUnlockManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int DEFAULT_OFFER_COUNT = 3;

    private final ConfigManager configManager;
    private final ConfigManager levelingConfigManager;
    private final AugmentManager augmentManager;
    private final PlayerDataManager playerDataManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private volatile List<UnlockRule> unlockRules;
    private volatile List<PrestigeUnlockRule> prestigeUnlockRules;
    private volatile List<PrestigeRerollRule> prestigeRerollRules;

    public AugmentUnlockManager(@Nonnull ConfigManager configManager,
            @Nonnull ConfigManager levelingConfigManager,
            @Nonnull AugmentManager augmentManager,
            @Nonnull PlayerDataManager playerDataManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.levelingConfigManager = Objects.requireNonNull(levelingConfigManager, "levelingConfigManager");
        this.augmentManager = Objects.requireNonNull(augmentManager, "augmentManager");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "playerDataManager");
        this.archetypePassiveManager = archetypePassiveManager;
        this.unlockRules = List.of();
        this.prestigeUnlockRules = List.of();
        this.prestigeRerollRules = List.of();
        reload();
    }

    /** Reload unlock milestone rules from config.yml. */
    public synchronized void reload() {
        levelingConfigManager.load();
        List<UnlockRule> parsed = parseRules();
        List<PrestigeUnlockRule> parsedPrestige = parsePrestigeRules();
        List<PrestigeRerollRule> parsedRerolls = parsePrestigeRerollRules();
        this.unlockRules = parsed;
        this.prestigeUnlockRules = parsedPrestige;
        this.prestigeRerollRules = parsedRerolls;
        if (parsed.isEmpty()) {
            LOGGER.atWarning().log("No augment unlock rules parsed. Check config augments.unlocks for tiers/levels.");
        } else {
            LOGGER.atInfo().log("Loaded %d augment unlock rules", parsed.size());
        }

        if (parsedPrestige.isEmpty()) {
            LOGGER.atWarning()
                    .log("No prestige augment unlock rules parsed. Check leveling.yml prestige.augment_unlock_tiers.");
        } else {
            LOGGER.atInfo().log("Loaded %d prestige augment unlock rules", parsedPrestige.size());
        }

        if (parsedRerolls.isEmpty()) {
            LOGGER.atInfo().log("No prestige reroll rules parsed. Reroll unlocks are disabled.");
        } else {
            LOGGER.atInfo().log("Loaded %d prestige reroll rules", parsedRerolls.size());
        }
    }

    /** Ensures any eligible unlocks are rolled and persisted for the player. */
    public void ensureUnlocks(@Nonnull PlayerData playerData) {
        LOGGER.atFiner().log("ensureUnlocks: player=%s level=%d rules=%d", playerData.getPlayerName(),
                playerData.getLevel(), unlockRules.size());

        int playerLevel = playerData.getLevel();
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerData, playerLevel);
        Set<String> excludedAugmentIds = collectOwnedAugmentIds(playerData);
        excludedAugmentIds.addAll(collectArchetypeBlockedAugmentIds(playerData));

        boolean updated = false;
        for (Map.Entry<PassiveTier, Integer> entry : eligibleByTier.entrySet()) {
            PassiveTier tier = entry.getKey();
            int eligibleMilestones = entry.getValue();
            String tierKey = tier.name();

            int selectedCount = countSelectedForTier(playerData, tierKey);
            int pendingCount = countPendingUnlockBundles(playerData.getAugmentOffersForTier(tierKey));
            int grantedCount = selectedCount + pendingCount;
            int missing = Math.max(0, eligibleMilestones - grantedCount);

            if (missing <= 0) {
                LOGGER.atFiner().log("Skip %s: eligible=%d granted=%d selected=%d pending=%d", tierKey,
                        eligibleMilestones, grantedCount, selectedCount, pendingCount);
                continue;
            }

            List<String> offers = new ArrayList<>(playerData.getAugmentOffersForTier(tierKey));
            for (int i = 0; i < missing; i++) {
                List<String> rolled = rollOffers(tier, excludedAugmentIds);
                if (rolled.isEmpty()) {
                    LOGGER.atWarning().log("Failed to roll augments for tier %s (pool empty?) for %s (level %d)",
                            tierKey, playerData.getPlayerName(), playerLevel);
                    continue;
                }
                offers.addAll(rolled);
                for (String augmentId : rolled) {
                    String normalizedId = normalizeAugmentId(augmentId);
                    if (normalizedId != null) {
                        excludedAugmentIds.add(normalizedId);
                    }
                }
                updated = true;
                LOGGER.atFine().log("Rolled %d %s augments for %s (level %d) [grant %d/%d]",
                        rolled.size(), tierKey, playerData.getPlayerName(), playerLevel, i + 1, missing);
            }
            playerData.setAugmentOffersForTier(tierKey, offers);
        }
        if (updated) {
            playerDataManager.save(playerData);
        } else {
            LOGGER.atFiner().log("No augment rolls persisted for %s (level %d)", playerData.getPlayerName(),
                    playerData.getLevel());
        }
    }

    /**
     * Rerolls pending offers for eligible tiers while preserving selected augments.
     */
    public void refreshUnlocks(@Nonnull PlayerData playerData) {
        playerData.clearAugmentOffers();
        ensureUnlocks(playerData);
    }

    /**
     * Returns how many rerolls are still available for the given tier.
     */
    public int getRemainingRerolls(@Nonnull PlayerData playerData, @Nonnull PassiveTier tier) {
        if (playerData == null || tier == null) {
            return 0;
        }

        int playerLevel = Math.max(1, playerData.getLevel());
        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        int eligible = getEligibleRerollsForTier(playerData, tier, prestigeLevel, playerLevel);
        int used = Math.max(0, playerData.getAugmentRerollsUsedForTier(tier.name()));
        return Math.max(0, eligible - used);
    }

    /**
     * Consumes one reroll for a tier and rerolls pending offers in that tier.
     *
     * @return true when a reroll was consumed and offers were updated.
     */
    public boolean tryConsumeReroll(@Nonnull PlayerData playerData, @Nonnull PassiveTier tier) {
        if (playerData == null || tier == null) {
            return false;
        }

        if (getRemainingRerolls(playerData, tier) <= 0) {
            return false;
        }

        String tierKey = tier.name();
        List<String> existingOffers = new ArrayList<>(playerData.getAugmentOffersForTier(tierKey));
        int bundleCount = countPendingUnlockBundles(existingOffers);
        if (bundleCount <= 0) {
            return false;
        }

        Set<String> excludedAugmentIds = collectOwnedAugmentIds(playerData);
        List<String> rerolledOffers = new ArrayList<>();
        for (int i = 0; i < bundleCount; i++) {
            List<String> rolled = rollOffers(tier, excludedAugmentIds);
            if (rolled.isEmpty()) {
                continue;
            }

            rerolledOffers.addAll(rolled);
            for (String augmentId : rolled) {
                String normalizedId = normalizeAugmentId(augmentId);
                if (normalizedId != null) {
                    excludedAugmentIds.add(normalizedId);
                }
            }
        }

        if (rerolledOffers.isEmpty()) {
            return false;
        }

        playerData.setAugmentOffersForTier(tierKey, rerolledOffers);
        playerData.incrementAugmentRerollsUsedForTier(tierKey);
        playerDataManager.save(playerData);
        return true;
    }

    /**
     * Clears selected augments and pending offers, then rerolls unlocks the player
     * is
     * currently eligible for.
     */
    public void resetAllAugments(@Nonnull PlayerData playerData) {
        playerData.clearSelectedAugments();
        playerData.clearAugmentOffers();
        ensureUnlocks(playerData);
        playerDataManager.save(playerData);
    }

    /**
     * Trims pending/selected augment unlocks that exceed the player's current
     * eligibility (for example after reducing prestige).
     */
    public boolean trimExcessUnlocks(@Nonnull PlayerData playerData) {
        int playerLevel = playerData.getLevel();
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerData, playerLevel);

        boolean updated = false;
        for (PassiveTier tier : PassiveTier.values()) {
            String tierKey = tier.name();
            int eligibleMilestones = Math.max(0, eligibleByTier.getOrDefault(tier, 0));

            int selectedCount = countSelectedForTier(playerData, tierKey);
            List<String> offers = new ArrayList<>(playerData.getAugmentOffersForTier(tierKey));
            int pendingCount = countPendingUnlockBundles(offers);
            int grantedCount = selectedCount + pendingCount;

            int excess = grantedCount - eligibleMilestones;
            if (excess <= 0) {
                continue;
            }

            int bundlesToRemove = Math.min(excess, pendingCount);
            if (bundlesToRemove > 0) {
                truncateOfferBundlesFromEnd(offers, bundlesToRemove);
                playerData.setAugmentOffersForTier(tierKey, offers);
                excess -= bundlesToRemove;
                updated = true;
            }

            if (excess > 0) {
                int removedSelections = removeSelectedEntriesFromTier(playerData, tierKey, excess);
                if (removedSelections > 0) {
                    updated = true;
                }
            }
        }

        if (trimExcessRerollUsage(playerData)) {
            updated = true;
        }

        if (updated) {
            playerDataManager.save(playerData);
        }

        return updated;
    }

    /**
     * Returns tiers that currently have unclaimed augment offers for the player.
     */
    public List<PassiveTier> getPendingOfferTiers(@Nonnull PlayerData playerData) {
        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        List<PassiveTier> tiers = new ArrayList<>();
        PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON };
        for (PassiveTier tier : priority) {
            if (!offers.getOrDefault(tier.name(), List.of()).isEmpty()) {
                tiers.add(tier);
            }
        }
        return tiers;
    }

    public int getNextUnlockLevel(int currentLevel) {
        int next = Integer.MAX_VALUE;
        for (UnlockRule rule : unlockRules) {
            for (int level : rule.levels()) {
                if (level > currentLevel && level < next) {
                    next = level;
                }
            }
        }
        return next == Integer.MAX_VALUE ? -1 : next;
    }

    public int getNextUnlockLevel(@Nonnull PlayerData playerData, int currentLevel) {
        int next = getNextUnlockLevel(currentLevel);
        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());

        for (PrestigeUnlockRule rule : prestigeUnlockRules) {
            if (rule.countEligibleIgnoringPlayerLevel(prestigeLevel) <= 0) {
                continue;
            }

            int requiredPlayerLevel = rule.requiredPlayerLevel();
            if (requiredPlayerLevel > currentLevel) {
                next = next <= 0 ? requiredPlayerLevel : Math.min(next, requiredPlayerLevel);
            }
        }

        return next;
    }

    public int getEligibleMilestoneCount(int playerLevel) {
        return getEligibleMilestoneCount(null, playerLevel);
    }

    public int getEligibleMilestoneCount(PlayerData playerData, int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerData, playerLevel);
        int total = 0;
        for (int count : eligibleByTier.values()) {
            total += Math.max(0, count);
        }
        return total;
    }

    public int getGrantedMilestoneCount(@Nonnull PlayerData playerData, int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerData, playerLevel);
        int total = 0;
        for (Map.Entry<PassiveTier, Integer> entry : eligibleByTier.entrySet()) {
            String tierKey = entry.getKey().name();
            int selectedCount = countSelectedForTier(playerData, tierKey);
            int pendingCount = countPendingUnlockBundles(playerData.getAugmentOffersForTier(tierKey));
            int granted = selectedCount + pendingCount;
            total += Math.min(entry.getValue(), Math.max(0, granted));
        }
        return total;
    }

    private List<String> rollOffers(PassiveTier tier, Set<String> excludedAugmentIds) {
        Map<String, AugmentDefinition> all = augmentManager.getAugments();
        LOGGER.atFiner().log("Rolling tier %s: augmentManager size=%d", tier, all != null ? all.size() : -1);
        List<AugmentDefinition> pool = all.values().stream()
                .filter(def -> def != null && def.getTier() == tier)
                .filter(def -> {
                    String normalizedId = normalizeAugmentId(def.getId());
                    return normalizedId != null
                            && (excludedAugmentIds == null || !excludedAugmentIds.contains(normalizedId));
                })
                .collect(Collectors.toCollection(ArrayList::new));
        if (pool.isEmpty()) {
            LOGGER.atWarning().log("No augments available for tier %s; unlock roll skipped", tier);
            return List.of();
        }
        Collections.shuffle(pool, ThreadLocalRandom.current());
        int count = Math.min(DEFAULT_OFFER_COUNT, pool.size());
        List<String> rolled = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rolled.add(pool.get(i).getId());
        }
        return rolled;
    }

    private Set<String> collectOwnedAugmentIds(PlayerData playerData) {
        Set<String> ids = new HashSet<>();
        if (playerData == null) {
            return ids;
        }

        for (String selectedId : playerData.getSelectedAugmentsSnapshot().values()) {
            String normalizedId = normalizeAugmentId(selectedId);
            if (normalizedId != null) {
                ids.add(normalizedId);
            }
        }

        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        for (List<String> tierOffers : offers.values()) {
            if (tierOffers == null || tierOffers.isEmpty()) {
                continue;
            }
            for (String offerId : tierOffers) {
                String normalizedId = normalizeAugmentId(offerId);
                if (normalizedId != null) {
                    ids.add(normalizedId);
                }
            }
        }

        return ids;
    }

    private Set<String> collectArchetypeBlockedAugmentIds(PlayerData playerData) {
        Set<String> blocked = new HashSet<>();
        if (playerData == null || archetypePassiveManager == null) {
            return blocked;
        }

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager.getSnapshot(playerData);
        if (snapshot == null || snapshot.isEmpty()) {
            return blocked;
        }

        if (!snapshot.getDefinitions(ArchetypePassiveType.FIRST_STRIKE).isEmpty()) {
            String normalized = normalizeAugmentId(FirstStrikeAugment.ID);
            if (normalized != null) {
                blocked.add(normalized);
            }
        }
        if (!snapshot.getDefinitions(ArchetypePassiveType.EXECUTIONER).isEmpty()) {
            String normalized = normalizeAugmentId(ExecutionerAugment.ID);
            if (normalized != null) {
                blocked.add(normalized);
            }
        }
        return blocked;
    }

    private String normalizeAugmentId(String augmentId) {
        if (augmentId == null || augmentId.isBlank()) {
            return null;
        }
        return augmentId.trim().toLowerCase(Locale.ROOT);
    }

    private List<UnlockRule> parseRules() {
        Object raw = configManager.get("augments.unlocks", Collections.emptyList(), false);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            LOGGER.atWarning().log("Config augments.unlocks is missing or empty (type=%s, value=%s)",
                    raw == null ? "null" : raw.getClass().getSimpleName(), raw);
            return List.of();
        }
        List<UnlockRule> rules = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            PassiveTier tier = parseTier(map.get("tier"));
            if (tier == null) {
                continue;
            }
            Set<Integer> levels = parseLevels(map.get("levels"));
            if (levels.isEmpty()) {
                continue;
            }
            rules.add(new UnlockRule(tier, levels));
        }
        // Ensure deterministic ordering: higher tiers first, then ascending level
        rules.sort(Comparator.comparing(UnlockRule::tier).reversed());
        return rules;
    }

    private PassiveTier parseTier(Object value) {
        if (value instanceof PassiveTier tier) {
            return tier;
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return PassiveTier.valueOf(str.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private Set<Integer> parseLevels(Object value) {
        Set<Integer> levels = new HashSet<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Number num) {
                    int level = num.intValue();
                    if (level > 0) {
                        levels.add(level);
                    }
                } else if (element instanceof String str) {
                    try {
                        int level = Integer.parseInt(str.trim());
                        if (level > 0) {
                            levels.add(level);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } else if (value instanceof Number num) {
            int level = num.intValue();
            if (level > 0) {
                levels.add(level);
            }
        }
        return levels;
    }

    private record UnlockRule(PassiveTier tier, Set<Integer> levels) {
        int countEligibleMilestones(int playerLevel) {
            int count = 0;
            for (int level : levels) {
                if (playerLevel >= level) {
                    count++;
                }
            }
            return count;
        }
    }

    private List<PrestigeUnlockRule> parsePrestigeRules() {
        Object raw = levelingConfigManager.get("prestige.augment_unlock_tiers", Collections.emptyList(), false);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return defaultPrestigeRules();
        }

        List<PrestigeUnlockRule> rules = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }

            PassiveTier tier = parseTier(map.get("tier"));
            if (tier == null) {
                continue;
            }

            int requiredPlayerLevel = parseInt(map.get("required_player_level"), 0);
            if (requiredPlayerLevel < 0) {
                continue;
            }

            Set<Integer> prestigeLevels = parseLevels(map.get("prestige_levels"));
            if (!prestigeLevels.isEmpty()) {
                rules.add(PrestigeUnlockRule.explicit(tier, prestigeLevels, requiredPlayerLevel));
                continue;
            }

            int requiredPrestigeLevel = parseInt(map.get("required_prestige_level"), 1);
            int maxUnlocks = parseInt(map.get("max_unlocks"), -1);

            if (requiredPrestigeLevel <= 0 || maxUnlocks == 0) {
                continue;
            }

            rules.add(PrestigeUnlockRule.progressive(tier, requiredPrestigeLevel, requiredPlayerLevel, maxUnlocks));
        }

        if (rules.isEmpty()) {
            return defaultPrestigeRules();
        }

        rules.sort(Comparator
                .comparing(PrestigeUnlockRule::firstPrestigeLevel)
                .thenComparing(PrestigeUnlockRule::requiredPlayerLevel)
                .thenComparing(PrestigeUnlockRule::tier));
        return rules;
    }

    private List<PrestigeUnlockRule> defaultPrestigeRules() {
        // Compatibility fallback when prestige.augment_unlock_tiers is absent.
        return List.of(
                PrestigeUnlockRule.explicit(PassiveTier.ELITE, Set.of(2, 4, 6, 8), 0),
                PrestigeUnlockRule.explicit(PassiveTier.MYTHIC, Set.of(10), 0));
    }

    private List<PrestigeRerollRule> parsePrestigeRerollRules() {
        Object raw = levelingConfigManager.get("prestige.augment_reroll_tiers", Collections.emptyList(), false);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<PrestigeRerollRule> rules = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }

            PassiveTier tier = parseTier(map.get("tier"));
            if (tier == null) {
                continue;
            }

            int requiredPlayerLevel = parseInt(map.get("required_player_level"), 0);
            if (requiredPlayerLevel < 0) {
                continue;
            }

            Set<Integer> prestigeLevels = parseLevels(map.get("prestige_levels"));
            if (!prestigeLevels.isEmpty()) {
                rules.add(PrestigeRerollRule.explicit(tier, prestigeLevels, requiredPlayerLevel));
                continue;
            }

            int requiredPrestigeLevel = parseInt(map.get("required_prestige_level"), 1);
            int maxRerolls = parseInt(map.get("max_rerolls"), -1);

            if (requiredPrestigeLevel <= 0 || maxRerolls == 0) {
                continue;
            }

            rules.add(PrestigeRerollRule.progressive(tier, requiredPrestigeLevel, requiredPlayerLevel, maxRerolls));
        }

        if (rules.isEmpty()) {
            return List.of();
        }

        rules.sort(Comparator
                .comparing(PrestigeRerollRule::firstPrestigeLevel)
                .thenComparing(PrestigeRerollRule::requiredPlayerLevel)
                .thenComparing(PrestigeRerollRule::tier));
        return rules;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private int getEligibleRerollsForTier(PlayerData playerData,
            PassiveTier tier,
            int prestigeLevel,
            int playerLevel) {
        if (playerData == null || tier == null || prestigeLevel <= 0 || prestigeRerollRules.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (PrestigeRerollRule rule : prestigeRerollRules) {
            if (rule.tier() != tier) {
                continue;
            }
            total += Math.max(0, rule.countEligibleMilestones(prestigeLevel, playerLevel));
        }

        return total;
    }

    private boolean trimExcessRerollUsage(PlayerData playerData) {
        if (playerData == null || prestigeRerollRules.isEmpty()) {
            return false;
        }

        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        int playerLevel = Math.max(1, playerData.getLevel());
        boolean updated = false;

        for (PassiveTier tier : PassiveTier.values()) {
            int eligible = getEligibleRerollsForTier(playerData, tier, prestigeLevel, playerLevel);
            int used = playerData.getAugmentRerollsUsedForTier(tier.name());
            if (used > eligible) {
                playerData.setAugmentRerollsUsedForTier(tier.name(), eligible);
                updated = true;
            }
        }

        return updated;
    }

    private record PrestigeUnlockRule(PassiveTier tier,
            Set<Integer> prestigeLevels,
            int requiredPrestigeLevel,
            int requiredPlayerLevel,
            int maxUnlocks) {

        static PrestigeUnlockRule explicit(PassiveTier tier, Set<Integer> prestigeLevels, int requiredPlayerLevel) {
            return new PrestigeUnlockRule(tier,
                    prestigeLevels == null ? Set.of() : Set.copyOf(prestigeLevels),
                    1,
                    requiredPlayerLevel,
                    1);
        }

        static PrestigeUnlockRule progressive(PassiveTier tier,
                int requiredPrestigeLevel,
                int requiredPlayerLevel,
                int maxUnlocks) {
            return new PrestigeUnlockRule(tier,
                    Set.of(),
                    requiredPrestigeLevel,
                    requiredPlayerLevel,
                    maxUnlocks);
        }

        int firstPrestigeLevel() {
            if (!prestigeLevels.isEmpty()) {
                int min = Integer.MAX_VALUE;
                for (int level : prestigeLevels) {
                    if (level > 0 && level < min) {
                        min = level;
                    }
                }
                return min == Integer.MAX_VALUE ? Integer.MAX_VALUE : min;
            }
            return requiredPrestigeLevel;
        }

        int countEligibleIgnoringPlayerLevel(int prestigeLevel) {
            if (prestigeLevels.isEmpty()) {
                if (prestigeLevel < requiredPrestigeLevel) {
                    return 0;
                }

                int unlocked = (prestigeLevel - requiredPrestigeLevel) + 1;
                if (maxUnlocks > 0) {
                    return Math.min(unlocked, maxUnlocks);
                }
                return unlocked;
            }

            int count = 0;
            for (int level : prestigeLevels) {
                if (level > 0 && prestigeLevel >= level) {
                    count++;
                }
            }
            return count;
        }

        int countEligibleMilestones(int prestigeLevel, int playerLevel) {
            if (playerLevel < requiredPlayerLevel) {
                return 0;
            }
            return countEligibleIgnoringPlayerLevel(prestigeLevel);
        }
    }

    private record PrestigeRerollRule(PassiveTier tier,
            Set<Integer> prestigeLevels,
            int requiredPrestigeLevel,
            int requiredPlayerLevel,
            int maxRerolls) {

        static PrestigeRerollRule explicit(PassiveTier tier, Set<Integer> prestigeLevels, int requiredPlayerLevel) {
            return new PrestigeRerollRule(tier,
                    prestigeLevels == null ? Set.of() : Set.copyOf(prestigeLevels),
                    1,
                    requiredPlayerLevel,
                    1);
        }

        static PrestigeRerollRule progressive(PassiveTier tier,
                int requiredPrestigeLevel,
                int requiredPlayerLevel,
                int maxRerolls) {
            return new PrestigeRerollRule(tier,
                    Set.of(),
                    requiredPrestigeLevel,
                    requiredPlayerLevel,
                    maxRerolls);
        }

        int firstPrestigeLevel() {
            if (!prestigeLevels.isEmpty()) {
                int min = Integer.MAX_VALUE;
                for (int level : prestigeLevels) {
                    if (level > 0 && level < min) {
                        min = level;
                    }
                }
                return min == Integer.MAX_VALUE ? Integer.MAX_VALUE : min;
            }
            return requiredPrestigeLevel;
        }

        int countEligibleMilestones(int prestigeLevel, int playerLevel) {
            if (playerLevel < requiredPlayerLevel || prestigeLevel < requiredPrestigeLevel) {
                return 0;
            }

            if (!prestigeLevels.isEmpty()) {
                int count = 0;
                for (int level : prestigeLevels) {
                    if (level > 0 && prestigeLevel >= level) {
                        count++;
                    }
                }
                return count;
            }

            int unlocked = (prestigeLevel - requiredPrestigeLevel) + 1;
            if (maxRerolls > 0) {
                return Math.min(unlocked, maxRerolls);
            }
            return unlocked;
        }
    }

    private int countPendingUnlockBundles(List<String> offers) {
        if (offers == null || offers.isEmpty()) {
            return 0;
        }
        return (offers.size() + DEFAULT_OFFER_COUNT - 1) / DEFAULT_OFFER_COUNT;
    }

    private void truncateOfferBundlesFromEnd(List<String> offers, int bundlesToRemove) {
        if (offers == null || offers.isEmpty() || bundlesToRemove <= 0) {
            return;
        }

        int toRemove = Math.min(offers.size(), bundlesToRemove * DEFAULT_OFFER_COUNT);
        int newSize = Math.max(0, offers.size() - toRemove);
        offers.subList(newSize, offers.size()).clear();
    }

    private int removeSelectedEntriesFromTier(PlayerData playerData, String tierKey, int entriesToRemove) {
        if (playerData == null || tierKey == null || tierKey.isBlank() || entriesToRemove <= 0) {
            return 0;
        }

        String normalizedTier = tierKey.trim().toUpperCase(Locale.ROOT);
        List<String> candidateKeys = playerData.getSelectedAugmentsSnapshot().keySet().stream()
                .filter(key -> isTierSelectionKey(key, normalizedTier))
                .sorted((a, b) -> Integer.compare(selectionKeyRank(b, normalizedTier),
                        selectionKeyRank(a, normalizedTier)))
                .collect(Collectors.toCollection(ArrayList::new));

        int removed = 0;
        for (String key : candidateKeys) {
            if (removed >= entriesToRemove) {
                break;
            }
            playerData.setSelectedAugmentForTier(key, null);
            removed++;
        }

        return removed;
    }

    private boolean isTierSelectionKey(String key, String normalizedTier) {
        if (key == null || key.isBlank() || normalizedTier == null || normalizedTier.isBlank()) {
            return false;
        }
        String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
        return normalizedKey.equals(normalizedTier) || normalizedKey.startsWith(normalizedTier + "#");
    }

    private int selectionKeyRank(String key, String normalizedTier) {
        if (key == null || key.isBlank() || normalizedTier == null || normalizedTier.isBlank()) {
            return 0;
        }

        String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
        if (normalizedKey.equals(normalizedTier)) {
            return 0;
        }
        if (!normalizedKey.startsWith(normalizedTier + "#")) {
            return 0;
        }

        String suffix = normalizedKey.substring((normalizedTier + "#").length()).trim();
        if (suffix.isEmpty()) {
            return 1;
        }

        try {
            int parsed = Integer.parseInt(suffix);
            return Math.max(1, parsed);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE / 2;
        }
    }

    private int countSelectedForTier(PlayerData playerData, String tierKey) {
        if (playerData == null || tierKey == null || tierKey.isBlank()) {
            return 0;
        }
        int count = 0;
        String normalizedTier = tierKey.trim().toUpperCase(Locale.ROOT);
        for (String key : playerData.getSelectedAugmentsSnapshot().keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
            if (normalizedKey.equals(normalizedTier) || normalizedKey.startsWith(normalizedTier + "#")) {
                count++;
            }
        }
        return count;
    }

    private Map<PassiveTier, Integer> buildEligibleByTier(PlayerData playerData, int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = new EnumMap<>(PassiveTier.class);
        for (UnlockRule rule : unlockRules) {
            int eligible = rule.countEligibleMilestones(playerLevel);
            if (eligible <= 0) {
                continue;
            }
            eligibleByTier.merge(rule.tier(), eligible, Integer::sum);
        }
        appendPrestigeMilestones(eligibleByTier, playerData, playerLevel);
        return eligibleByTier;
    }

    private void appendPrestigeMilestones(Map<PassiveTier, Integer> eligibleByTier,
            PlayerData playerData,
            int playerLevel) {
        if (eligibleByTier == null || playerData == null) {
            return;
        }

        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        if (prestigeLevel <= 0 || prestigeUnlockRules.isEmpty()) {
            return;
        }

        for (PrestigeUnlockRule rule : prestigeUnlockRules) {
            int bonusMilestones = rule.countEligibleMilestones(prestigeLevel, playerLevel);
            if (bonusMilestones <= 0) {
                continue;
            }
            eligibleByTier.merge(rule.tier(), bonusMilestones, Integer::sum);
        }
    }
}
