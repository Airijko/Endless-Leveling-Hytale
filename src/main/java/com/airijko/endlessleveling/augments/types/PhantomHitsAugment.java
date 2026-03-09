package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class PhantomHitsAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    public static final String ID = "phantom_hits";
    private static final long INTERNAL_COOLDOWN_MILLIS = 400L;

    private final double flatDamage;
    private final double strengthScaling;
    private final double sorceryScaling;
    private final boolean canCrit;

    public PhantomHitsAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> phantomDamage = AugmentValueReader.getMap(passives, "phantom_damage");

        this.flatDamage = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "flat_damage", 0.0D));
        this.strengthScaling = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "strength_scaling", 0.0D));
        this.sorceryScaling = Math.max(0.0D, AugmentValueReader.getDouble(phantomDamage, "sorcery_scaling", 0.0D));
        this.canCrit = AugmentValueReader.getBoolean(phantomDamage, "can_crit", false);
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        AugmentState state = null;
        if (context.getRuntimeState() != null) {
            state = context.getRuntimeState().getState(ID);
            long now = System.currentTimeMillis();
            if (state.getLastProc() > 0L && now - state.getLastProc() < INTERNAL_COOLDOWN_MILLIS) {
                return context.getDamage();
            }
        }

        SkillManager skillManager = context.getSkillManager();
        if (skillManager == null || context.getPlayerData() == null) {
            return context.getDamage();
        }

        double strength = skillManager.calculatePlayerStrength(context.getPlayerData());
        double sorcery = skillManager.calculatePlayerSorcery(context.getPlayerData());
        double phantomDamage = flatDamage + (strength * strengthScaling) + (sorcery * sorceryScaling);

        if (phantomDamage <= 0.0D) {
            return context.getDamage();
        }

        if (canCrit) {
            double critChance = Math.max(0.0D,
                    Math.min(1.0D, skillManager.calculatePlayerPrecision(context.getPlayerData())));
            if (ThreadLocalRandom.current().nextDouble() <= critChance) {
                double ferocity = skillManager.calculatePlayerFerocity(context.getPlayerData());
                phantomDamage *= 1.0D + (ferocity / 100.0D);
            }
        }

        if (state != null) {
            state.setLastProc(System.currentTimeMillis());
        }

        return context.getDamage() + (float) phantomDamage;
    }
}
