package com.airijko.endlessleveling.player;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.types.GlassCannonAugment;
import com.airijko.endlessleveling.augments.types.NestingDollAugment;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.managers.ConfigManager;

/**
 * Centralizes computation of player attributes by merging the live Hytale
 * baselines with race overrides and EndlessLeveling skill bonuses.
 */
public class PlayerAttributeManager {
    // Key for suppressing vanilla mana
    private static final String SUPPRESS_VANILLA_MANA_KEY = "EL_SUPPRESS_VANILLA_MANA";
    private static final String LEGACY_RACE_BASE_INTELLIGENCE_KEY = "EL_RACE_BASE_INTELLIGENCE";
    private static final String LEGACY_SKILL_BONUS_INTELLIGENCE_KEY = "SKILL_BONUS_INTELLIGENCE";
    private static final String LEGACY_RACE_BASE_MANA_KEY = "EL_RACE_BASE_MANA";
    private static final String LEGACY_SKILL_BONUS_MANA_KEY = "SKILL_BONUS_MANA";
    private static final String LEGACY_SUPPRESS_VANILLA_INTELLIGENCE_KEY = "EL_SUPPRESS_VANILLA_INTELLIGENCE";
    private static final String LEGACY_SUPPRESS_VANILLA_FLOW_KEY = "EL_SUPPRESS_VANILLA_FLOW";
    private static final String LEGACY_RACE_BASE_ENDURANCE_KEY = "EL_RACE_BASE_ENDURANCE";
    private static final String LEGACY_SKILL_BONUS_ENDURANCE_KEY = "SKILL_BONUS_ENDURANCE";
    private static final String LEGACY_RACE_BASE_VITALITY_KEY = "EL_RACE_BASE_VITALITY";
    private static final String LEGACY_SKILL_BONUS_VITALITY_KEY = "SKILL_BONUS_VITALITY";
    private static final double DEFAULT_GLASS_CANNON_HEALTH_PENALTY = 0.20D;
    private static final double DEFAULT_NESTING_DOLL_HEALTH_PENALTY = 1.0D / 3.0D;
    private static final double MAX_HEALTH_PENALTY = 0.95D;

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public enum AttributeSlot {
        LIFE_FORCE("EL_RACE_BASE_HEALTH", "SKILL_BONUS_HEALTH", DefaultEntityStatTypes.getHealth(),
                SkillAttributeType.LIFE_FORCE, 100.0f, false),
        STAMINA("EL_RACE_BASE_STAMINA", "SKILL_BONUS_STAMINA", DefaultEntityStatTypes.getStamina(),
                SkillAttributeType.STAMINA, 10.0f, false),
        FLOW("EL_RACE_BASE_FLOW", "SKILL_BONUS_FLOW", DefaultEntityStatTypes.getMana(),
                SkillAttributeType.FLOW, 0.0f, true);

        private final String raceModifierKey;
        private final String skillModifierKey;
        private final int statIndex;
        private final SkillAttributeType attributeType;
        private final float vanillaBase;
        private final boolean clampBaselineAtZero;

        AttributeSlot(String raceModifierKey, String skillModifierKey, int statIndex,
                SkillAttributeType attributeType, float vanillaBase, boolean clampBaselineAtZero) {
            this.raceModifierKey = raceModifierKey;
            this.skillModifierKey = skillModifierKey;
            this.statIndex = statIndex;
            this.attributeType = attributeType;
            this.vanillaBase = vanillaBase;
            this.clampBaselineAtZero = clampBaselineAtZero;
        }

        public String raceModifierKey() {
            return raceModifierKey;
        }

        public String skillModifierKey() {
            return skillModifierKey;
        }

        public int statIndex() {
            return statIndex;
        }

        public SkillAttributeType attributeType() {
            return attributeType;
        }

        public float vanillaBase() {
            return vanillaBase;
        }

        public boolean clampBaselineAtZero() {
            return clampBaselineAtZero;
        }
    }

    public record AttributeComputation(float hytaleBase,
            float raceBase,
            float raceDelta,
            float skillBonus,
            float finalMax) {
    }

    private final RaceManager raceManager;

    public PlayerAttributeManager(@Nonnull RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    public boolean applyAttribute(@Nonnull AttributeSlot slot,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull PlayerData playerData,
            float skillBonus) {

        EntityStatMap statMap = accessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            LOGGER.atWarning().log("PlayerAttributeManager: EntityStatMap missing for %s", ref);
            return false;
        }

        EntityStatValue current = statMap.get(slot.statIndex());
        if (current == null) {
            LOGGER.atWarning().log("PlayerAttributeManager: stat %s missing for %s", slot.name(), ref);
            return false;
        }

        // Do not rewrite LIFE_FORCE while the entity is in a dead/near-death
        // state. This avoids stat-map correction churn interfering with combat
        // death attribution during revive/low-hp transitions.
        if (slot == AttributeSlot.LIFE_FORCE && current.get() <= 1.0f) {
            return true;
        }

        float previousMax = current.getMax();
        float previousValue = current.get();

        cleanupLegacyAttributeModifiers(slot, statMap);
        statMap.removeModifier(slot.statIndex(), slot.raceModifierKey());
        statMap.removeModifier(slot.statIndex(), slot.skillModifierKey());

        // Remove old suppression modifier if present (for mana only)
        if (slot == AttributeSlot.FLOW) {
            statMap.removeModifier(slot.statIndex(), SUPPRESS_VANILLA_MANA_KEY);
        }

        EntityStatValue baseline = statMap.get(slot.statIndex());
        if (baseline == null) {
            baseline = current;
        }

        AttributeComputation computation = computeContribution(slot, baseline.getMax(), skillBonus, playerData);
        applyStatModifier(statMap, slot.statIndex(), slot.raceModifierKey(), computation.raceDelta());
        applyStatModifier(statMap, slot.statIndex(), slot.skillModifierKey(), computation.skillBonus());

        // Apply suppression modifier for vanilla mana (subtract vanilla base)
        if (slot == AttributeSlot.FLOW) {
            float vanillaMana = slot.vanillaBase();
            if (Math.abs(vanillaMana) > 0.0001f) {
                applyStatModifier(statMap, slot.statIndex(), SUPPRESS_VANILLA_MANA_KEY, -vanillaMana);
            }
        }

        EntityStatValue updated = statMap.get(slot.statIndex());
        float newMax = updated != null ? updated.getMax() : computation.finalMax();
        // Clamp the final max to a minimum of 0.0f to prevent negative stats
        newMax = Math.max(0.0f, newMax);
        float ratio = previousMax > 0.01f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.01f, Math.min(newMax, ratio * newMax));
        statMap.setStatValue(slot.statIndex(), newValue);
        statMap.update();

        return true;
    }

    private void cleanupLegacyAttributeModifiers(@Nonnull AttributeSlot slot, @Nonnull EntityStatMap statMap) {
        if (slot == AttributeSlot.FLOW) {
            statMap.removeModifier(slot.statIndex(), LEGACY_RACE_BASE_INTELLIGENCE_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SKILL_BONUS_INTELLIGENCE_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_RACE_BASE_MANA_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SKILL_BONUS_MANA_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SUPPRESS_VANILLA_INTELLIGENCE_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SUPPRESS_VANILLA_FLOW_KEY);
            return;
        }

        if (slot == AttributeSlot.STAMINA) {
            statMap.removeModifier(slot.statIndex(), LEGACY_RACE_BASE_ENDURANCE_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SKILL_BONUS_ENDURANCE_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_RACE_BASE_VITALITY_KEY);
            statMap.removeModifier(slot.statIndex(), LEGACY_SKILL_BONUS_VITALITY_KEY);
        }
    }

    public AttributeComputation computeContribution(@Nonnull AttributeSlot slot,
            float rawHytaleBase,
            float skillBonus,
            @Nonnull PlayerData playerData) {
        SkillAttributeType attributeType = slot.attributeType();
        float baseline = slot.clampBaselineAtZero() ? Math.max(0.0f, rawHytaleBase) : rawHytaleBase;
        float vanillaBase = slot.vanillaBase();
        float raceBase = resolveRaceBaseValue(playerData, attributeType, vanillaBase);
        float raceDelta = raceBase - vanillaBase;
        float effectiveRaceDelta = raceDelta;
        float effectiveSkillBonus = skillBonus;

        if (slot == AttributeSlot.LIFE_FORCE) {
            double multiplier = 1.0D;
            double glassCannonPenalty = resolveGlassCannonHealthPenalty(playerData);
            if (glassCannonPenalty > 0.0D) {
                multiplier *= Math.max(0.0D, 1.0D - glassCannonPenalty);
            }

            double nestingDollPenalty = resolveNestingDollHealthPenalty(playerData);
            if (nestingDollPenalty > 0.0D) {
                multiplier *= Math.max(0.0D, 1.0D - nestingDollPenalty);
            }

            if (multiplier < 0.9999D) {
                float scaledMultiplier = (float) Math.max(0.0D, multiplier);
                // The EndlessLeveling-owned LIFE_FORCE target is (raceBase + skillBonus).
                // Keep the race contribution intact when possible and consume the
                // penalty from skill-owned health first. This avoids surfacing a
                // giant negative standalone modifier or collapsing everything into
                // the race bucket, while still leaving external non-mod health
                // sources untouched.
                float modOwnedTarget = Math.max(0.0f, raceBase + skillBonus);
                float penalizedTarget = modOwnedTarget * scaledMultiplier;
                float adjustedRaceBase = Math.min(Math.max(0.0f, raceBase), penalizedTarget);
                effectiveRaceDelta = adjustedRaceBase - vanillaBase;
                effectiveSkillBonus = Math.max(0.0f, penalizedTarget - adjustedRaceBase);
            }
        }

        float finalMax = baseline + effectiveRaceDelta + effectiveSkillBonus;
        return new AttributeComputation(baseline, raceBase, effectiveRaceDelta, effectiveSkillBonus, finalMax);
    }

    private double resolveGlassCannonHealthPenalty(@Nonnull PlayerData playerData) {
        if (!hasSelectedAugment(playerData, GlassCannonAugment.ID)) {
            return 0.0D;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getAugmentManager() == null) {
            return DEFAULT_GLASS_CANNON_HEALTH_PENALTY;
        }

        AugmentDefinition definition = plugin.getAugmentManager().getAugment(GlassCannonAugment.ID);
        if (definition == null) {
            return DEFAULT_GLASS_CANNON_HEALTH_PENALTY;
        }

        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> healthPenalty = AugmentValueReader.getMap(passives, "health_penalty");
        double penalty = Math.abs(
                AugmentValueReader.getDouble(healthPenalty, "max_health_percent", DEFAULT_GLASS_CANNON_HEALTH_PENALTY));
        return Math.min(MAX_HEALTH_PENALTY, Math.max(0.0D, penalty));
    }

    private double resolveNestingDollHealthPenalty(@Nonnull PlayerData playerData) {
        if (!hasSelectedAugment(playerData, NestingDollAugment.ID)) {
            return 0.0D;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getAugmentRuntimeManager() == null || playerData.getUuid() == null) {
            return 0.0D;
        }

        var state = plugin.getAugmentRuntimeManager()
                .getRuntimeState(playerData.getUuid())
                .getState(NestingDollAugment.ID);
        if (state == null || state.getStacks() <= 0) {
            return 0.0D;
        }

        double perDeathPenalty = DEFAULT_NESTING_DOLL_HEALTH_PENALTY;
        int maxRevives = 1;
        if (plugin.getAugmentManager() != null) {
            AugmentDefinition definition = plugin.getAugmentManager().getAugment(NestingDollAugment.ID);
            if (definition != null) {
                Map<String, Object> passives = definition.getPassives();
                Map<String, Object> deathStacks = AugmentValueReader.getMap(passives, "death_stacks");
                int maxDeaths = Math.max(1, AugmentValueReader.getInt(deathStacks, "max_deaths", 1));
                maxRevives = Math.max(0, maxDeaths - 1);
                perDeathPenalty = Math.max(0.0D,
                        AugmentValueReader.getDouble(deathStacks,
                                "health_penalty_per_death",
                                DEFAULT_NESTING_DOLL_HEALTH_PENALTY));
            }
        }

        int safeStacks = Math.max(0, Math.min(maxRevives, state.getStacks()));
        double totalPenalty = perDeathPenalty * safeStacks;
        return Math.min(MAX_HEALTH_PENALTY, Math.max(0.0D, totalPenalty));
    }

    private boolean hasSelectedAugment(@Nonnull PlayerData playerData, @Nonnull String augmentId) {
        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        if (selected.isEmpty()) {
            return false;
        }
        for (String value : selected.values()) {
            if (value != null && augmentId.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private float resolveRaceBaseValue(PlayerData playerData, SkillAttributeType attributeType, float fallback) {
        if (playerData == null) {
            return fallback;
        }
        double raceValue = getRaceAttribute(playerData, attributeType, fallback);
        return (float) raceValue;
    }

    public double getRaceAttribute(PlayerData playerData, SkillAttributeType attributeType, double fallback) {
        if (raceManager == null || !raceManager.isEnabled()) {
            return fallback;
        }
        return raceManager.getAttribute(playerData, attributeType, fallback);
    }

    public double combineAttribute(PlayerData playerData, SkillAttributeType attributeType, double skillBonus,
            double fallback) {
        double raceValue = getRaceAttribute(playerData, attributeType, fallback);
        return raceValue + skillBonus;
    }

    private void applyStatModifier(EntityStatMap statMap, int statIndex, String key, float amount) {
        statMap.removeModifier(statIndex, key);
        if (Math.abs(amount) <= 0.0001f) {
            return;
        }
        try {
            StaticModifier modifier = new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, amount);
            statMap.putModifier(statIndex, key, modifier);
        } catch (Exception e) {
            LOGGER.atSevere().log("PlayerAttributeManager: Failed to apply modifier %s: %s", key, e.getMessage());
        }
    }
}
