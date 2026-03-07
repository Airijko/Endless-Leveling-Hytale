package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.HashMap;
import java.util.Map;

public final class UndyingRageAugment extends YamlAugment
        implements AugmentHooks.OnLowHpAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "undying_rage";

    private final long durationMillis;
    private final long cooldownMillis;
    private final double healthThresholdPercent;
    private final double minHealthHp;
    private final double maxBonusFerocity;
    private final double fullValueAtHealthPercent;
    private final String bonusScalingStat;
    private final Map<SkillAttributeType, Double> passiveBuffs;

    public UndyingRageAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> rage = AugmentValueReader.getMap(passives, "rage");
        if (rage.isEmpty()) {
            rage = AugmentValueReader.getMap(passives, "under_rage");
        }
        Map<String, Object> bonusDamage = AugmentValueReader.getMap(passives, "bonus_damage");
        if (bonusDamage.isEmpty()) {
            bonusDamage = AugmentValueReader.getMap(passives, "rage_damage");
        }
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

        this.durationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(rage, "duration", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(rage, "cooldown", 0.0D));
        this.healthThresholdPercent = clamp01(AugmentValueReader.getDouble(rage,
                "health_threshold",
                AugmentValueReader.getDouble(rage, "min_health_percent", 0.0D)));
        this.minHealthHp = Math.max(0.0D,
                AugmentValueReader.getDouble(rage,
                        "min_health_hp",
                        AugmentValueReader.getDouble(rage, "health_threshold_hp", 0.0D)));

        this.bonusScalingStat = String.valueOf(bonusDamage.getOrDefault("scaling_stat", "missing_health_percent"))
                .trim()
                .toLowerCase();
        double configuredMaxFerocity = AugmentValueReader.getDouble(bonusDamage,
                "max_bonus_ferocity",
                AugmentValueReader.getDouble(bonusDamage,
                        "max_bonus_crit_damage",
                        AugmentValueReader.getDouble(bonusDamage, "max_bonus_damage", 0.0D)));
        this.maxBonusFerocity = normalizePercentPoints(configuredMaxFerocity);
        this.fullValueAtHealthPercent = clamp01(AugmentValueReader.getDouble(bonusDamage,
                "full_value_at_health_percent",
                0.0D));
        this.passiveBuffs = parseBuffs(buffs);
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        if (context == null) {
            return 0f;
        }
        var hp = context.getStatMap() == null ? null
                : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getIncomingDamage();
        }
        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getIncomingDamage();
        }
        var state = runtime.getState(ID);

        float thresholdHp = AugmentUtils.resolveThresholdHp(hp.getMax(), minHealthHp, healthThresholdPercent);
        float survivalFloor = AugmentUtils.resolveSurvivalFloor(hp.getMax(), thresholdHp);
        float projected = hp.get() - context.getIncomingDamage();
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());

        // While rage is active, prevent execution below configured threshold.
        if (state.getExpiresAt() > now) {
            return AugmentUtils.applyUnkillableThreshold(context.getStatMap(),
                    context.getIncomingDamage(),
                    thresholdHp,
                    survivalFloor);
        }

        // Only trigger when the incoming hit would push HP under threshold.
        if (projected > thresholdHp) {
            return context.getIncomingDamage();
        }

        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return context.getIncomingDamage();
        }

        state.setExpiresAt(now + durationMillis);
        state.setStacks(1);
        context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(survivalFloor, hp.get()));
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.undying_rage.active",
                            "{0} is active for {1}s.",
                            getName(),
                            durationMillis / 1000.0D));
        }
        return 0f;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }
        var state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        if (state.getStacks() > 0 && state.getExpiresAt() > 0L && now > state.getExpiresAt()) {
            state.setStacks(0);
            state.setExpiresAt(0L);
            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        Lang.tr(playerRef.getUuid(),
                                "augments.undying_rage.ended",
                                "{0} duration ended.",
                                getName()));
            }
        }

        EntityStatValue hp = context.getStatMap() == null
                ? null
                : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        double ferocityBonus = resolveFerocityBonus(hp);
        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_ferocity_bonus",
                SkillAttributeType.FEROCITY,
                ferocityBonus,
                0L);

        for (var entry : passiveBuffs.entrySet()) {
            SkillAttributeType type = entry.getKey();
            double value = entry.getValue();
            AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                    ID + "_" + type.getConfigKey(),
                    type,
                    value * 100.0D,
                    0L);
        }
    }

    private Map<SkillAttributeType, Double> parseBuffs(Map<String, Object> buffs) {
        Map<SkillAttributeType, Double> parsed = new HashMap<>();
        if (buffs == null || buffs.isEmpty()) {
            return parsed;
        }
        for (var entry : buffs.entrySet()) {
            SkillAttributeType type = SkillAttributeType.fromConfigKey(entry.getKey());
            if (type == null) {
                continue;
            }
            double value = 0.0D;
            Object raw = entry.getValue();
            if (raw instanceof Number number) {
                value = number.doubleValue();
            } else if (raw instanceof Map<?, ?> map) {
                Object nested = map.get("value");
                if (nested instanceof Number number) {
                    value = number.doubleValue();
                }
            }
            if (value != 0.0D) {
                parsed.put(type, value);
            }
        }
        return parsed;
    }

    private double resolveFerocityBonus(EntityStatValue hp) {
        if (hp == null || hp.getMax() <= 0f || maxBonusFerocity <= 0.0D) {
            return 0.0D;
        }
        if (!"missing_health_percent".equals(bonusScalingStat)) {
            return 0.0D;
        }

        double healthRatio = clamp01(hp.get() / hp.getMax());
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
        return maxBonusFerocity * clamp01(normalized);
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private double normalizePercentPoints(double configuredValue) {
        double abs = Math.abs(configuredValue);
        if (abs > 0.0D && abs <= 5.0D) {
            return configuredValue * 100.0D;
        }
        return configuredValue;
    }
}
