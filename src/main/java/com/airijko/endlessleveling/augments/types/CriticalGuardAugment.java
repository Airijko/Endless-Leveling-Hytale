package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.managers.SkillManager;

import java.util.Map;

public final class CriticalGuardAugment extends YamlAugment implements AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "critical_guard";

    private final double maxReduction;

    public CriticalGuardAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        this.maxReduction = AugmentValueReader.getNestedDouble(buffs, 0.0D, "crit_defense", "value");
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        SkillManager skillManager = context.getSkillManager();
        double critChance = skillManager != null ? skillManager.calculatePlayerPrecision(context.getPlayerData())
                : 0.0D;
        double reduction = Math.max(0.0D, Math.min(maxReduction, maxReduction * critChance));
        float damage = context.getIncomingDamage();
        return (float) (damage * (1.0D - reduction));
    }
}
