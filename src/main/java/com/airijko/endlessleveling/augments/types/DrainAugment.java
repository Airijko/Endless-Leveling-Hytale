package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;

public final class DrainAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "drain";

    private final double percent;
    private final long cooldownMillis;
    private final double maxDamagePerTick;

    public DrainAugment(AugmentDefinition definition) {
        super(definition);
        var passives = definition.getPassives();
        var bonusDamage = AugmentValueReader.getMap(passives, "bonus_damage_on_hit");
        this.percent = AugmentValueReader.getNestedDouble(passives, 0.0D, "bonus_damage_on_hit", "value") * 100.0D;
        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getNestedDouble(passives, 0.0D, "bonus_damage_on_hit", "cooldown"));
        this.maxDamagePerTick = Math.max(0.0D, AugmentValueReader.getDouble(bonusDamage, "max_damage_per_tick", 0.0D));
    }

    public double getPercent() {
        return percent;
    }

    public static double bonusDamage(EntityStatMap targetStats, double percent) {
        if (targetStats == null || percent <= 0.0D) {
            return 0.0D;
        }
        var health = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return 0.0D;
        }
        return Math.max(0.0D, health.get() * (percent / 100.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        var runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        if (cooldownMillis > 0L && state.getLastProc() > 0L && now - state.getLastProc() < cooldownMillis) {
            return context.getDamage();
        }

        double extra = bonusDamage(context.getTargetStats(), getPercent());
        if (extra <= 0.0D) {
            return context.getDamage();
        }

        PlayerRef targetPlayer = context.getCommandBuffer() == null || context.getTargetRef() == null
                ? null
                : AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getTargetRef());
        boolean targetIsMonster = targetPlayer == null || !targetPlayer.isValid();
        if (targetIsMonster && maxDamagePerTick > 0.0D && extra > maxDamagePerTick) {
            extra = maxDamagePerTick;
        }

        // Drain is a one-time proc hit, so it should use the augment proc damage path.
        if (context.getCommandBuffer() != null && context.getTargetRef() != null
                && EntityRefUtil.isUsable(context.getTargetRef())) {
            DamageSystems.executeDamage(
                    context.getTargetRef(),
                    context.getCommandBuffer(),
                    PlayerCombatSystem.createAugmentProcDamage(context.getAttackerRef(), (float) extra));
            state.setLastProc(now);
            return context.getDamage();
        }

        return context.getDamage();
    }
}
