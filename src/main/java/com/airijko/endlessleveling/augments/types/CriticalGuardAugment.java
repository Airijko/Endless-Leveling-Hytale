package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class CriticalGuardAugment extends YamlAugment implements AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "critical_guard";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final double maxReduction;

    public CriticalGuardAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> critDefense = AugmentValueReader.getMap(passives, "crit_defense");
        if (critDefense.isEmpty()) {
            Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
            critDefense = AugmentValueReader.getMap(buffs, "crit_defense");
        }
        this.maxReduction = AugmentValueReader.getDouble(critDefense, "value", 0.0D);
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        float incomingDamage = context.getIncomingDamage();
        if (incomingDamage <= 0f) {
            return incomingDamage;
        }

        SkillManager skillManager = context.getSkillManager();
        double critChance = skillManager != null ? skillManager.calculatePlayerPrecision(context.getPlayerData())
                : 0.0D;
        critChance = Math.max(0.0D, Math.min(1.0D, critChance));

        double roll = ThreadLocalRandom.current().nextDouble();
        boolean guardCrit = roll <= critChance;
        double reduction = guardCrit ? Math.max(0.0D, Math.min(maxReduction, maxReduction * critChance)) : 0.0D;
        float outgoingDamage = (float) (incomingDamage * (1.0D - reduction));

        LOGGER.atFine().log(
                "Critical Guard: player=%s incoming=%.2f critChance=%.2f%% roll=%.4f guardCrit=%s reduction=%.2f%% outgoing=%.2f",
                context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown",
                incomingDamage,
                critChance * 100.0D,
                roll,
                guardCrit,
                reduction * 100.0D,
                outgoingDamage);

        return outgoingDamage;
    }
}
