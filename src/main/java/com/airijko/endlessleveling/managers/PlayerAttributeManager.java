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

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public enum AttributeSlot {
        LIFE_FORCE(null, "EL_RACE_BASE_HEALTH", "SKILL_BONUS_HEALTH", DefaultEntityStatTypes.getHealth(),
                SkillAttributeType.LIFE_FORCE, false),
        STAMINA(null, "EL_RACE_BASE_STAMINA", "SKILL_BONUS_STAMINA", DefaultEntityStatTypes.getStamina(),
                SkillAttributeType.STAMINA, false),
        INTELLIGENCE("EL_SUPPRESS_VANILLA_MANA", "EL_RACE_BASE_INTELLIGENCE", "SKILL_BONUS_INTELLIGENCE",
                DefaultEntityStatTypes.getMana(), SkillAttributeType.INTELLIGENCE, true);

        private final String baselineModifierKey;
        private final String raceModifierKey;
        private final String skillModifierKey;
        private final int statIndex;
        private final SkillAttributeType attributeType;
        private final boolean zeroVanillaBase;

        AttributeSlot(String baselineModifierKey, String raceModifierKey, String skillModifierKey, int statIndex,
                SkillAttributeType attributeType, boolean zeroVanillaBase) {
            this.baselineModifierKey = baselineModifierKey;
            this.raceModifierKey = raceModifierKey;
            this.skillModifierKey = skillModifierKey;
            this.statIndex = statIndex;
            this.attributeType = attributeType;
            this.zeroVanillaBase = zeroVanillaBase;
        }

        public String baselineModifierKey() {
            return baselineModifierKey;
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

        public boolean zeroVanillaBase() {
            return zeroVanillaBase;
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

        if (slot.zeroVanillaBase() && slot.baselineModifierKey() != null) {
            statMap.removeModifier(slot.statIndex(), slot.baselineModifierKey());
        }
        statMap.removeModifier(slot.statIndex(), slot.raceModifierKey());
        statMap.removeModifier(slot.statIndex(), slot.skillModifierKey());

        EntityStatValue baseline = statMap.get(slot.statIndex());
        if (baseline == null) {
            baseline = current;
        }

        AttributeComputation computation = computeContribution(slot, baseline.getMax(), skillBonus, playerData);

        if (slot.zeroVanillaBase() && slot.baselineModifierKey() != null) {
            applyStatModifier(statMap, slot.statIndex(), slot.baselineModifierKey(), -computation.hytaleBase());
            applyStatModifier(statMap, slot.statIndex(), slot.raceModifierKey(), computation.raceBase());
        } else {
            applyStatModifier(statMap, slot.statIndex(), slot.raceModifierKey(), computation.raceDelta());
        }
        applyStatModifier(statMap, slot.statIndex(), slot.skillModifierKey(), computation.skillBonus());

        EntityStatValue updated = statMap.get(slot.statIndex());
        float newMax = updated != null ? updated.getMax() : computation.finalMax();
        float ratio = previousMax > 0.01f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.01f, Math.min(newMax, ratio * newMax));
        statMap.setStatValue(slot.statIndex(), newValue);
        statMap.update();

        LOGGER.atInfo().log(
                "PlayerAttributeManager: %s -> base=%.2f, race=%.2f, skill=%.2f, finalMax=%.2f for %s",
                slot.name(), computation.hytaleBase(), computation.raceBase(), computation.skillBonus(), newMax,
                playerData.getPlayerName());
        return true;
    }

    public AttributeComputation computeContribution(@Nonnull AttributeSlot slot,
            float rawHytaleBase,
            float skillBonus,
            @Nonnull PlayerData playerData) {
        SkillAttributeType attributeType = slot.attributeType();
        float hytaleBase = slot.zeroVanillaBase() ? Math.max(0.0f, rawHytaleBase) : rawHytaleBase;
        float raceBase = resolveRaceBaseValue(playerData, attributeType, hytaleBase);
        float raceDelta = raceBase - hytaleBase;
        float finalMax = raceBase + skillBonus;
        return new AttributeComputation(hytaleBase, raceBase, raceDelta, skillBonus, finalMax);
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
