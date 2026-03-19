package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

public final class BailoutAugment extends YamlAugment
        implements AugmentHooks.OnLowHpAugment, AugmentHooks.PassiveStatAugment, AugmentHooks.OnKillAugment {
    public static final String ID = "bailout";
    private static final double TRIGGER_VFX_Y_OFFSET = 1.0D;
    private static final String[] TRIGGER_VFX_IDS = new String[] {
        "GreenOrbImpact",
        "GreenOrbTrail"
    };
    private static final String[] TRIGGER_SFX_IDS = new String[] {
        "SFX_Skeleton_Mage_Spellbook_Impact",
        "SFX_Skeleton_Mage_Spellbook_Charge"
    };

    private final long cooldownMillis;
    private final double reviveHealthPercent;
    private final double drainMaxHpPercentPerSecond;
    private final boolean drainsToZero;
    private final boolean cancelOnKill;
    private final double emergencyHealMissingPercent;

    public BailoutAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> deathPrevention = AugmentValueReader.getMap(passives, "death_prevention");
        Map<String, Object> healthDecay = AugmentValueReader.getMap(passives, "health_decay");
        Map<String, Object> emergencyHeal = AugmentValueReader.getMap(passives, "emergency_heal");

        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(deathPrevention, "cooldown", 0.0D));
        this.reviveHealthPercent = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(deathPrevention, "revive_health_percent", 0.0D)));

        this.drainMaxHpPercentPerSecond = resolveDrainRatePerSecond(healthDecay);
        this.drainsToZero = AugmentValueReader.getBoolean(healthDecay, "drains_to_zero", true);
        this.cancelOnKill = AugmentValueReader.getBoolean(healthDecay, "cancel_on_kill", true);

        this.emergencyHealMissingPercent = Math.max(0.0D,
                Math.min(1.0D, AugmentValueReader.getDouble(emergencyHeal, "missing_health_percent", 0.0D)));
    }

    @Override
    public float onLowHp(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getIncomingDamage() <= 0f) {
            return context != null ? context.getIncomingDamage() : 0f;
        }
        EntityStatMap statMap = context.getStatMap();
        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
            return context.getIncomingDamage();
        }

        // 1 HP or lower.
        float projectedHp = hp.get() - context.getIncomingDamage();
        if (projectedHp > 1.0f) {
            return context.getIncomingDamage();
        }

        if (!AugmentUtils.consumeCooldown(context.getRuntimeState(), ID, getName(), cooldownMillis)) {
            return context.getIncomingDamage();
        }

        AugmentUtils.applyUnkillableThreshold(statMap, context.getIncomingDamage(), 1.0f, 1.0f);
        float revivedHealth = (float) (hp.getMax() * reviveHealthPercent);
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1.0f, revivedHealth));
        playTriggerSound(context);
        playTriggerVfx(context);

        AugmentState state = context.getRuntimeState() != null ? context.getRuntimeState().getState(ID) : null;
        if (state != null) {
            if (drainsToZero && drainMaxHpPercentPerSecond > 0.0D) {
                state.setStacks(1);
                state.setStoredValue(drainMaxHpPercentPerSecond);
                state.setExpiresAt(0L);
            } else {
                clearDecayState(state);
            }
        }

        var playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    String.format("%s triggered! Revived at %.0f%% health. Losing %.0f%% max HP/s until kill or death.",
                            getName(),
                            reviveHealthPercent * 100.0D,
                            drainMaxHpPercentPerSecond * 100.0D));
        }

        return 0f;
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getRuntimeState() == null || context.getStatMap() == null) {
            return;
        }
        AugmentState state = context.getRuntimeState().getState(ID);
        if (state.getStacks() <= 0 || !drainsToZero || drainMaxHpPercentPerSecond <= 0.0D) {
            return;
        }

        EntityStatValue hp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.get() <= 0f) {
            executePlayerDeath(context);
            clearDecayState(state);
            return;
        }

        double deltaSeconds = Math.max(0.0D, context.getDeltaSeconds());
        if (deltaSeconds <= 0.0D) {
            return;
        }

        if (hp.getMax() <= 0f) {
            state.clear();
            return;
        }

        double activeDrainRate = state.getStoredValue() > 0.0D
                ? Math.min(1.0D, state.getStoredValue())
                : drainMaxHpPercentPerSecond;

        float currentHp = hp.get();
        double plannedDrain = hp.getMax() * activeDrainRate * deltaSeconds;
        float appliedDrain = (float) Math.min(currentHp, plannedDrain);
        if (appliedDrain <= 0f) {
            return;
        }

        float newHp = Math.max(0f, currentHp - appliedDrain);
        context.getStatMap().setStatValue(DefaultEntityStatTypes.getHealth(), newHp);
        if (newHp <= 0.0001f) {
            executePlayerDeath(context);
            clearDecayState(state);
        }
    }

    private void playTriggerVfx(AugmentHooks.DamageTakenContext context) {
        Ref<EntityStore> defenderRef = context != null ? context.getDefenderRef() : null;
        Vector3d effectPosition = resolveEffectPosition(context, defenderRef);
        if (effectPosition == null || defenderRef == null) {
            return;
        }

        for (String vfxId : TRIGGER_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(vfxId, effectPosition, defenderRef.getStore());
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void playTriggerSound(AugmentHooks.DamageTakenContext context) {
        Ref<EntityStore> defenderRef = context != null ? context.getDefenderRef() : null;
        Vector3d effectPosition = resolveEffectPosition(context, defenderRef);
        if (effectPosition == null || defenderRef == null) {
            return;
        }

        for (String soundId : TRIGGER_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            SoundUtil.playSoundEvent3d(null, soundIndex, effectPosition, defenderRef.getStore());
            return;
        }
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private Vector3d resolveEffectPosition(AugmentHooks.DamageTakenContext context, Ref<EntityStore> ref) {
        if (context == null || context.getCommandBuffer() == null || !EntityRefUtil.isUsable(ref)) {
            return null;
        }
        TransformComponent transform = EntityRefUtil.tryGetComponent(context.getCommandBuffer(),
                ref,
                TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        Vector3d position = transform.getPosition();
        return new Vector3d(position.getX(), position.getY() + TRIGGER_VFX_Y_OFFSET, position.getZ());
    }

    private void clearDecayState(AugmentState state) {
        if (state == null) {
            return;
        }
        // Preserve lastProc so Bailout still respects the configured cooldown.
        state.setStacks(0);
        state.setStoredValue(0.0D);
        state.setExpiresAt(0L);
    }

    private void executePlayerDeath(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getCommandBuffer() == null || context.getPlayerRef() == null
                || !context.getPlayerRef().isValid()) {
            return;
        }

        if (context.getCommandBuffer().getComponent(context.getPlayerRef(),
                DeathComponent.getComponentType()) != null) {
            return;
        }

        DeathComponent.tryAddComponent(
                context.getCommandBuffer(),
                context.getPlayerRef(),
                new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE));
    }

    private double resolveDrainRatePerSecond(Map<String, Object> healthDecay) {
        double configured = AugmentValueReader.getDouble(healthDecay,
                "drain_max_hp_percent_per_second",
                -1.0D);

        if (configured <= 0.0D) {
            configured = AugmentValueReader.getDouble(healthDecay,
                    "drain_percent_per_second",
                    -1.0D);
        }

        // Legacy fallback: duration means "drain revived HP to zero over N seconds".
        if (configured <= 0.0D) {
            double legacyDuration = AugmentValueReader.getDouble(healthDecay, "duration", 0.0D);
            if (legacyDuration > 0.0D) {
                configured = reviveHealthPercent / legacyDuration;
            }
        }

        // Accept either decimal ratio (0.25) or percent points (25).
        if (configured > 1.0D && configured <= 100.0D) {
            configured = configured / 100.0D;
        }

        return Math.max(0.0D, Math.min(1.0D, configured));
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        if (!cancelOnKill || context == null || context.getRuntimeState() == null) {
            return;
        }
        AugmentState state = context.getRuntimeState().getState(ID);
        if (state.getStacks() <= 0) {
            return;
        }

        clearDecayState(state);

        if (emergencyHealMissingPercent <= 0.0D || context.getCommandBuffer() == null
                || context.getKillerRef() == null) {
            return;
        }

        EntityStatMap killerStats = context.getCommandBuffer().getComponent(context.getKillerRef(),
                EntityStatMap.getComponentType());
        EntityStatValue hp = killerStats == null ? null : killerStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return;
        }

        double missing = Math.max(0.0D, hp.getMax() - hp.get());
        if (missing <= 0.0D) {
            return;
        }
        AugmentUtils.heal(killerStats, missing * emergencyHealMissingPercent);
    }
}
