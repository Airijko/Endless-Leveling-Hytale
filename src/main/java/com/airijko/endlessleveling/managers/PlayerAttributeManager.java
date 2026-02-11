package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
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

/**
 * Centralizes computation of player attributes by merging the live Hytale
 * baselines with race overrides and EndlessLeveling skill bonuses.
 */
public class PlayerAttributeManager {
    // Key for suppressing vanilla mana
    private static final String SUPPRESS_VANILLA_MANA_KEY = "EL_SUPPRESS_VANILLA_MANA";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public enum AttributeSlot {
        LIFE_FORCE("EL_RACE_BASE_HEALTH", "SKILL_BONUS_HEALTH", DefaultEntityStatTypes.getHealth(),
                SkillAttributeType.LIFE_FORCE, 100.0f, false),
        STAMINA("EL_RACE_BASE_STAMINA", "SKILL_BONUS_STAMINA", DefaultEntityStatTypes.getStamina(),
                SkillAttributeType.STAMINA, 10.0f, false),
        INTELLIGENCE("EL_RACE_BASE_INTELLIGENCE", "SKILL_BONUS_INTELLIGENCE", DefaultEntityStatTypes.getMana(),
                SkillAttributeType.INTELLIGENCE, 0.0f, true);

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

        float previousMax = current.getMax();
        float previousValue = current.get();

        statMap.removeModifier(slot.statIndex(), slot.raceModifierKey());
        statMap.removeModifier(slot.statIndex(), slot.skillModifierKey());

        // Remove old suppression modifier if present (for mana only)
        if (slot == AttributeSlot.INTELLIGENCE) {
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
        if (slot == AttributeSlot.INTELLIGENCE) {
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

    public AttributeComputation computeContribution(@Nonnull AttributeSlot slot,
            float rawHytaleBase,
            float skillBonus,
            @Nonnull PlayerData playerData) {
        SkillAttributeType attributeType = slot.attributeType();
        float baseline = slot.clampBaselineAtZero() ? Math.max(0.0f, rawHytaleBase) : rawHytaleBase;
        float vanillaBase = slot.vanillaBase();
        float raceBase = resolveRaceBaseValue(playerData, attributeType, vanillaBase);
        float raceDelta = raceBase - vanillaBase;
        float finalMax = baseline + raceDelta + skillBonus;
        return new AttributeComputation(baseline, raceBase, raceDelta, skillBonus, finalMax);
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
