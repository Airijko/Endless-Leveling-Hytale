package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final List<UnlockRule> unlockRules;

    public AugmentUnlockManager(@Nonnull ConfigManager configManager,
            @Nonnull AugmentManager augmentManager,
            @Nonnull PlayerDataManager playerDataManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.augmentManager = Objects.requireNonNull(augmentManager, "augmentManager");
        this.playerDataManager = Objects.requireNonNull(playerDataManager, "playerDataManager");
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

        boolean updated = false;
        for (UnlockRule rule : unlockRules) {
            if (!rule.isEligible(playerData.getLevel())) {
                LOGGER.atFiner().log("Skip %s: level %d below required %s", rule.tier(), playerData.getLevel(),
                        rule.levels);
                continue;
            }
            String tierKey = rule.tier().name();
            boolean hasOffers = !playerData.getAugmentOffersForTier(tierKey).isEmpty();
            boolean alreadyChosen = playerData.getSelectedAugmentForTier(tierKey) != null;
            if (hasOffers || alreadyChosen) {
                LOGGER.atFiner().log("Skip %s: offers=%s chosen=%s for %s", tierKey, hasOffers, alreadyChosen,
                        playerData.getPlayerName());
                continue; // already rolled/claimed for this tier
            }
            List<String> rolled = rollOffers(rule.tier());
            if (!rolled.isEmpty()) {
                playerData.setAugmentOffersForTier(tierKey, rolled);
                updated = true;
                LOGGER.atFine().log("Rolled %d %s augments for %s (level %d)",
                        rolled.size(), tierKey, playerData.getPlayerName(), playerData.getLevel());
            } else {
                LOGGER.atWarning().log("Failed to roll augments for tier %s (pool empty?) for %s (level %d)",
                        tierKey, playerData.getPlayerName(), playerData.getLevel());
            }
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

    private List<String> rollOffers(PassiveTier tier) {
        Map<String, AugmentDefinition> all = augmentManager.getAugments();
        LOGGER.atFiner().log("Rolling tier %s: augmentManager size=%d", tier, all != null ? all.size() : -1);
        List<AugmentDefinition> pool = all.values().stream()
                .filter(def -> def != null && def.getTier() == tier)
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
        boolean isEligible(int playerLevel) {
            for (int level : levels) {
                if (playerLevel >= level) {
                    return true;
                }
            }
            return false;
        }
    }
}
