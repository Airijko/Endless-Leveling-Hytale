package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Map;

public final class PhaseRushAugment extends YamlAugment
        implements AugmentHooks.PassiveStatAugment, AugmentHooks.OnHitAugment {
    public static final String ID = "phase_rush";

    private final double baseHasteBonus;
    private final int hitsRequired;
    private final double hasteBurstMultiplier;
    private final long hasteBurstDurationMillis;
    private final double hasteToDamageConversionPercent;

    public PhaseRushAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> hasteNode = AugmentValueReader.getMap(buffs, "haste");
        Map<String, Object> hitCounter = AugmentValueReader.getMap(passives, "hit_counter");
        Map<String, Object> hasteBurst = AugmentValueReader.getMap(passives, "haste_burst");
        Map<String, Object> conversion = AugmentValueReader.getMap(passives, "haste_to_damage_conversion");

        this.baseHasteBonus = Math.max(0.0D, AugmentValueReader.getDouble(hasteNode, "value", 0.0D));
        this.hitsRequired = Math.max(1, AugmentValueReader.getInt(hitCounter, "hits_required", 5));
        this.hasteBurstMultiplier = Math.max(1.0D, AugmentValueReader.getDouble(hasteBurst, "multiplier", 1.0D));
        this.hasteBurstDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(hasteBurst, "duration", 0.0D));
        this.hasteToDamageConversionPercent = Math.max(0.0D,
                AugmentValueReader.getDouble(conversion, "conversion_percent", 0.0D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        var state = context.getRuntimeState().getState(ID);
        boolean burstActive = state.getExpiresAt() > now;
        double hasteBonus = baseHasteBonus * (burstActive ? hasteBurstMultiplier : 1.0D);

        AugmentUtils.setAttributeBonus(context.getRuntimeState(),
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteBonus * 100.0D,
                0L);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        int hits = state.getStacks() + 1;
        if (hits >= hitsRequired) {
            state.setStacks(0);
            if (hasteBurstDurationMillis > 0L) {
                state.setExpiresAt(now + hasteBurstDurationMillis);
            }
            var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getAttackerRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        String.format("%s activated! Haste burst for %.1fs.",
                                getName(),
                                hasteBurstDurationMillis / 1000.0D));
            }
        } else {
            state.setStacks(hits);
        }

        boolean burstActive = state.getExpiresAt() > now;
        double hasteBonus = baseHasteBonus * (burstActive ? hasteBurstMultiplier : 1.0D);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_haste",
                SkillAttributeType.HASTE,
                hasteBonus * 100.0D,
                0L);

        double conversionBonus = resolveHasteConversionBonus(context);
        return AugmentUtils.applyMultiplier(context.getDamage(), conversionBonus);
    }

    private double resolveHasteConversionBonus(AugmentHooks.HitContext context) {
        if (hasteToDamageConversionPercent <= 0.0D
                || context.getSkillManager() == null
                || context.getPlayerData() == null) {
            return 0.0D;
        }

        SkillManager.HasteBreakdown breakdown = context.getSkillManager().getHasteBreakdown(context.getPlayerData());
        double hasteRatio = Math.max(0.0D, breakdown.totalMultiplier() - 1.0D);
        return hasteRatio * hasteToDamageConversionPercent;
    }
}