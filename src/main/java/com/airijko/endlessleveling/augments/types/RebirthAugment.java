package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;

import java.util.Map;

public final class RebirthAugment extends YamlAugment implements AugmentHooks.OnLowHpAugment {
    public static final String ID = "rebirth";

    private final double healPercent;
    private final double minHealthPercent;
    private final long cooldownMillis;

    public RebirthAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_on_trigger");
        this.healPercent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.minHealthPercent = AugmentValueReader.getDouble(heal, "min_health_percent", 0.0D);
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(heal, "cooldown", 0.0D));
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        var hp = context.getStatMap() == null ? null
                : context.getStatMap().get(
                        com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getIncomingDamage();
        }
        double minHp = hp.getMax() * minHealthPercent;
        if (hp.get() - context.getIncomingDamage() > minHp) {
            return context.getIncomingDamage();
        }
        if (!AugmentUtils.isCooldownReady(context.getRuntimeState(), ID, cooldownMillis)) {
            return context.getIncomingDamage();
        }
        double healAmount = hp.getMax() * healPercent;
        context.getStatMap().setStatValue(
                com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth(),
                (float) Math.min(hp.getMax(), hp.get() + healAmount));
        AugmentUtils.markProc(context.getRuntimeState(), ID, cooldownMillis);
        return 0f;
    }
}
