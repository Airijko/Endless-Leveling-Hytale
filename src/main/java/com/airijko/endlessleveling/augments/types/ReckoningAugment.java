package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReckoningAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "reckoning";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Map<UUID, Map<String, Long>> EXECUTION_HEAL_COOLDOWNS = new ConcurrentHashMap<>();

    private final double maxBonus;
    private final String bonusScalingStat;
    private final double fullValueAtHealthPercent;
    private final double selfDamagePercent;
    private final double executionThreshold;
    private final double executionHealPercent;
    private final long executionCooldownMillis;

    public ReckoningAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bonus = AugmentValueReader.getMap(passives, "bonus_damage");
        this.maxBonus = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(bonus, "max_bonus_damage", 0.0D));
        this.bonusScalingStat = String.valueOf(bonus.getOrDefault("scaling_stat", "missing_health_percent"))
                .trim()
                .toLowerCase();
        this.fullValueAtHealthPercent = clamp01(
                AugmentValueReader.getDouble(bonus, "full_value_at_health_percent", 0.0D));

        Map<String, Object> selfDamage = AugmentValueReader.getMap(passives, "self_damage");
        this.selfDamagePercent = AugmentValueReader.getDouble(selfDamage, "percent_of_current_hp", 0.0D);

        Map<String, Object> executionHeal = AugmentValueReader.getMap(passives, "execution_heal");
        this.executionThreshold = AugmentValueReader.getDouble(executionHeal, "trigger_threshold", 0.0D);
        this.executionHealPercent = AugmentValueReader.getDouble(executionHeal, "heal_percent_missing_hp", 0.0D);
        this.executionCooldownMillis = parseDurationMillis(executionHeal.get("cooldown_per_target"));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        EntityStatMap attackerStats = resolveAttackerStats(context);
        EntityStatValue hp = attackerStats == null ? null : attackerStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            LOGGER.atFine().log("Reckoning skip: missing attacker HP stat (attackerStats=%s)", attackerStats);
            return context.getDamage();
        }

        double bonus = resolveBonusDamageMultiplier(hp);
        float updatedDamage = AugmentUtils.applyAdditiveBonusFromBase(
                context.getDamage(),
                context.getBaseDamage(),
                bonus);

        // Always pay the health cost on any resolved hit.
        applySelfDamage(attackerStats, hp);

        // Attempt execution heal when target is low, respecting per-target cooldown.
        applyExecutionHeal(context, attackerStats, hp, updatedDamage);
        return updatedDamage;
    }

    private double resolveBonusDamageMultiplier(EntityStatValue hp) {
        if (hp == null || hp.getMax() <= 0f || maxBonus <= 0.0D) {
            return 0.0D;
        }
        double healthRatio = clamp01(hp.get() / hp.getMax());

        if (!"missing_health_percent".equals(bonusScalingStat)) {
            LOGGER.atFine().log("Reckoning unknown scaling_stat '%s', defaulting to missing_health_percent",
                    bonusScalingStat);
        }

        double normalized;
        if (fullValueAtHealthPercent > 0.0D && fullValueAtHealthPercent < 1.0D) {
            if (healthRatio <= fullValueAtHealthPercent) {
                normalized = 1.0D;
            } else {
                normalized = (1.0D - healthRatio) / (1.0D - fullValueAtHealthPercent);
            }
        } else {
            normalized = 1.0D - healthRatio;
        }

        return maxBonus * clamp01(normalized);
    }

    private void applySelfDamage(EntityStatMap attackerStats, EntityStatValue hp) {
        if (selfDamagePercent <= 0.0D || attackerStats == null) {
            return;
        }
        float current = hp.get();
        float loss = (float) (current * selfDamagePercent);
        float updated = Math.max(0.0f, current - loss);
        LOGGER.atFine().log("Reckoning self-damage: current=%.2f loss=%.2f updated=%.2f", current, loss, updated);
        attackerStats.setStatValue(DefaultEntityStatTypes.getHealth(), updated);
    }

    private EntityStatMap resolveAttackerStats(AugmentHooks.HitContext context) {
        EntityStatMap stats = context.getAttackerStats();
        if (stats != null) {
            LOGGER.atFiner().log("Reckoning attacker stats from context");
            return stats;
        }
        if (context.getCommandBuffer() != null && context.getAttackerRef() != null) {
            EntityStatMap fromBuffer = EntityRefUtil.tryGetComponent(context.getCommandBuffer(),
                    context.getAttackerRef(),
                    EntityStatMap.getComponentType());
            LOGGER.atFiner().log("Reckoning attacker stats via command buffer: %s", fromBuffer);
            return fromBuffer;
        }
        LOGGER.atFine().log("Reckoning attacker stats unresolved (no map available)");
        return null;
    }

    private EntityStatMap resolveTargetStats(AugmentHooks.HitContext context) {
        EntityStatMap stats = context.getTargetStats();
        if (stats != null) {
            LOGGER.atFiner().log("Reckoning target stats from context");
            return stats;
        }
        if (context.getCommandBuffer() != null && context.getTargetRef() != null) {
            EntityStatMap fromBuffer = EntityRefUtil.tryGetComponent(context.getCommandBuffer(),
                    context.getTargetRef(),
                    EntityStatMap.getComponentType());
            LOGGER.atFiner().log("Reckoning target stats via command buffer: %s", fromBuffer);
            return fromBuffer;
        }
        LOGGER.atFine().log("Reckoning target stats unresolved (no map available)");
        return null;
    }

    private void applyExecutionHeal(AugmentHooks.HitContext context,
            EntityStatMap attackerStats,
            EntityStatValue hp,
            float hitDamage) {
        if (attackerStats == null || hp == null) {
            return;
        }
        EntityStatMap targetStats = resolveTargetStats(context);
        EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
        if (targetHp == null || targetHp.getMax() <= 0f) {
            return;
        }

        double targetCurrentHp = targetHp.get();
        double targetRatio = targetCurrentHp / targetHp.getMax();
        double projectedTargetHp = targetCurrentHp - Math.max(0.0D, hitDamage);
        boolean targetUnderThreshold = executionThreshold > 0.0D && targetRatio <= executionThreshold;
        boolean lethalHit = projectedTargetHp <= 0.0D;
        if (!targetUnderThreshold && !lethalHit) {
            return;
        }

        // Per-target cooldown check.
        UUID attackerId = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
        if (attackerId == null) {
            return;
        }
        String targetKey = resolveTargetKey(context);
        if (targetKey == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastProc = EXECUTION_HEAL_COOLDOWNS
                .computeIfAbsent(attackerId, id -> new ConcurrentHashMap<>())
                .getOrDefault(targetKey, 0L);
        if (executionCooldownMillis > 0L && now - lastProc < executionCooldownMillis) {
            return;
        }

        double missingHp = Math.max(0.0D, hp.getMax() - hp.get());
        double healAmount = missingHp * Math.max(0.0D, executionHealPercent);
        float applied = AugmentUtils.heal(attackerStats, healAmount);
        if (applied > 0f) {
            EXECUTION_HEAL_COOLDOWNS.get(attackerId).put(targetKey, now);
            LOGGER.atFine().log("Reckoning execution heal applied: target=%s healed=%.2f missing=%.2f", targetKey,
                    applied, missingHp);
        }
    }

    private String resolveTargetKey(AugmentHooks.HitContext context) {
        if (context.getTargetRef() == null) {
            return null;
        }
        if (context.getCommandBuffer() != null) {
            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getTargetRef());
            if (playerRef != null && playerRef.isValid() && playerRef.getUuid() != null) {
                return playerRef.getUuid().toString();
            }
        }
        return context.getTargetRef().toString();
    }

    private long parseDurationMillis(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number number) {
            return AugmentUtils.secondsToMillis(number.doubleValue());
        }
        if (raw instanceof String str) {
            String trimmed = str.trim().toLowerCase();
            if (trimmed.endsWith("ms")) {
                try {
                    return Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim());
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
            if (trimmed.endsWith("s")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            try {
                return AugmentUtils.secondsToMillis(Double.parseDouble(trimmed));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
