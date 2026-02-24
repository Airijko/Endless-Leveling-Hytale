package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class FortressAugment extends YamlAugment implements AugmentHooks.OnLowHpAugment {
    public static final String ID = "fortress";

    private final double shieldReduction;
    private final long shieldDuration;
    private final long buffDuration;
    private final double defenseBuff;
    private final double strengthBuff;
    private final double sorceryBuff;
    private final long cooldownMillis;

    public FortressAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> shield = AugmentValueReader.getMap(passives, "shield_phase");
        Map<String, Object> buff = AugmentValueReader.getMap(passives, "buff_phase");
        this.shieldReduction = AugmentValueReader.getDouble(shield, "value", 0.0D);
        this.shieldDuration = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(shield, "duration", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(shield, "cooldown", 0.0D));
        this.buffDuration = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(buff, "duration", 0.0D));
        this.defenseBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "defense", "value");
        this.strengthBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "strength", "value");
        this.sorceryBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "sorcery", "value");
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        EntityStatValue hp = context.getStatMap() == null ? null
                : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getIncomingDamage();
        }
        double projected = hp.get() - context.getIncomingDamage();
        if (projected > 0.0D && projected / hp.getMax() > 0.05D) {
            return context.getIncomingDamage();
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getIncomingDamage();
        }
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();
        // Stage handling: stacks==1 -> shield active, storedValue= buffEndMillis
        if (state.getStacks() == 1 && now <= state.getExpiresAt()) {
            applyAttributeBonuses(runtime, 1, state.getExpiresAt());
            double reduction = Math.max(0.0D, shieldReduction);
            double multiplier = Math.max(0.0D, 1.0D - reduction);
            return (float) (context.getIncomingDamage() * multiplier);
        }
        if (state.getStacks() == 1 && now > state.getExpiresAt()) {
            state.setStacks(2);
            long buffExpiresAt = now + buffDuration;
            state.setExpiresAt(buffExpiresAt);
            applyAttributeBonuses(runtime, 2, buffExpiresAt);
        }
        if (state.getStacks() == 2 && now <= state.getExpiresAt()) {
            applyAttributeBonuses(runtime, 2, state.getExpiresAt());
            float dmg = context.getIncomingDamage();
            return (float) (dmg * (1.0D - Math.max(0.0D, defenseBuff)));
        }
        if (!AugmentUtils.consumeCooldown(runtime, ID, cooldownMillis)) {
            applyAttributeBonuses(runtime, 0, 0L);
            return context.getIncomingDamage();
        }
        // Trigger shield
        state.setStacks(1);
        long shieldExpiresAt = now + shieldDuration;
        state.setExpiresAt(shieldExpiresAt);
        state.setStoredValue(shieldExpiresAt + buffDuration);
        applyAttributeBonuses(runtime, 1, shieldExpiresAt);
        double reduction = Math.max(0.0D, shieldReduction);
        double multiplier = Math.max(0.0D, 1.0D - reduction);
        return (float) (context.getIncomingDamage() * multiplier);
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, int stage, long expiresAt) {
        if (runtime == null) {
            return;
        }
        long duration = expiresAt > 0L ? Math.max(0L, expiresAt - System.currentTimeMillis()) : 0L;
        if (stage == 2) {
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_def",
                    SkillAttributeType.DEFENSE,
                    defenseBuff * 100.0D,
                    duration);
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_str",
                    SkillAttributeType.STRENGTH,
                    strengthBuff * 100.0D,
                    duration);
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_sorc",
                    SkillAttributeType.SORCERY,
                    sorceryBuff * 100.0D,
                    duration);
            return;
        }

        // Clear stage-specific bonuses when shielded or idle.
        AugmentUtils.setAttributeBonus(runtime, ID + "_def", SkillAttributeType.DEFENSE, 0.0D, 0L);
        AugmentUtils.setAttributeBonus(runtime, ID + "_str", SkillAttributeType.STRENGTH, 0.0D, 0L);
        AugmentUtils.setAttributeBonus(runtime, ID + "_sorc", SkillAttributeType.SORCERY, 0.0D, 0L);
    }
}
