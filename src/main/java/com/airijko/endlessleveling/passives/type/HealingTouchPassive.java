package com.airijko.endlessleveling.passives.type;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies HEALING_TOUCH on-hit healing using configurable chance and source
 * attribute.
 */
public final class HealingTouchPassive {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final boolean PASSIVE_DEBUG = Boolean
            .parseBoolean(System.getProperty("el.passive.debug", "true"));

    private static final double DEFAULT_TRIGGER_CHANCE = 1.0D;
    private static final SkillAttributeType DEFAULT_SOURCE_ATTRIBUTE = SkillAttributeType.STAMINA;
    private static final double DEFAULT_BASE_RADIUS = 10.0D;
    private static final double DEFAULT_MANA_PER_BLOCK = 0.0D;
    private static final double DEFAULT_SELF_HEAL_EFFECTIVENESS = PartyHealingDistributor.DEFAULT_SELF_HEAL_EFFECTIVENESS;

    private final boolean enabled;
    private final double triggerChance;
    private final double healRatio;
    private final SkillAttributeType sourceAttribute;
    private final double baseRadius;
    private final double manaPerBlock;
    private final double selfHealEffectiveness;

    private HealingTouchPassive(boolean enabled,
            double triggerChance,
            double healRatio,
            SkillAttributeType sourceAttribute,
            double baseRadius,
            double manaPerBlock,
            double selfHealEffectiveness) {
        this.enabled = enabled;
        this.triggerChance = Math.max(0.0D, Math.min(1.0D, triggerChance));
        this.healRatio = Math.max(0.0D, healRatio);
        this.sourceAttribute = sourceAttribute == null ? DEFAULT_SOURCE_ATTRIBUTE : sourceAttribute;
        this.baseRadius = Math.max(0.0D, baseRadius);
        this.manaPerBlock = manaPerBlock;
        this.selfHealEffectiveness = Math.max(0.0D, Math.min(1.0D, selfHealEffectiveness));
    }

    public static HealingTouchPassive fromSnapshot(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return disabled();
        }

        double ratio = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.HEALING_TOUCH));
        if (ratio <= 0.0D) {
            return disabled();
        }

        double chance = DEFAULT_TRIGGER_CHANCE;
        SkillAttributeType sourceAttribute = DEFAULT_SOURCE_ATTRIBUTE;
        double baseRadius = DEFAULT_BASE_RADIUS;
        double manaPerBlock = DEFAULT_MANA_PER_BLOCK;
        double selfHealEffectiveness = DEFAULT_SELF_HEAL_EFFECTIVENESS;

        RacePassiveDefinition strongest = resolveStrongestDefinition(
                snapshot.getDefinitions(ArchetypePassiveType.HEALING_TOUCH));
        if (strongest != null && strongest.properties() != null && !strongest.properties().isEmpty()) {
            Map<String, Object> props = strongest.properties();
            chance = parseBoundedRatio(firstNonNull(props.get("healing_chance"), props.get("trigger_chance")),
                    DEFAULT_TRIGGER_CHANCE);

            Object rawSource = firstNonNull(props.get("source_attribute"), props.get("attribute_source"));
            if (rawSource instanceof String sourceKey) {
                SkillAttributeType parsedType = SkillAttributeType.fromConfigKey(sourceKey);
                if (parsedType != null) {
                    sourceAttribute = parsedType;
                }
            }

            baseRadius = parseNonNegative(props.get("radius"), baseRadius);
            Object radiusScalingRaw = props.get("radius_mana_scaling");
            if (radiusScalingRaw instanceof Map<?, ?> radiusScaling) {
                manaPerBlock = parsePositive(radiusScaling.get("mana_per_block"), manaPerBlock);
            }
            Object selfEffectRaw = firstNonNull(props.get("self_heal_effectiveness"), props.get("self_heal_ratio"));
            selfHealEffectiveness = parseBoundedRatio(selfEffectRaw, selfHealEffectiveness);
        }

        return new HealingTouchPassive(true,
                chance,
                ratio,
                sourceAttribute,
                baseRadius,
                manaPerBlock,
                selfHealEffectiveness);
    }

    public static HealingTouchPassive disabled() {
        return new HealingTouchPassive(false,
                0.0D,
                0.0D,
                DEFAULT_SOURCE_ATTRIBUTE,
                DEFAULT_BASE_RADIUS,
                DEFAULT_MANA_PER_BLOCK,
                DEFAULT_SELF_HEAL_EFFECTIVENESS);
    }

    public boolean enabled() {
        return enabled;
    }

    public void apply(PlayerData playerData,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap attackerStats,
            SkillManager skillManager,
            float dealtDamage) {
        if (!enabled || playerData == null || commandBuffer == null || dealtDamage <= 0f) {
            return;
        }
        if (!EntityRefUtil.isUsable(attackerRef)) {
            return;
        }
        if (triggerChance <= 0.0D) {
            return;
        }
        if (triggerChance < 1.0D && ThreadLocalRandom.current().nextDouble() > triggerChance) {
            return;
        }

        EntityStatMap resolvedStats = attackerStats != null
                ? attackerStats
                : EntityRefUtil.tryGetComponent(commandBuffer, attackerRef, EntityStatMap.getComponentType());
        if (resolvedStats == null) {
            return;
        }

        double sourceValue = resolveSourceValue(playerData, resolvedStats, skillManager);
        if (sourceValue <= 0.0D) {
            return;
        }

        double healAmount = sourceValue * healRatio;
        if (healAmount <= 0.0D) {
            return;
        }

        if (PASSIVE_DEBUG) {
            LOGGER.atInfo().log("[PASSIVE_DEBUG] player=%s passive=%s heal=%.2f source=%s chance=%.2f",
                    playerData.getUuid(),
                    ArchetypePassiveType.HEALING_TOUCH.name(),
                    healAmount,
                    sourceAttribute,
                    triggerChance);
        }

        PartyHealingDistributor.applySplitHealingToWoundedParty(playerData,
                attackerRef,
                commandBuffer,
                resolvedStats,
                healAmount,
                baseRadius,
                manaPerBlock,
                selfHealEffectiveness);
    }

    private double resolveSourceValue(PlayerData playerData,
            EntityStatMap statMap,
            SkillManager skillManager) {
        if (sourceAttribute == SkillAttributeType.LIFE_FORCE) {
            return resolveResourceMax(statMap, DefaultEntityStatTypes.getHealth());
        }
        if (sourceAttribute == SkillAttributeType.STAMINA) {
            return resolveResourceMax(statMap, DefaultEntityStatTypes.getStamina());
        }
        if (sourceAttribute == SkillAttributeType.FLOW) {
            return resolveResourceMax(statMap, DefaultEntityStatTypes.getMana());
        }

        if (skillManager != null) {
            return Math.max(0.0D, skillManager.calculateSkillAttributeTotalBonus(playerData, sourceAttribute, -1));
        }
        SkillManager resolvedSkillManager = EndlessLeveling.getInstance() != null
                ? EndlessLeveling.getInstance().getSkillManager()
                : null;
        if (resolvedSkillManager != null) {
            return Math.max(0.0D,
                    resolvedSkillManager.calculateSkillAttributeTotalBonus(playerData, sourceAttribute, -1));
        }
        return 0.0D;
    }

    private double resolveResourceMax(EntityStatMap statMap, int statIndex) {
        if (statMap == null) {
            return 0.0D;
        }
        EntityStatValue statValue = statMap.get(statIndex);
        if (statValue == null) {
            return 0.0D;
        }
        return Math.max(0.0D, statValue.getMax());
    }

    private static RacePassiveDefinition resolveStrongestDefinition(List<RacePassiveDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }
        RacePassiveDefinition strongest = null;
        double strongestValue = Double.NEGATIVE_INFINITY;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            double value = definition.value();
            if (strongest == null || value > strongestValue) {
                strongest = definition;
                strongestValue = value;
            }
        }
        return strongest;
    }

    private static Object firstNonNull(Object primary, Object secondary) {
        return primary != null ? primary : secondary;
    }

    private static double parseBoundedRatio(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double parseNonNegative(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return Math.max(0.0D, value);
    }

    private static double parsePositive(Object raw, double fallback) {
        double value = parseRawDouble(raw, fallback);
        return value > 0.0D ? value : fallback;
    }

    private static double parseRawDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
