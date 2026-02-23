package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class VampiricStrikeAugment extends YamlAugment implements AugmentHooks.OnCritAugment {
    public static final String ID = "vampiric_strike";

    private final double healPercent;
    private final long cooldownMillis;

    public VampiricStrikeAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_on_crit");
        this.healPercent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(heal, "trigger_cooldown", 0.0D));
    }

    @Override
    public void onCrit(AugmentHooks.HitContext context) {
        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, cooldownMillis)) {
            return;
        }
        double healAmount = context.getDamage() * healPercent;
        AugmentUtils.heal(context.getAttackerStats(), healAmount);
    }
}
