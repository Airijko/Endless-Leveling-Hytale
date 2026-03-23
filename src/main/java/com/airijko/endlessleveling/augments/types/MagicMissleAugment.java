package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDamageSafety;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

import java.util.Map;

public final class MagicMissleAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "magic_missle";

    private final double flatDamage;
    private final double sorceryScaling;
    private final long cooldownMillis;

    public MagicMissleAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> missileDamage = AugmentValueReader.getMap(passives, "missile_damage");

        this.flatDamage = Math.max(0.0D, AugmentValueReader.getDouble(missileDamage, "flat_damage", 0.0D));
        this.sorceryScaling = Math.max(0.0D, AugmentValueReader.getDouble(missileDamage, "sorcery_scaling", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(missileDamage, "cooldown", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        double sorcery = AugmentUtils.resolveSorcery(context);

        double missileDamage = flatDamage + (sorcery * sorceryScaling);
        if (missileDamage <= 0.0D) {
            return context.getDamage();
        }

        if (context.getCommandBuffer() != null && context.getTargetRef() != null
                && EntityRefUtil.isUsable(context.getTargetRef())) {
            Damage proc = PlayerCombatSystem.createAugmentProcDamage(context.getAttackerRef(), (float) missileDamage);
            AugmentDamageSafety.tryExecuteDamage(context.getTargetRef(), context.getCommandBuffer(), proc, ID);
            return context.getDamage();
        }

        return context.getDamage() + (float) missileDamage;
    }
}
