package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.augments.types.CommonAugment;
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
    private static final String DEFENSE_STAT_KEY = "defense";
    private static final String STRENGTH_STAT_KEY = "strength";
    private static final String SORCERY_STAT_KEY = "sorcery";
    private static final String PRECISION_STAT_KEY = "precision";
    private static final String FEROCITY_STAT_KEY = "ferocity";
    private static final String VANGUARD_BASE_CLASS_ID = "vanguard";
    private static final Set<String> DEFENSE_COMMON_BLOCKED_PRIMARY_CLASSES = Set.of(
            "mage",
            "arcanist",
            "marksman",
            "assassin",
            "oracle",
            "healer",
            "necromancer");

    private final ConfigManager configManager;
    private final ConfigManager levelingConfigManager;
    private final AugmentManager augmentManager;
    private final PlayerDataManager playerDataManager;
    private final ClassManager classManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final SkillManager skillManager;
    private volatile List<UnlockRule> unlockRules;
    private volatile List<PrestigeUnlockRule> prestigeUnlockRules;
    private volatile List<PrestigeRerollRule> prestigeRerollRules;
    private volatile int playerLevelCap;
    private volatile int prestigeLevelCapIncrease;

    public AugmentUnlockManager(@Nonnull ConfigManager configManager,
            @Nonnull ConfigManager levelingConfigManager,
            @Nonnull AugmentManager augmentManager,
            @Nonnull PlayerDataManager playerDataManager,
            @Nonnull ClassManager classManager,
            @Nonnull SkillManager skillManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.levelingConfigManager = Objects.requireNonNull(levelingConfigManager, "levelingConfigManager");
        this.augmentManager = Objects.requireNonNull(augmentManager, "augmentManager");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "playerDataManager");
        this.classManager = Objects.requireNonNull(classManager, "classManager");
        this.skillManager = Objects.requireNonNull(skillManager, "skillManager");
        this.archetypePassiveManager = archetypePassiveManager;
        this.unlockRules = List.of();
        this.prestigeUnlockRules = List.of();
        this.prestigeRerollRules = List.of();
        this.playerLevelCap = 100;
        this.prestigeLevelCapIncrease = 10;
        reload();
    }

    /**
     * Reload unlock milestone rules from leveling.yml.
     */
    public synchronized void reload() {
        configManager.load();
        levelingConfigManager.load();
        this.playerLevelCap = Math.max(1, parseInt(levelingConfigManager.get("player_level_cap", 100, false), 100));
        this.prestigeLevelCapIncrease = Math
                .max(0, parseInt(levelingConfigManager.get("prestige.level_cap_increase_per_prestige", 10, false), 10));
        List<UnlockRule> parsed = parseRules();
        List<PrestigeUnlockRule> parsedPrestige = parsePrestigeRules();
        List<PrestigeRerollRule> parsedRerolls = parsePrestigeRerollRules();
        this.unlockRules = parsed;
        this.prestigeUnlockRules = parsedPrestige;
        this.prestigeRerollRules = parsedRerolls;
        if (parsed.isEmpty()) {
            LOGGER.atWarning()
                    .log("No augment unlock rules parsed. Check leveling.yml augments.unlocks or legacy config.yml augments.unlocks.");
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

        boolean updated = sanitizeVanguardCommonCritSelections(playerData);

        int playerLevel = playerData.getLevel();
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerData, playerLevel);
        Set<String> excludedAugmentIds = collectOwnedAugmentIds(playerData);
        excludedAugmentIds.addAll(collectArchetypeBlockedAugmentIds(playerData));

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
                List<String> rolled = rollOffers(playerData, tier, excludedAugmentIds);
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
     * Finds the tier for a pending offer id currently shown to the player.
     */
    public PassiveTier findOfferTier(@Nonnull PlayerData playerData, @Nonnull String offerId) {
        OfferLocation location = findOfferLocation(playerData, null, offerId);
        return location != null ? location.tier() : null;
    }

    /**
     * Consumes one reroll token for the tier and rerolls the specific pending
     * offer.
     *
     * @return replacement augment id when successful, otherwise null.
     */
    public String tryConsumeRerollForOffer(@Nonnull PlayerData playerData,
            @Nonnull PassiveTier tier,
            @Nonnull String offerId) {
        if (playerData == null || tier == null || offerId == null || offerId.isBlank()) {
            return null;
        }

        OfferLocation location = findOfferLocation(playerData, tier, offerId);
        if (location == null) {
            return null;
        }

        if (getRemainingRerolls(playerData, tier) <= 0) {
            return null;
        }

        Set<String> excludedAugmentIds = collectOwnedAugmentIds(playerData);
        String rolled = rollSingleOffer(playerData, tier, excludedAugmentIds);
        if (rolled == null || rolled.isBlank()) {
            return null;
        }

        List<String> offers = new ArrayList<>(playerData.getAugmentOffersForTier(location.tierKey()));
        if (location.offerIndex() < 0 || location.offerIndex() >= offers.size()) {
            return null;
        }

        offers.set(location.offerIndex(), rolled);
        playerData.setAugmentOffersForTier(location.tierKey(), offers);
        playerData.incrementAugmentRerollsUsedForTier(location.tierKey());
        playerDataManager.save(playerData);
        return rolled;
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
            List<String> rolled = rollOffers(playerData, tier, excludedAugmentIds);
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
     * Resets augments for every profile the player has, not just the active one.
     * Saves the player data once after all profiles are processed.
     */
    public void resetAllAugmentsForAllProfiles(@Nonnull PlayerData playerData) {
        int originalActiveIndex = playerData.getActiveProfileIndex();
        for (int profileIndex : playerData.getProfiles().keySet()) {
            playerData.switchProfile(profileIndex);
            playerData.clearSelectedAugments();
            playerData.clearAugmentOffers();
            ensureUnlocks(playerData);
        }
        playerData.switchProfile(originalActiveIndex);
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
        PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.LEGENDARY, PassiveTier.ELITE, PassiveTier.COMMON };
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
            int candidate = rule.nextEligibleLevelAfter(currentLevel);
            if (candidate > currentLevel && candidate < next) {
                next = candidate;
            }
        }
        return next == Integer.MAX_VALUE ? -1 : next;
    }

    public int getNextUnlockLevel(@Nonnull PlayerData playerData, int currentLevel) {
        int localLevel = Math.max(1, currentLevel);
        int progressionLevel = getProgressionLevel(playerData, localLevel);
        int progressionOffset = Math.max(0, progressionLevel - localLevel);
        int next = Integer.MAX_VALUE;

        for (UnlockRule rule : unlockRules) {
            int candidate = rule.nextEligibleLevelAfter(localLevel, progressionLevel, progressionOffset);
            if (candidate > localLevel && candidate < next) {
                next = candidate;
            }
        }

        next = next == Integer.MAX_VALUE ? -1 : next;
        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());

        for (PrestigeUnlockRule rule : prestigeUnlockRules) {
            if (rule.countEligibleIgnoringPlayerLevel(prestigeLevel) <= 0) {
                continue;
            }

            int requiredPlayerLevel = rule.requiredPlayerLevel();
            int nextLocalForGate = requiredPlayerLevel - progressionOffset;
            if (nextLocalForGate > localLevel) {
                next = next <= 0 ? nextLocalForGate : Math.min(next, nextLocalForGate);
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

    private List<String> rollOffers(PlayerData playerData, PassiveTier tier, Set<String> excludedAugmentIds) {
        if (tier == PassiveTier.COMMON) {
            List<String> commonOffers = rollCommonStatOffers(playerData);
            if (!commonOffers.isEmpty()) {
                return commonOffers;
            }
        }

        Map<String, AugmentDefinition> all = augmentManager.getAugments();
        LOGGER.atFiner().log("Rolling tier %s: augmentManager size=%d", tier, all != null ? all.size() : -1);
        List<AugmentDefinition> pool = all.values().stream()
                .filter(def -> def != null && def.getTier() == tier)
                .filter(def -> {
                    String normalizedId = normalizeAugmentId(def.getId());
                    return normalizedId != null
                            && (def.isStackable() || excludedAugmentIds == null
                                    || !excludedAugmentIds.contains(normalizedId));
                })
                .filter(def -> isAugmentAllowedForPrimaryClass(def, playerData))
                .collect(Collectors.toCollection(ArrayList::new));
        if (pool.isEmpty()) {
            LOGGER.atWarning().log("No augments available for tier %s; unlock roll skipped", tier);
            return List.of();
        }
        int count = Math.min(DEFAULT_OFFER_COUNT, pool.size());
        return weightedRandomSelection(pool, playerData, count);
    }

    /**
     * Perform weighted random selection from a list of augments.
     * Higher weights increase the probability of selection.
     */
    private List<String> weightedRandomSelection(
            List<AugmentDefinition> pool,
            PlayerData playerData,
            int count) {

        if (pool == null || pool.isEmpty()) {
            return List.of();
        }

        List<String> selected = new ArrayList<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<AugmentDefinition> remaining = new ArrayList<>(pool);

        // Select 'count' augments without replacement using weighted selection
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            // Calculate weights for remaining augments
            List<Integer> weights = new ArrayList<>();
            int totalWeight = 0;

            for (AugmentDefinition augment : remaining) {
                int weight = calculateAugmentWeight(augment, playerData);
                weights.add(weight);
                totalWeight += weight;
            }

            if (totalWeight <= 0) {
                // Fallback to first item if no valid weights
                selected.add(remaining.get(0).getId());
                remaining.remove(0);
                continue;
            }

            // Pick random value in [0, totalWeight)
            int pick = rng.nextInt(totalWeight);

            // Find which augment this corresponds to
            int sum = 0;
            for (int j = 0; j < weights.size(); j++) {
                sum += weights.get(j);
                if (pick < sum) {
                    selected.add(remaining.get(j).getId());
                    remaining.remove(j);
                    break;
                }
            }
        }

        return selected;
    }

    /**
     * Calculate weight for an augment based on player's damage type focus.
     * Evaluates conditions like sorcery_higher_than_strength or strength_higher_than_sorcery.
     */
    private int calculateAugmentWeight(AugmentDefinition augment, PlayerData playerData) {
        if (augment == null || playerData == null || skillManager == null) {
            return 10; // neutral weight
        }

        String id = augment.getId().toLowerCase(Locale.ROOT);
        CharacterClassDefinition primaryClass = resolvePrimaryClass(playerData);

        // Get player's damage values
        float strengthValue = Math.max(0f, skillManager.calculatePlayerStrength(playerData));
        float sorceryValue = Math.max(0f, skillManager.calculatePlayerSorcery(playerData));

        int roleBonus = AugmentRoleWeightRules.getRoleWeightBonus(primaryClass, id);
        boolean isSorceryAugment = AugmentRoleWeightRules.isSorceryAugment(id);
        boolean isStrengthAugment = AugmentRoleWeightRules.isStrengthAugment(id);
        boolean isHybridAugment = AugmentRoleWeightRules.isHybridAugment(id);

        // Pure damage-path augments should beat hybrid augments when the player's
        // stats support that path. Overlap is still allowed, but hybrid is now the fallback.
        if (isSorceryAugment) {
            if (sorceryValue > strengthValue) {
                return 70 + roleBonus;
            }
            if (sorceryValue == strengthValue) {
                return 40 + roleBonus;
            }
            return 8;
        }

        if (isStrengthAugment) {
            if (strengthValue > sorceryValue) {
                return 70 + roleBonus;
            }
            if (strengthValue == sorceryValue) {
                return 40 + roleBonus;
            }
            return 8;
        }

        if (isHybridAugment) {
            return 25 + roleBonus;
        }

        // Neutral/utility augments can still receive role bonuses (for example, life-force augments).
        return 10 + roleBonus;
    }

    private boolean isAugmentAllowedForPrimaryClass(AugmentDefinition augment, PlayerData playerData) {
        if (augment == null) {
            return false;
        }

        String augmentId = normalizeAugmentId(augment.getId());
        if (augmentId == null) {
            return false;
        }

        CharacterClassDefinition primaryClass = resolvePrimaryClass(playerData);
        return AugmentRoleWeightRules.isAugmentAllowedForClass(primaryClass, augmentId);
    }

    private CharacterClassDefinition resolvePrimaryClass(PlayerData playerData) {
        if (playerData == null || classManager == null) {
            return null;
        }
        return classManager.getPlayerPrimaryClass(playerData);
    }

    private List<String> rollCommonStatOffers(PlayerData playerData) {
        AugmentDefinition commonAugmentDefinition = augmentManager.getAugment(CommonAugment.ID);
        if (commonAugmentDefinition == null || commonAugmentDefinition.getTier() != PassiveTier.COMMON) {
            return List.of();
        }

        Map<String, Object> buffs = AugmentValueReader.getMap(commonAugmentDefinition.getPassives(), "buffs");
        if (buffs.isEmpty()) {
            return List.of();
        }

        DamageBuildFocus damageBuildFocus = resolveDamageBuildFocus(playerData);
        boolean blockDefenseOffer = shouldBlockDefenseCommonOffer(playerData);
        boolean blockCritOffers = shouldBlockVanguardCritCommonOffers(playerData);
        boolean blockPrecisionOffer = shouldBlockPrecisionCommonOffer(playerData);
        List<String> statKeys = new ArrayList<>();
        for (String key : buffs.keySet()) {
            if (key != null && !key.isBlank()) {
                String normalized = key.trim().toLowerCase(Locale.ROOT);
                if (damageBuildFocus == DamageBuildFocus.STRENGTH && SORCERY_STAT_KEY.equals(normalized)) {
                    continue;
                }
                if (damageBuildFocus == DamageBuildFocus.SORCERY && STRENGTH_STAT_KEY.equals(normalized)) {
                    continue;
                }
                if (blockDefenseOffer && DEFENSE_STAT_KEY.equals(normalized)) {
                    continue;
                }
                if (blockCritOffers
                        && (PRECISION_STAT_KEY.equals(normalized) || FEROCITY_STAT_KEY.equals(normalized))) {
                    continue;
                }
                if (blockPrecisionOffer && PRECISION_STAT_KEY.equals(normalized)) {
                    continue;
                }
                statKeys.add(normalized);
            }
        }
        if (statKeys.isEmpty()) {
            return List.of();
        }

        Collections.shuffle(statKeys, ThreadLocalRandom.current());
        int count = Math.min(DEFAULT_OFFER_COUNT, statKeys.size());
        List<String> rolled = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String statKey = statKeys.get(i);
            Map<String, Object> section = AugmentValueReader.getMap(buffs, statKey);
            double value = rollCommonRange(section);
            rolled.add(CommonAugment.buildStatOfferId(statKey, value));
        }
        return rolled;
    }

    private double rollCommonRange(Map<String, Object> section) {
        double base = Math.max(0.0D, AugmentValueReader.getDouble(section, "value", 0.0D));
        double min = Math.max(0.0D, AugmentValueReader.getDouble(section, "min_value", base));
        double max = Math.max(0.0D, AugmentValueReader.getDouble(section, "max_value", base));

        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }

        if (Math.abs(max - min) < 0.0001D) {
            return roundToTwoDecimals(min);
        }

        if (isWholeNumber(min) && isWholeNumber(max)) {
            long minInt = Math.round(min);
            long maxInt = Math.round(max);
            if (maxInt <= minInt) {
                return minInt;
            }
            return ThreadLocalRandom.current().nextLong(minInt, maxInt + 1L);
        }

        return roundToTwoDecimals(ThreadLocalRandom.current().nextDouble(min, max));
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private boolean isWholeNumber(double value) {
        return Math.abs(value - Math.rint(value)) < 0.0001D;
    }

    private String rollSingleOffer(PlayerData playerData, PassiveTier tier, Set<String> excludedAugmentIds) {
        List<String> rolled = rollOffers(playerData, tier, excludedAugmentIds);
        if (rolled.isEmpty()) {
            return null;
        }
        return rolled.get(0);
    }

    private boolean shouldBlockDefenseCommonOffer(PlayerData playerData) {
        String basePrimaryClassId = normalizePrimaryClassBaseId(playerData);
        return basePrimaryClassId != null && DEFENSE_COMMON_BLOCKED_PRIMARY_CLASSES.contains(basePrimaryClassId);
    }

    private boolean shouldBlockVanguardCritCommonOffers(PlayerData playerData) {
        String basePrimaryClassId = normalizePrimaryClassBaseId(playerData);
        return VANGUARD_BASE_CLASS_ID.equals(basePrimaryClassId);
    }

    private boolean shouldBlockPrecisionCommonOffer(PlayerData playerData) {
        if (playerData == null || skillManager == null) {
            return false;
        }
        // Block precision offer if player already has max precision (100% crit chance)
        SkillManager.PrecisionBreakdown precision = skillManager.getPrecisionBreakdown(playerData);
        if (precision == null) {
            return false;
        }
        // Precision breakthrough represents total crit chance percentage (0-1.0 scale becomes 0-100%)
        // Block if already at or above 100%
        return precision.totalPercent() >= 1.0;
    }

    private boolean sanitizeVanguardCommonCritSelections(PlayerData playerData) {
        if (!shouldBlockVanguardCritCommonOffers(playerData)) {
            return false;
        }

        boolean updated = false;
        int removedSelections = 0;
        for (Map.Entry<String, String> selectedEntry : playerData.getSelectedAugmentsSnapshot().entrySet()) {
            String selectionKey = selectedEntry.getKey();
            String selectedAugmentId = selectedEntry.getValue();
            CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(selectedAugmentId);
            if (offer == null || !isBlockedVanguardCritCommonStat(offer.attributeKey())) {
                continue;
            }
            playerData.setSelectedAugmentForTier(selectionKey, null);
            removedSelections++;
            updated = true;
        }

        List<String> currentCommonOffers = new ArrayList<>(
                playerData.getAugmentOffersForTier(PassiveTier.COMMON.name()));
        if (!currentCommonOffers.isEmpty()) {
            List<String> filteredCommonOffers = new ArrayList<>(currentCommonOffers.size());
            int removedOffers = 0;
            for (String offerId : currentCommonOffers) {
                CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(offerId);
                if (offer != null && isBlockedVanguardCritCommonStat(offer.attributeKey())) {
                    removedOffers++;
                    continue;
                }
                filteredCommonOffers.add(offerId);
            }
            if (removedOffers > 0) {
                playerData.setAugmentOffersForTier(PassiveTier.COMMON.name(), filteredCommonOffers);
                updated = true;
                LOGGER.atInfo().log(
                        "Removed %d blocked Vanguard COMMON offers for %s (precision/ferocity)",
                        removedOffers,
                        playerData.getPlayerName());
            }
        }

        if (removedSelections > 0) {
            LOGGER.atInfo().log(
                    "Refunded %d blocked Vanguard COMMON selections for %s (precision/ferocity)",
                    removedSelections,
                    playerData.getPlayerName());
        }

        return updated;
    }

    private boolean isBlockedVanguardCritCommonStat(String attributeKey) {
        if (attributeKey == null || attributeKey.isBlank()) {
            return false;
        }
        String normalized = attributeKey.trim().toLowerCase(Locale.ROOT);
        return PRECISION_STAT_KEY.equals(normalized) || FEROCITY_STAT_KEY.equals(normalized);
    }

    private DamageBuildFocus resolveDamageBuildFocus(PlayerData playerData) {
        if (playerData == null) {
            return DamageBuildFocus.NONE;
        }

        int selectedCommonStrengthCount = 0;
        int selectedCommonSorceryCount = 0;
        for (String selectedAugmentId : playerData.getSelectedAugmentsSnapshot().values()) {
            CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(selectedAugmentId);
            if (offer == null) {
                continue;
            }

            String attributeKey = offer.attributeKey();
            if (STRENGTH_STAT_KEY.equals(attributeKey)) {
                selectedCommonStrengthCount++;
            } else if (SORCERY_STAT_KEY.equals(attributeKey)) {
                selectedCommonSorceryCount++;
            }
        }

        if (selectedCommonStrengthCount > 0 && selectedCommonSorceryCount == 0) {
            return DamageBuildFocus.STRENGTH;
        }
        if (selectedCommonSorceryCount > 0 && selectedCommonStrengthCount == 0) {
            return DamageBuildFocus.SORCERY;
        }

        float strengthValue = Math.max(0f, skillManager.calculatePlayerStrength(playerData));
        float sorceryValue = Math.max(0f, skillManager.calculatePlayerSorcery(playerData));
        if (strengthValue > sorceryValue) {
            return DamageBuildFocus.STRENGTH;
        }
        if (sorceryValue > strengthValue) {
            return DamageBuildFocus.SORCERY;
        }

        return DamageBuildFocus.NONE;
    }

    private String normalizePrimaryClassBaseId(PlayerData playerData) {
        if (playerData == null) {
            return null;
        }

        String primaryClassId = playerData.getPrimaryClassId();
        if (primaryClassId == null || primaryClassId.isBlank()) {
            return null;
        }

        String normalized = primaryClassId.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('_');
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    private enum DamageBuildFocus {
        NONE,
        STRENGTH,
        SORCERY
    }

    private OfferLocation findOfferLocation(PlayerData playerData, PassiveTier expectedTier, String offerId) {
        if (playerData == null || offerId == null || offerId.isBlank()) {
            return null;
        }

        String normalizedRequested = normalizeAugmentId(offerId);
        if (normalizedRequested == null) {
            return null;
        }

        Map<String, List<String>> offersByTier = playerData.getAugmentOffersSnapshot();
        for (Map.Entry<String, List<String>> entry : offersByTier.entrySet()) {
            String tierKey = entry.getKey();
            PassiveTier tier = parseTier(tierKey);
            if (tier == null) {
                continue;
            }
            if (expectedTier != null && tier != expectedTier) {
                continue;
            }

            List<String> offers = entry.getValue();
            if (offers == null || offers.isEmpty()) {
                continue;
            }

            for (int i = 0; i < offers.size(); i++) {
                String candidate = offers.get(i);
                String normalizedCandidate = normalizeAugmentId(candidate);
                if (normalizedRequested.equals(normalizedCandidate)) {
                    return new OfferLocation(tier, tier.name(), i, candidate);
                }
            }
        }

        return null;
    }

    private record OfferLocation(PassiveTier tier, String tierKey, int offerIndex, String offerId) {
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

        return blocked;
    }

    private String normalizeAugmentId(String augmentId) {
        if (augmentId == null || augmentId.isBlank()) {
            return null;
        }
        return augmentId.trim().toLowerCase(Locale.ROOT);
    }

    private List<UnlockRule> parseRules() {
        Object raw = levelingConfigManager.get("augments.unlocks", null, false);
        List<?> list = raw instanceof List<?> parsedList ? parsedList : null;
        if (list == null || list.isEmpty()) {
            LOGGER.atWarning().log(
                "Augment unlock rules are missing from leveling.yml augments.unlocks (type=%s)",
                raw == null ? "null" : raw.getClass().getSimpleName());
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
            if (!levels.isEmpty()) {
                rules.add(UnlockRule.explicit(tier, levels));
                continue;
            }

            int requiredPlayerLevel = parseInt(map.get("required_player_level"), 1);
            int levelInterval = parseInt(map.get("level_interval"),
                    parseInt(map.get("unlock_every_levels"), 1));
            int maxUnlocks = parseInt(map.get("max_unlocks"), -1);
            if (requiredPlayerLevel <= 0 || levelInterval <= 0 || maxUnlocks == 0) {
                continue;
            }

            rules.add(UnlockRule.progressive(tier, requiredPlayerLevel, levelInterval, maxUnlocks));
        }

        // Ensure deterministic ordering: earliest unlock first, then tier.
        rules.sort(Comparator
                .comparingInt(UnlockRule::firstPlayerLevel)
                .thenComparing(UnlockRule::tier));
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

    /**
     * Returns the interval N if value is a wildcard pattern ("*" or
     * "star-slash-N"),
     * otherwise 0.
     */
    private int parseWildcardInterval(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 1) {
            return 0;
        }
        String s = list.get(0) instanceof String str ? str.trim() : null;
        if (s == null) {
            return 0;
        }
        if ("*".equals(s)) {
            return 1;
        }
        if (s.startsWith("*/")) {
            try {
                int n = Integer.parseInt(s.substring(2).trim());
                return n > 0 ? n : 0;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private record UnlockRule(PassiveTier tier,
            Set<Integer> levels,
            int requiredPlayerLevel,
            int levelInterval,
            int maxUnlocks) {

        boolean isProgressive() {
            return levels.isEmpty();
        }

        static UnlockRule explicit(PassiveTier tier, Set<Integer> levels) {
            return new UnlockRule(tier,
                    levels == null ? Set.of() : Set.copyOf(levels),
                    1,
                    1,
                    1);
        }

        static UnlockRule progressive(PassiveTier tier,
                int requiredPlayerLevel,
                int levelInterval,
                int maxUnlocks) {
            return new UnlockRule(tier,
                    Set.of(),
                    Math.max(1, requiredPlayerLevel),
                    Math.max(1, levelInterval),
                    maxUnlocks);
        }

        int countEligibleMilestones(int playerLevel) {
            return countEligibleMilestones(playerLevel, playerLevel);
        }

        int countEligibleMilestones(int playerLevel, int progressionLevel) {
            if (isProgressive()) {
                int effectiveLevel = playerLevel;
                if (effectiveLevel < requiredPlayerLevel) {
                    return 0;
                }

                int interval = Math.max(1, levelInterval);
                int unlocked = ((effectiveLevel - requiredPlayerLevel) / interval) + 1;
                if (maxUnlocks > 0) {
                    return Math.min(unlocked, maxUnlocks);
                }
                return unlocked;
            }

            int count = 0;
            for (int level : levels) {
                if (playerLevel >= level) {
                    count++;
                }
            }
            return count;
        }

        int nextEligibleLevelAfter(int currentLevel) {
            return nextEligibleLevelAfter(currentLevel, currentLevel, 0);
        }

        int nextEligibleLevelAfter(int currentLevel, int progressionLevel, int progressionOffset) {
            if (isProgressive()) {
                if (maxUnlocks == 0) {
                    return -1;
                }

                int interval = Math.max(1, levelInterval);
                int effectiveLevel = currentLevel;
                int nextLocalLevel;

                if (effectiveLevel < requiredPlayerLevel) {
                    nextLocalLevel = requiredPlayerLevel;
                } else {
                    int progressionIndex = ((effectiveLevel - requiredPlayerLevel) / interval) + 1;
                    nextLocalLevel = requiredPlayerLevel + (progressionIndex * interval);
                }

                if (maxUnlocks > 0) {
                    int last = requiredPlayerLevel + ((maxUnlocks - 1) * interval);
                    if (nextLocalLevel > last) {
                        return -1;
                    }
                }
                return nextLocalLevel;
            }

            int next = Integer.MAX_VALUE;
            for (int level : levels) {
                if (level > currentLevel && level < next) {
                    next = level;
                }
            }
            return next == Integer.MAX_VALUE ? -1 : next;
        }

        int firstPlayerLevel() {
            if (levels.isEmpty()) {
                return requiredPlayerLevel;
            }
            int min = Integer.MAX_VALUE;
            for (int level : levels) {
                if (level > 0 && level < min) {
                    min = level;
                }
            }
            return min == Integer.MAX_VALUE ? Integer.MAX_VALUE : min;
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

            int wildcardInterval = parseWildcardInterval(map.get("prestige_levels"));
            if (wildcardInterval > 0) {
                int wildcardMaxUnlocks = parseInt(map.get("max_unlocks"), -1);
                rules.add(PrestigeUnlockRule.progressive(tier, wildcardInterval, requiredPlayerLevel,
                        wildcardMaxUnlocks, wildcardInterval));
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
                PrestigeUnlockRule.explicit(PassiveTier.LEGENDARY, Set.of(5, 10), 0),
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
            int maxUnlocks,
            int interval) {

        static PrestigeUnlockRule explicit(PassiveTier tier, Set<Integer> prestigeLevels, int requiredPlayerLevel) {
            return new PrestigeUnlockRule(tier,
                    prestigeLevels == null ? Set.of() : Set.copyOf(prestigeLevels),
                    1,
                    requiredPlayerLevel,
                    1,
                    1);
        }

        static PrestigeUnlockRule progressive(PassiveTier tier,
                int requiredPrestigeLevel,
                int requiredPlayerLevel,
                int maxUnlocks) {
            return progressive(tier, requiredPrestigeLevel, requiredPlayerLevel, maxUnlocks, 1);
        }

        static PrestigeUnlockRule progressive(PassiveTier tier,
                int requiredPrestigeLevel,
                int requiredPlayerLevel,
                int maxUnlocks,
                int interval) {
            return new PrestigeUnlockRule(tier,
                    Set.of(),
                    requiredPrestigeLevel,
                    requiredPlayerLevel,
                    maxUnlocks,
                    Math.max(1, interval));
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

                int effectiveInterval = Math.max(1, interval);
                int unlocked = ((prestigeLevel - requiredPrestigeLevel) / effectiveInterval) + 1;
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
        int localLevel = Math.max(1, playerLevel);
        int progressionLevel = getProgressionLevel(playerData, localLevel);
        Map<PassiveTier, Integer> eligibleByTier = new EnumMap<>(PassiveTier.class);
        for (UnlockRule rule : unlockRules) {
            int eligible = rule.countEligibleMilestones(localLevel, progressionLevel);
            if (eligible <= 0) {
                continue;
            }
            eligibleByTier.merge(rule.tier(), eligible, Integer::sum);
        }
        appendPrestigeMilestones(eligibleByTier, playerData, localLevel, progressionLevel);
        return eligibleByTier;
    }

    private int getProgressionLevel(PlayerData playerData, int playerLevel) {
        int localLevel = Math.max(1, playerLevel);
        if (playerData == null) {
            return localLevel;
        }

        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        if (prestigeLevel <= 0) {
            return localLevel;
        }

        long offset = 0L;
        for (int i = 0; i < prestigeLevel; i++) {
            offset += getConfiguredLevelCapForPrestige(i);
            if (offset >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }

        long progression = offset + localLevel;
        return progression >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) progression;
    }

    private int getConfiguredLevelCapForPrestige(int prestigeLevel) {
        int safePrestige = Math.max(0, prestigeLevel);
        return Math.max(1, playerLevelCap + (prestigeLevelCapIncrease * safePrestige));
    }

    private void appendPrestigeMilestones(Map<PassiveTier, Integer> eligibleByTier,
            PlayerData playerData,
            int playerLevel,
            int progressionLevel) {
        if (eligibleByTier == null || playerData == null) {
            return;
        }

        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        if (prestigeLevel <= 0 || prestigeUnlockRules.isEmpty()) {
            return;
        }

        for (PrestigeUnlockRule rule : prestigeUnlockRules) {
            int effectiveGateLevel = Math.max(playerLevel, progressionLevel);
            int bonusMilestones = rule.countEligibleMilestones(prestigeLevel, effectiveGateLevel);
            if (bonusMilestones <= 0) {
                continue;
            }
            eligibleByTier.merge(rule.tier(), bonusMilestones, Integer::sum);
        }
    }
}
