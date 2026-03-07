package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Map;

public final class FortressAugment extends YamlAugment
        implements AugmentHooks.OnLowHpAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "fortress";

    private final double healthThresholdPercent;
    private final double minHealthHp;
    private final long shieldDuration;
    private final long buffDuration;
    private final double defenseBuff;
    private final double strengthBuff;
    private final double sorceryBuff;
    private final long cooldownMillis;
    private static final float LOCKED_SPEED_MULTIPLIER = 0.0001F;
    private static final float LOCKED_EPSILON = 0.0001F;

    public FortressAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> shield = AugmentValueReader.getMap(passives, "shield_phase");
        Map<String, Object> buff = AugmentValueReader.getMap(passives, "buff_phase");
        this.healthThresholdPercent = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(shield, "health_threshold", 0.05D)));
        this.minHealthHp = Math.max(0.0D,
                AugmentValueReader.getDouble(shield,
                        "min_health_hp",
                        AugmentValueReader.getDouble(shield, "health_threshold_hp", 0.0D)));
        this.shieldDuration = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(shield, "duration", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(shield, "cooldown", 0.0D));
        this.buffDuration = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(buff, "duration", 0.0D));
        this.defenseBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "defense", "value");
        this.strengthBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "strength", "value");
        this.sorceryBuff = AugmentValueReader.getNestedDouble(buff, 0.0D, "buffs", "sorcery", "value");
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        if (context == null) {
            return 0f;
        }
        AugmentRuntimeState runtime = context.getRuntimeState();
        if (runtime == null) {
            return context.getIncomingDamage();
        }

        long now = System.currentTimeMillis();
        var state = runtime.getState(ID);
        if (isShieldActive(state, now)) {
            EntityStatValue activeHp = context.getStatMap() == null
                    ? null
                    : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
            if (activeHp == null || activeHp.getMax() <= 0f) {
                return context.getIncomingDamage();
            }
            float activeThreshold = AugmentUtils.resolveThresholdHp(activeHp.getMax(), minHealthHp,
                    healthThresholdPercent);
            float activeFloor = AugmentUtils.resolveSurvivalFloor(activeHp.getMax(), activeThreshold);
            return AugmentUtils.applyUnkillableThreshold(context.getStatMap(),
                    context.getIncomingDamage(),
                    activeThreshold,
                    activeFloor);
        }

        if (state.getStacks() > 0) {
            state.clear();
            applyMovementLock(context.getDefenderRef(), context.getCommandBuffer(), false);
        }

        EntityStatValue hp = context.getStatMap() == null ? null
                : context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return context.getIncomingDamage();
        }

        float thresholdHp = AugmentUtils.resolveThresholdHp(hp.getMax(), minHealthHp, healthThresholdPercent);
        float survivalFloor = AugmentUtils.resolveSurvivalFloor(hp.getMax(), thresholdHp);
        double projected = hp.get() - context.getIncomingDamage();
        if (projected > thresholdHp) {
            return context.getIncomingDamage();
        }

        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return context.getIncomingDamage();
        }

        state.setStacks(1);
        long shieldExpiresAt = now + Math.max(0L, shieldDuration);
        long buffExpiresAt = now + Math.max(0L, buffDuration);
        state.setExpiresAt(shieldExpiresAt);
        state.setStoredValue(buffExpiresAt);
        float damageToApply = resolveDamageToLeaveFloor(hp.get(), context.getIncomingDamage(), survivalFloor);
        if (damageToApply <= 0f) {
            context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(survivalFloor, hp.get()));
        }

        applyAttributeBonuses(runtime, buffExpiresAt);
        applyMovementLock(context.getDefenderRef(), context.getCommandBuffer(), true);

        PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    Lang.tr(playerRef.getUuid(),
                            "augments.fortress.activated",
                            "{0} activated! Shielded for {1}s, buffs for {2}s.",
                            getName(),
                            shieldDuration / 1000.0D,
                            buffDuration / 1000.0D));
        }

        return damageToApply;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return;
        }
        var state = context.getRuntimeState().getState(ID);
        long now = System.currentTimeMillis();
        boolean shieldActive = isShieldActive(state, now);

        if (shieldActive) {
            applyMovementLock(context.getPlayerRef(), context.getCommandBuffer(), true);
        }

        if (state.getStoredValue() > 0L && now > (long) state.getStoredValue()) {
            clearAttributeBonuses(context.getRuntimeState());
            state.setStoredValue(0.0D);
            PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        Lang.tr(playerRef.getUuid(),
                                "augments.fortress.buff_ended",
                                "{0} buff ended.",
                                getName()));
            }
        }

        if (!shieldActive && state.getStacks() > 0) {
            state.setStacks(0);
            state.setExpiresAt(0L);
            applyMovementLock(context.getPlayerRef(), context.getCommandBuffer(), false);
            PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
            if (playerRef != null && playerRef.isValid()) {
                AugmentUtils.sendAugmentMessage(playerRef,
                        Lang.tr(playerRef.getUuid(),
                                "augments.fortress.shield_ended",
                                "{0} stasis shield ended.",
                                getName()));
            }
        }
    }

    private boolean isShieldActive(AugmentState state, long now) {
        return state != null
                && state.getStacks() == 1
                && state.getExpiresAt() > 0L
                && now <= state.getExpiresAt();
    }

    private float resolveDamageToLeaveFloor(float currentHealth, float incomingDamage, float survivalFloor) {
        float safeCurrent = Math.max(0.0f, currentHealth);
        float safeIncoming = Math.max(0.0f, incomingDamage);
        float safeFloor = Math.max(1.0f, survivalFloor);
        float maxAllowedDamage = Math.max(0.0f, safeCurrent - safeFloor);
        return Math.min(safeIncoming, maxAllowedDamage);
    }

    private void applyAttributeBonuses(AugmentRuntimeState runtime, long expiresAt) {
        if (runtime == null) {
            return;
        }
        long duration = expiresAt > 0L ? Math.max(0L, expiresAt - System.currentTimeMillis()) : 0L;
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_def",
                SkillAttributeType.DEFENSE,
                defenseBuff * 100.0D,
                duration);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_str",
                SkillAttributeType.STRENGTH,
                strengthBuff * 100.0D,
                duration);
        AugmentUtils.setAttributeBonus(runtime,
                ID + "_sorc",
                SkillAttributeType.SORCERY,
                sorceryBuff * 100.0D,
                duration);
    }

    private void clearAttributeBonuses(AugmentRuntimeState runtime) {
        if (runtime == null) {
            return;
        }
        AugmentUtils.setAttributeBonus(runtime, ID + "_def", SkillAttributeType.DEFENSE, 0.0D, 0L);
        AugmentUtils.setAttributeBonus(runtime, ID + "_str", SkillAttributeType.STRENGTH, 0.0D, 0L);
        AugmentUtils.setAttributeBonus(runtime, ID + "_sorc", SkillAttributeType.SORCERY, 0.0D, 0L);
    }

    private void applyMovementLock(
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,
            com.hypixel.hytale.component.CommandBuffer<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> commandBuffer,
            boolean locked) {
        if (ref == null || commandBuffer == null || !ref.isValid()) {
            return;
        }
        MovementManager movementManager = commandBuffer.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, ref);
        if (playerRef == null) {
            return;
        }

        if (!locked) {
            movementManager.resetDefaultsAndUpdate(ref, commandBuffer);
            return;
        }

        MovementSettings settings = movementManager.getSettings();
        if (settings == null) {
            return;
        }
        boolean alreadyLocked = Math
                .abs(settings.forwardWalkSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.backwardWalkSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.strafeWalkSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.forwardRunSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.backwardRunSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.strafeRunSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.forwardCrouchSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.backwardCrouchSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.strafeCrouchSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON
                && Math.abs(settings.forwardSprintSpeedMultiplier - LOCKED_SPEED_MULTIPLIER) <= LOCKED_EPSILON;
        if (alreadyLocked) {
            return;
        }
        settings.forwardWalkSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.backwardWalkSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.strafeWalkSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.forwardRunSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.backwardRunSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.strafeRunSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.forwardCrouchSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.backwardCrouchSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.strafeCrouchSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        settings.forwardSprintSpeedMultiplier = LOCKED_SPEED_MULTIPLIER;
        movementManager.update(playerRef.getPacketHandler());
    }
}
