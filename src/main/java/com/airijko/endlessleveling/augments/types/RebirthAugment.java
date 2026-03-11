package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

import java.util.Map;

public final class RebirthAugment extends YamlAugment implements AugmentHooks.OnLowHpAugment {
    public static final String ID = "rebirth";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final double healPercent;
    private final double minHealthPercent;
    private final double minHealthHp;
    private final long cooldownMillis;

    public RebirthAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> heal = AugmentValueReader.getMap(passives, "heal_on_trigger");
        this.healPercent = AugmentValueReader.getDouble(heal, "value", 0.0D);
        this.minHealthPercent = AugmentValueReader.getDouble(heal, "min_health_percent", 0.0D);
        this.minHealthHp = Math.max(0.0D,
                AugmentValueReader.getDouble(heal,
                        "min_health_hp",
                        AugmentValueReader.getDouble(heal, "health_threshold_hp", 0.0D)));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(heal, "cooldown", 0.0D));
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        if (context == null) {
            LOGGER.atFine().log("[AUGMENT REBIRTH] Context is null");
            return 0f;
        }
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            LOGGER.atFine().log("[AUGMENT REBIRTH] Runtime state is null");
            return context.getIncomingDamage();
        }
        var hp = context.getStatMap() == null ? null
                : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            LOGGER.atFine().log("[AUGMENT REBIRTH] Health stat is null or max <= 0");
            return context.getIncomingDamage();
        }

        float thresholdHp = AugmentUtils.resolveThresholdHp(hp.getMax(), minHealthHp, minHealthPercent);
        float survivalFloor = AugmentUtils.resolveSurvivalFloor(hp.getMax(), thresholdHp);
        double projectedHp = hp.get() - context.getIncomingDamage();
        LOGGER.atFine().log("[AUGMENT REBIRTH] Current HP: %.3f, Proj HP: %.3f, Threshold: %.3f, Damage: %.3f",
                hp.get(), projectedHp, thresholdHp, context.getIncomingDamage());
        if (projectedHp > thresholdHp) {
            LOGGER.atFine().log("[AUGMENT REBIRTH] HP above threshold, not triggering");
            return context.getIncomingDamage();
        }

        LOGGER.atFine().log("[AUGMENT REBIRTH] Low HP detected! Checking cooldown...");
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            LOGGER.atFine().log("[AUGMENT REBIRTH] Cooldown not ready");
            return context.getIncomingDamage();
        }

        LOGGER.atInfo().log("[AUGMENT REBIRTH] ACTIVATING! Healing by %.2f%%", healPercent * 100.0D);
        AugmentUtils.applyUnkillableThreshold(context.getStatMap(),
                context.getIncomingDamage(),
                thresholdHp,
                survivalFloor);
        double healAmount = hp.getMax() * healPercent;
        float healed = (float) Math.min(hp.getMax(), hp.get() + healAmount);
        context.getStatMap().setStatValue(
                DefaultEntityStatTypes.getHealth(),
                Math.max(survivalFloor, healed));
        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.rebirth.activated",
                            "{0} activated! Restored {1}% of max health.",
                            getName(), healPercent * 100.0D));
        }
        return 0f;
    }
}
