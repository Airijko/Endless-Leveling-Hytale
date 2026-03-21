package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;

import java.util.Locale;
import java.util.Set;

/**
 * Centralized role and augment weighting rules.
 */
public final class AugmentRoleWeightRules {

    private static final Set<String> SORCERY_AUGMENTS = Set.of(
            "arcane_cataclysm", "arcane_comet", "arcane_instability", "arcane_mastery",
            "magic_blade", "magic_missle", "mana_infusion");

    private static final Set<String> STRENGTH_AUGMENTS = Set.of(
            "blood_echo", "blood_frenzy", "blood_surge",
            "fleet_footwork", "overdrive", "overheal",
            "phantom_hits", "conqueror", "predator", "soul_reaver", "bloodthirster",
            "vampiric_strike", "vampirism", "endure_pain", "undying_rage");

        // Hybrid augments affect both damage paths, either through generic bonus damage
        // or by scaling both strength and sorcery.
        private static final Set<String> HYBRID_AUGMENTS = Set.of(
            "brute_force", "fortress", "goliath",
            "phantom_hits", "raging_momentum",
            "phase_rush", "cutdown", "drain", "executioner", "first_strike",
            "giant_slayer", "glass_cannon", "raid_boss", "time_master", "reckoning",
            "snipers_reach", "rebirth", "protective_bubble", "wither");

    // Augments that are primarily defensive/health scaling.
    private static final Set<String> LIFE_FORCE_AUGMENTS = Set.of(
            "cripple", "fortress", "goliath", "raid_boss", "tank_engine", "nesting_doll", "bailout", "death_bomb");

    private static final Set<String> SORCERY_FAVORED_ROLES = Set.of("mage", "battlemage", "support");
    private static final Set<String> STRENGTH_FAVORED_ROLES = Set.of(
            "assassin", "diver", "skirmisher", "juggernaut", "vanguard", "marksman");
    private static final Set<String> LIFE_FORCE_FAVORED_ROLES = Set.of("vanguard", "juggernaut", "battlemage", "skirmisher");

    private AugmentRoleWeightRules() {
    }

    public static boolean isSorceryAugment(String augmentId) {
        String normalized = normalizeAugmentId(augmentId);
        return normalized != null && SORCERY_AUGMENTS.contains(normalized);
    }

    public static boolean isStrengthAugment(String augmentId) {
        String normalized = normalizeAugmentId(augmentId);
        return normalized != null && STRENGTH_AUGMENTS.contains(normalized);
    }

    public static boolean isHybridAugment(String augmentId) {
        String normalized = normalizeAugmentId(augmentId);
        return normalized != null && HYBRID_AUGMENTS.contains(normalized);
    }

    public static int getRoleWeightBonus(CharacterClassDefinition primaryClass, String augmentId) {
        if (primaryClass == null) {
            return 0;
        }

        String normalizedAugmentId = normalizeAugmentId(augmentId);
        if (normalizedAugmentId == null) {
            return 0;
        }

        int bonus = 0;

        // Overlap is intentional: an augment can be hybrid and still role-favored.
        if (SORCERY_AUGMENTS.contains(normalizedAugmentId)
                && hasAnyRole(primaryClass, SORCERY_FAVORED_ROLES)) {
            bonus += 20;
        }

        if (STRENGTH_AUGMENTS.contains(normalizedAugmentId)
                && hasAnyRole(primaryClass, STRENGTH_FAVORED_ROLES)) {
            bonus += 20;
        }

        if (LIFE_FORCE_AUGMENTS.contains(normalizedAugmentId)
                && hasAnyRole(primaryClass, LIFE_FORCE_FAVORED_ROLES)) {
            // Higher bonus to strongly bias tanky/life-force augments for these roles.
            bonus += 30;
        }

        if ("snipers_reach".equals(normalizedAugmentId)
                && hasAnyRole(primaryClass, Set.of("marksman"))) {
            bonus += 20;
        }

        return bonus;
    }

    public static boolean isAugmentAllowedForClass(CharacterClassDefinition primaryClass, String augmentId) {
        String normalizedAugmentId = normalizeAugmentId(augmentId);
        if (normalizedAugmentId == null) {
            return false;
        }

        // Do not offer Sniper's Reach to melee-only classes.
        if ("snipers_reach".equals(normalizedAugmentId)) {
            return classSupportsRangedCombat(primaryClass);
        }

        return true;
    }

    private static boolean classSupportsRangedCombat(CharacterClassDefinition definition) {
        if (definition == null) {
            return true;
        }

        String rangeType = definition.getRangeType();
        if (rangeType == null || rangeType.isBlank()) {
            return true;
        }

        return rangeType.toLowerCase(Locale.ROOT).contains("range");
    }

    private static boolean hasAnyRole(CharacterClassDefinition definition, Set<String> normalizedTargetRoles) {
        if (definition == null || normalizedTargetRoles == null || normalizedTargetRoles.isEmpty()) {
            return false;
        }

        for (String role : definition.getRoles()) {
            if (role == null || role.isBlank()) {
                continue;
            }

            String normalizedRole = role.trim().toLowerCase(Locale.ROOT);
            if (normalizedTargetRoles.contains(normalizedRole)) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeAugmentId(String augmentId) {
        if (augmentId == null || augmentId.isBlank()) {
            return null;
        }
        return augmentId.trim().toLowerCase(Locale.ROOT);
    }
}
