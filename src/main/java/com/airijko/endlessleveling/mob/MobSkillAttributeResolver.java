package com.airijko.endlessleveling.mob;

import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive.SummonInheritedStats;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Central resolver for effective mob-facing skill attributes used by augments.
 */
public final class MobSkillAttributeResolver {

    private MobSkillAttributeResolver() {
    }

    public static double resolveSkillAttribute(AugmentHooks.BaseContext context, SkillAttributeType type) {
        if (context == null || type == null) {
            return 0.0D;
        }

        double fromPlayer = resolveFromPlayerContext(context.getSkillManager(), context.getPlayerData(), type);
        if (fromPlayer > 0.0D) {
            return fromPlayer;
        }

        EntityStatMap statMap = resolveContextStatMap(context);
        double fromEntityStats = resolveFromEntityStats(statMap, type);
        if (fromEntityStats > 0.0D) {
            return fromEntityStats;
        }

        return resolveFromSummonInheritance(resolveContextSourceRef(context), resolveContextCommandBuffer(context), type);
    }

    public static double resolveSkillAttribute(EntityStatMap statMap,
            SkillManager skillManager,
            PlayerData playerData,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> sourceRef,
            SkillAttributeType type) {
        if (type == null) {
            return 0.0D;
        }

        double fromPlayer = resolveFromPlayerContext(skillManager, playerData, type);
        if (fromPlayer > 0.0D) {
            return fromPlayer;
        }

        double fromEntityStats = resolveFromEntityStats(statMap, type);
        if (fromEntityStats > 0.0D) {
            return fromEntityStats;
        }

        return resolveFromSummonInheritance(sourceRef, commandBuffer, type);
    }

    private static double resolveFromPlayerContext(SkillManager skillManager,
            PlayerData playerData,
            SkillAttributeType type) {
        if (skillManager == null || playerData == null) {
            return 0.0D;
        }

        return switch (type) {
            case LIFE_FORCE -> Math.max(0.0D,
                    skillManager.calculateSkillAttributeTotalBonus(playerData, SkillAttributeType.LIFE_FORCE, -1));
            case STRENGTH -> Math.max(0.0D, skillManager.calculatePlayerStrength(playerData));
            case SORCERY -> Math.max(0.0D, skillManager.calculatePlayerSorcery(playerData));
            case DEFENSE -> Math.max(0.0D, skillManager.calculatePlayerDefense(playerData));
            case HASTE -> Math.max(0.0D, (skillManager.calculatePlayerHasteMultiplier(playerData) - 1.0D) * 100.0D);
            case STAMINA -> Math.max(0.0D, skillManager.calculatePlayerStamina(playerData));
            case FLOW -> Math.max(0.0D, skillManager.calculatePlayerFlow(playerData));
            case DISCIPLINE -> Math.max(0.0D, skillManager.getDisciplineXpBonusPercent(playerData));
            case PRECISION -> Math.max(0.0D, skillManager.calculatePlayerPrecision(playerData));
            case FEROCITY -> Math.max(0.0D, skillManager.calculatePlayerFerocity(playerData));
        };
    }

    private static EntityStatMap resolveContextStatMap(AugmentHooks.BaseContext context) {
        if (context instanceof AugmentHooks.HitContext hitContext) {
            return hitContext.getAttackerStats();
        }
        if (context instanceof AugmentHooks.DamageTakenContext damageTakenContext) {
            return damageTakenContext.getStatMap();
        }
        if (context instanceof AugmentHooks.PassiveStatContext passiveStatContext) {
            return passiveStatContext.getStatMap();
        }
        if (context instanceof AugmentHooks.KillContext killContext) {
            Ref<EntityStore> killerRef = killContext.getKillerRef();
            CommandBuffer<EntityStore> commandBuffer = killContext.getCommandBuffer();
            if (killerRef != null && commandBuffer != null) {
                return EntityRefUtil.tryGetComponent(commandBuffer, killerRef, EntityStatMap.getComponentType());
            }
        }
        return null;
    }

    private static Ref<EntityStore> resolveContextSourceRef(AugmentHooks.BaseContext context) {
        if (context instanceof AugmentHooks.HitContext hitContext) {
            return hitContext.getAttackerRef();
        }
        if (context instanceof AugmentHooks.DamageTakenContext damageTakenContext) {
            return damageTakenContext.getDefenderRef();
        }
        if (context instanceof AugmentHooks.PassiveStatContext passiveStatContext) {
            return passiveStatContext.getPlayerRef();
        }
        if (context instanceof AugmentHooks.KillContext killContext) {
            return killContext.getKillerRef();
        }
        return null;
    }

    private static CommandBuffer<EntityStore> resolveContextCommandBuffer(AugmentHooks.BaseContext context) {
        if (context instanceof AugmentHooks.HitContext hitContext) {
            return hitContext.getCommandBuffer();
        }
        if (context instanceof AugmentHooks.DamageTakenContext damageTakenContext) {
            return damageTakenContext.getCommandBuffer();
        }
        if (context instanceof AugmentHooks.PassiveStatContext passiveStatContext) {
            return passiveStatContext.getCommandBuffer();
        }
        if (context instanceof AugmentHooks.KillContext killContext) {
            return killContext.getCommandBuffer();
        }
        return null;
    }

    private static double resolveFromEntityStats(EntityStatMap statMap, SkillAttributeType type) {
        if (statMap == null || type == null) {
            return 0.0D;
        }

        return switch (type) {
            case LIFE_FORCE -> Math.max(0.0D, getMaxHealth(statMap));
            case STAMINA -> Math.max(0.0D, getCurrentStamina(statMap));
            case FLOW -> Math.max(0.0D, getCurrentMana(statMap));
            default -> 0.0D;
        };
    }

    private static double resolveFromSummonInheritance(Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            SkillAttributeType type) {
        if (!EntityRefUtil.isUsable(sourceRef) || sourceRef.getStore() == null) {
            return 0.0D;
        }

        SummonInheritedStats inheritedStats = ArmyOfTheDeadPassive.resolveManagedSummonInheritedStats(
                sourceRef,
                sourceRef.getStore(),
                commandBuffer);

        if (inheritedStats == null) {
            return 0.0D;
        }

        double combinedDamagePercent = Math.max(0.0D, (inheritedStats.damageMultiplier() - 1.0D) * 100.0D);
        return switch (type) {
            case STRENGTH -> combinedDamagePercent * 0.5D;
            case SORCERY -> combinedDamagePercent * 0.5D;
            case DEFENSE -> Math.max(0.0D, inheritedStats.defenseReduction() * 100.0D);
            case HASTE -> Math.max(0.0D, (inheritedStats.movementMultiplier() - 1.0D) * 100.0D);
            case PRECISION -> Math.max(0.0D, inheritedStats.critChance());
            case FEROCITY -> Math.max(0.0D, (inheritedStats.critDamageMultiplier() - 1.0D) * 100.0D);
            case LIFE_FORCE -> Math.max(0.0D, inheritedStats.lifeForceFlatHealthBonus());
            case STAMINA, FLOW, DISCIPLINE -> 0.0D;
        };
    }

    private static float getMaxHealth(EntityStatMap statMap) {
        EntityStatValue health = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        return health != null ? health.getMax() : 0f;
    }

    private static float getCurrentMana(EntityStatMap statMap) {
        EntityStatValue mana = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getMana());
        return mana != null ? mana.get() : 0f;
    }

    private static float getCurrentStamina(EntityStatMap statMap) {
        EntityStatValue stamina = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getStamina());
        return stamina != null ? stamina.get() : 0f;
    }
}
