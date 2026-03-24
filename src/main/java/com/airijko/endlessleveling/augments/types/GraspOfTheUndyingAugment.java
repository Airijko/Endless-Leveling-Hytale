package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDamageSafety;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Locale;

public final class GraspOfTheUndyingAugment extends Augment
        implements AugmentHooks.OnHitAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "grasp_of_the_undying";
    private static final String MAX_HP_BONUS_KEY = "EL_" + ID + "_max_hp_bonus";
    private static final String LEGACY_MAX_HP_BONUS_KEY = ID + "_max_hp_bonus";

    private static final String[] TRIGGER_VFX_IDS = new String[] {
        "Impact_Blade_01",
        "Explosion_Small"
    };
    private static final String[] TRIGGER_SFX_IDS = new String[] {
        "SFX_Sword_T2_Impact",
        "SFX_Staff_Flame_Fireball_Impact"
    };

    private final double flatDamage;
    private final double maxHealthScaling;
    private final long cooldownMillis;
    private final double flatHealthPerStack;
    private final double healOnHitMaxHealthPercent;
    private final int maxStacks;
    private final long outOfCombatExpireMillis;

    public GraspOfTheUndyingAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> trigger = AugmentValueReader.getMap(passives, "trigger");
        Map<String, Object> damage = AugmentValueReader.getMap(passives, "damage");
        Map<String, Object> stackingHealth = AugmentValueReader.getMap(passives, "stacking_health");

        this.flatDamage = Math.max(0.0D, AugmentValueReader.getDouble(damage, "flat_damage", 25.0D));
        this.maxHealthScaling = AugmentUtils
                .normalizeConfiguredBonusMultiplier(AugmentValueReader.getDouble(damage, "max_health_scaling", 0.025D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(
            AugmentValueReader.getDouble(
                trigger,
                "cooldown",
                AugmentValueReader.getDouble(damage, "cooldown", 2.0D)));

        this.flatHealthPerStack = Math.max(0.0D, AugmentValueReader.getDouble(stackingHealth, "flat_health_per_stack", 25.0D));
        this.healOnHitMaxHealthPercent = AugmentUtils
            .normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getDouble(stackingHealth, "heal_max_health_percent_on_hit", 0.04D));
        this.maxStacks = resolveMaxStacks(stackingHealth.get("max_stacks"));
        this.outOfCombatExpireMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(stackingHealth, "out_of_combat_expire", 10.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null) {
            return 0f;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();
        if (runtime != null) {
            var stackState = runtime.getState(ID);
            int currentStacks = Math.max(0, stackState.getStacks());
            if (maxStacks <= 0 || currentStacks < maxStacks) {
                stackState.setStacks(currentStacks + 1);
            }
            if (outOfCombatExpireMillis > 0L) {
                stackState.setExpiresAt(now + outOfCombatExpireMillis);
            }
        }

        EntityStatMap attackerStats = context.getAttackerStats();
        double maxHealth = AugmentUtils.getMaxHealth(attackerStats);
        if (attackerStats != null) {
            double healAmount = 0.0D;
            if (flatHealthPerStack > 0.0D) {
                healAmount += flatHealthPerStack;
            }
            if (healOnHitMaxHealthPercent > 0.0D && maxHealth > 0.0D) {
                healAmount += maxHealth * healOnHitMaxHealthPercent;
            }

            if (healAmount > 0.0D) {
                AugmentUtils.heal(attackerStats, healAmount);
            }
        }

        double procDamage = flatDamage
                + (maxHealth * maxHealthScaling);
        if (procDamage <= 0.0D) {
            return context.getDamage();
        }

        if (context.getCommandBuffer() != null && context.getTargetRef() != null && EntityRefUtil.isUsable(context.getTargetRef())) {
            playTriggerVfx(context.getCommandBuffer(), context.getTargetRef());
            playTriggerSfx(context.getCommandBuffer(), context.getTargetRef());
            Damage proc = PlayerCombatSystem.createAugmentProcDamage(context.getAttackerRef(), (float) procDamage);
            AugmentDamageSafety.tryExecuteDamage(context.getTargetRef(), context.getCommandBuffer(), proc, ID);
            return context.getDamage();
        }

        return context.getDamage() + (float) procDamage;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }

        AugmentRuntimeState runtime = context.getRuntimeState();
        var state = runtime.getState(ID);
        long now = System.currentTimeMillis();

        refreshStackExpiryWhileInCombat(context, state, now);

        if (outOfCombatExpireMillis > 0L && state.getStacks() > 0 && state.getExpiresAt() > 0L && now >= state.getExpiresAt()) {
            state.setStacks(0);
            state.setExpiresAt(0L);
        }

        applyMaxHealthBonus(context.getStatMap(), state.getStacks());
    }

    private void applyMaxHealthBonus(EntityStatMap statMap, int stacks) {
        if (statMap == null) {
            return;
        }

        EntityStatValue hpBefore = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpBefore == null || hpBefore.getMax() <= 0f) {
            return;
        }

        float previousMax = hpBefore.getMax();
        float previousCurrent = hpBefore.get();

        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), MAX_HP_BONUS_KEY);
        statMap.removeModifier(DefaultEntityStatTypes.getHealth(), LEGACY_MAX_HP_BONUS_KEY);
        statMap.update();

        int safeStacks = maxStacks > 0
            ? Math.max(0, Math.min(maxStacks, stacks))
            : Math.max(0, stacks);
        double totalBonus = flatHealthPerStack * safeStacks;
        if (Math.abs(totalBonus) > 0.0001D) {
            statMap.putModifier(DefaultEntityStatTypes.getHealth(),
                    MAX_HP_BONUS_KEY,
                    new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, (float) totalBonus));
            statMap.update();
        }

        EntityStatValue hpUpdated = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hpUpdated == null || hpUpdated.getMax() <= 0f) {
            return;
        }

        float newMax = hpUpdated.getMax();
        float ratio = previousMax > 0.01f ? previousCurrent / previousMax : 1.0f;
        float adjustedCurrent = Math.max(1.0f, Math.min(newMax, ratio * newMax));
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), adjustedCurrent);
    }

    private void refreshStackExpiryWhileInCombat(AugmentHooks.PassiveStatContext context,
            com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState state,
            long now) {
        if (context == null || state == null || state.getStacks() <= 0 || outOfCombatExpireMillis <= 0L) {
            return;
        }

        long lastCombatMillis = resolveLastCombatMillis(context);
        if (lastCombatMillis <= 0L) {
            return;
        }

        if (now - lastCombatMillis <= outOfCombatExpireMillis) {
            state.setExpiresAt(now + outOfCombatExpireMillis);
        }
    }

    private long resolveLastCombatMillis(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getPlayerData() == null || context.getPlayerData().getUuid() == null) {
            return 0L;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        PassiveManager passiveManager = plugin == null ? null : plugin.getPassiveManager();
        if (passiveManager == null) {
            return 0L;
        }

        var passiveState = passiveManager.getRuntimeState(context.getPlayerData().getUuid());
        return passiveState == null ? 0L : passiveState.getLastCombatMillis();
    }

    private int resolveMaxStacks(Object rawValue) {
        if (rawValue == null) {
            return 0;
        }
        if (rawValue instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (rawValue instanceof String string) {
            String normalized = string.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()
                    || "ENDLESS".equals(normalized)
                    || "INFINITE".equals(normalized)
                    || "UNCAPPED".equals(normalized)
                    || "NONE".equals(normalized)) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(normalized));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void playTriggerVfx(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> targetRef) {
        Vector3d position = resolveTargetEffectPosition(commandBuffer, targetRef);
        if (position == null || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        for (String vfxId : TRIGGER_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(vfxId, position, targetRef.getStore());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void playTriggerSfx(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> targetRef) {
        Vector3d position = resolveTargetEffectPosition(commandBuffer, targetRef);
        if (position == null || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        for (String soundId : TRIGGER_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            SoundUtil.playSoundEvent3d(null, soundIndex, position, targetRef.getStore());
            return;
        }
    }

    private Vector3d resolveTargetEffectPosition(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> targetRef) {
        if (commandBuffer == null || !EntityRefUtil.isUsable(targetRef)) {
            return null;
        }

        TransformComponent targetTransform = EntityRefUtil.tryGetComponent(
                commandBuffer,
                targetRef,
                TransformComponent.getComponentType());
        if (targetTransform == null || targetTransform.getPosition() == null) {
            return null;
        }

        Vector3d baseTargetPosition = targetTransform.getPosition();
        return new Vector3d(baseTargetPosition.getX(), baseTargetPosition.getY() + 0.8D, baseTargetPosition.getZ());
    }

    private int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }
}
