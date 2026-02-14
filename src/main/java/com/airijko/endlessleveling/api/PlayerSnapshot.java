package com.airijko.endlessleveling.api;

import com.airijko.endlessleveling.enums.SkillAttributeType;
import java.util.Map;
import java.util.UUID;

/** Immutable view of a player's EndlessLeveling state for external mods. */
public record PlayerSnapshot(
        UUID uuid,
        String playerName,
        int level,
        double xp,
        int skillPoints,
        String raceId,
        String primaryClassId,
        String secondaryClassId,
        Map<SkillAttributeType, Integer> skillLevels,
        double xpGainMultiplier) {
}
