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
    private final AugmentManager augmentManager;
    private final PlayerDataManager playerDataManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final List<UnlockRule> unlockRules;

    public AugmentUnlockManager(@Nonnull ConfigManager configManager,
            @Nonnull AugmentManager augmentManager,
            @Nonnull PlayerDataManager playerDataManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.augmentManager = Objects.requireNonNull(augmentManager, "augmentManager");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "playerDataManager");
        this.archetypePassiveManager = archetypePassiveManager;
        this.unlockRules = parseRules();
        if (this.unlockRules.isEmpty()) {
            LOGGER.atWarning().log("No augment unlock rules parsed. Check config augments.unlocks for tiers/levels.");
        } else {
            LOGGER.atInfo().log("Loaded %d augment unlock rules", this.unlockRules.size());
        }
    }

    /** Ensures any eligible unlocks are rolled and persisted for the player. */
    public void ensureUnlocks(@Nonnull PlayerData playerData) {
        LOGGER.atFiner().log("ensureUnlocks: player=%s level=%d rules=%d", playerData.getPlayerName(),
                playerData.getLevel(), unlockRules.size());

        int playerLevel = playerData.getLevel();
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerLevel);
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

    /** Clears and rerolls all unlock tiers the player qualifies for. */
    public void refreshUnlocks(@Nonnull PlayerData playerData) {
        playerData.clearSelectedAugments();
        playerData.clearAugmentOffers();
        ensureUnlocks(playerData);
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

    public int getEligibleMilestoneCount(int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerLevel);
        int total = 0;
        for (int count : eligibleByTier.values()) {
            total += Math.max(0, count);
        }
        return total;
    }

    public int getGrantedMilestoneCount(@Nonnull PlayerData playerData, int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = buildEligibleByTier(playerLevel);
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

    private int countPendingUnlockBundles(List<String> offers) {
        if (offers == null || offers.isEmpty()) {
            return 0;
        }
        return (offers.size() + DEFAULT_OFFER_COUNT - 1) / DEFAULT_OFFER_COUNT;
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

    private Map<PassiveTier, Integer> buildEligibleByTier(int playerLevel) {
        Map<PassiveTier, Integer> eligibleByTier = new EnumMap<>(PassiveTier.class);
        for (UnlockRule rule : unlockRules) {
            int eligible = rule.countEligibleMilestones(playerLevel);
            if (eligible <= 0) {
                continue;
            }
            eligibleByTier.merge(rule.tier(), eligible, Integer::sum);
        }
        return eligibleByTier;
    }
}
