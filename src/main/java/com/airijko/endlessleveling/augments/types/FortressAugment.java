package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class FortressAugment extends YamlAugment implements AugmentHooks.OnLowHpAugment {
    public static final String ID = "fortress";

    private final double shieldReduction;
    private final long shieldDuration;
    private final long buffDuration;
    private final double defenseBuff;
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
            return 0f;
        }
        if (state.getStacks() == 1 && now > state.getExpiresAt()) {
            state.setStacks(2);
            state.setExpiresAt(now + buffDuration);
        }
        if (state.getStacks() == 2 && now <= state.getExpiresAt()) {
            float dmg = context.getIncomingDamage();
            return (float) (dmg * (1.0D - Math.max(0.0D, defenseBuff)));
        }
        if (!AugmentUtils.consumeCooldown(runtime, ID, cooldownMillis)) {
            return context.getIncomingDamage();
        }
        // Trigger shield
        state.setStacks(1);
        state.setExpiresAt(now + shieldDuration);
        state.setStoredValue(now + shieldDuration + buffDuration);
        return 0f;
    }
}
