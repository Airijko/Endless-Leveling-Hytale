package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.Augment;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BurnAugment extends Augment
        implements AugmentHooks.OnDamageTakenAugment, AugmentHooks.PassiveStatAugment {
    public static final String ID = "burn";
    private static final String[] BURN_EFFECT_IDS = new String[] { "burning", "Burning", "Burn" };
    private static final float BURN_EFFECT_DURATION_SECONDS = 1.25F;
    private static final String[] PULSE_RING_VFX_IDS = new String[] { "Impact_Critical", "Impact_Blade_01" };
    private static final String[] PULSE_RING_SFX_IDS = new String[] {
            "SFX_Effect_Burn_World",
            "SFX_Staff_Flame_Fireball_Impact"
    };
    private static final long PULSE_RING_DURATION_MILLIS = 250L;
    private static final long PULSE_RING_STEP_MILLIS = 50L;
    private static final int PULSE_RING_MIN_POINT_COUNT = 10;
    private static final int PULSE_RING_MAX_POINT_COUNT = 36;
    private static final int PULSE_RING_MIN_LAYER_COUNT = 2;
    private static final int PULSE_RING_MAX_LAYER_COUNT = 4;
    private static final double PULSE_RING_START_RADIUS = 0.1D;
    private static final double PULSE_RING_MIN_LAYER_SPACING = 0.2D;
    private static final double PULSE_RING_MAX_LAYER_SPACING = 0.45D;
    private static final double PULSE_RING_Y_OFFSET = 0.3D;
    private static final Map<String, ActivePulse> ACTIVE_PULSES = new ConcurrentHashMap<>();

    private final double basePercentPerSecond;
    private final double bonusScalingPer100Health;
    private final double baseRadius;
    private final double healthPerRadiusBlock;
    private final long activeDurationMillis;
    private final long cooldownMillis;
    private final double lifeForceFlatBonus;
    private final double maxDamagePerTick;

    private static final class ActivePulse {
        Ref<EntityStore> sourceRef;
        long startedAt;
        long expiresAt;
        long lastVisualAt;
        double endRadius;
        boolean soundPlayed;
    }

    public BurnAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> auraBurn = AugmentValueReader.getMap(passives, "aura_burn");
        Map<String, Object> radiusScaling = AugmentValueReader.getMap(auraBurn, "radius_health_scaling");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> lifeForce = AugmentValueReader.getMap(buffs, "life_force");

        this.basePercentPerSecond = Math.max(0.0D,
                AugmentValueReader.getDouble(auraBurn, "base_max_hp_percent_per_second", 0.0D));
        this.bonusScalingPer100Health = Math.max(0.0D,
                AugmentValueReader.getDouble(auraBurn, "bonus_max_hp_scaling", 0.0D));
        this.baseRadius = Math.max(0.0D, AugmentValueReader.getDouble(auraBurn, "radius", 0.0D));
        this.healthPerRadiusBlock = Math.max(0.0D,
                AugmentValueReader.getDouble(radiusScaling, "health_per_block", 0.0D));
        this.activeDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(auraBurn, "duration", 0.0D));
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(auraBurn, "cooldown", 0.0D));
        this.lifeForceFlatBonus = Math.max(0.0D, AugmentValueReader.getDouble(lifeForce, "value", 0.0D));
        this.maxDamagePerTick = Math.max(0.0D, AugmentValueReader.getDouble(auraBurn, "max_damage_per_tick", 0.0D));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null || activeDurationMillis <= 0L) {
            return context != null ? context.getIncomingDamage() : 0f;
        }

        AugmentRuntimeState runtimeState = context.getRuntimeState();
        var state = runtimeState.getState(ID);
        long now = System.currentTimeMillis();
        if (state.getExpiresAt() > now) {
            return context.getIncomingDamage();
        }

        long cooldownEndsAt = (long) state.getStoredValue();
        if (cooldownEndsAt > now) {
            return context.getIncomingDamage();
        }

        long activeUntil = now + activeDurationMillis;
        long combinedCooldownEnd = cooldownMillis > 0L ? activeUntil + cooldownMillis : activeUntil;
        state.setExpiresAt(activeUntil);
        state.setStoredValue(combinedCooldownEnd);
        state.setStacks(1);
        state.setLastProc(0L);
        if (combinedCooldownEnd > now) {
            runtimeState.setCooldown(ID, getName(), combinedCooldownEnd);
        }

        PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getDefenderRef());
        if (playerRef != null && playerRef.isValid()) {
            AugmentUtils.sendAugmentMessage(playerRef,
                    String.format("%s activated for %.1fs.", getName(), activeDurationMillis / 1000.0D));
        }

        return context.getIncomingDamage();
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getCommandBuffer() == null || context.getPlayerRef() == null
                || context.getStatMap() == null || context.getRuntimeState() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        var burnState = context.getRuntimeState().getState(ID);
        boolean active = burnState.getExpiresAt() > now;
        if (!active) {
            clearPulse(context.getPlayerRef(), context.getCommandBuffer());
            if (burnState.getStacks() > 0) {
                PlayerRef playerRef = AugmentUtils.getPlayerRef(context.getCommandBuffer(), context.getPlayerRef());
                if (playerRef != null && playerRef.isValid()) {
                    AugmentUtils.sendAugmentMessage(playerRef,
                            String.format("%s expired.", getName()));
                }
                burnState.setStacks(0);
            }
            AugmentUtils.setAttributeBonus(context.getRuntimeState(), ID + "_life_force", SkillAttributeType.LIFE_FORCE,
                    0.0D, 0L);
            return;
        }

        context.getRuntimeState().setAttributeBonus(
                SkillAttributeType.LIFE_FORCE,
                ID + "_life_force",
                lifeForceFlatBonus,
                0L);

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> sourceRef = context.getPlayerRef();
        updatePulse(sourceRef, commandBuffer, now);

        // Gate aura damage to once per second
        if (burnState.getLastProc() > 0L && now - burnState.getLastProc() < 1000L) {
            return;
        }

        EntityStatValue sourceHp = context.getStatMap().get(DefaultEntityStatTypes.getHealth());
        if (sourceHp == null || sourceHp.getMax() <= 0f || sourceHp.get() <= 0f) {
            return;
        }

        double maxHealth = Math.max(0.0D, sourceHp.getMax());
        double radius = resolveRadius(maxHealth);
        if (radius <= 0.0D) {
            return;
        }

        double percentPerSecond = basePercentPerSecond + ((maxHealth / 100.0D) * bonusScalingPer100Health);
        if (percentPerSecond <= 0.0D) {
            return;
        }

        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            return;
        }

        burnState.setLastProc(now);
        double ratioThisTick = percentPerSecond;
        startPulse(sourceRef, commandBuffer, now, radius);
        EntityEffect burnEffect = resolveBurnEffect();
        UUID sourceUuid = resolveSourceUuid(context, sourceRef, commandBuffer);
        PartyManager partyManager = resolvePartyManager();
        UUID sourcePartyLeader = resolvePartyLeader(partyManager, sourceUuid);

        HashSet<Integer> visitedEntityIds = new HashSet<>();
        for (Ref<EntityStore> targetRef : TargetUtil.getAllEntitiesInSphere(
                sourceTransform.getPosition(),
                radius,
                commandBuffer)) {
            if (targetRef == null || !targetRef.isValid()) {
                continue;
            }
            if (!visitedEntityIds.add(targetRef.getIndex())) {
                continue;
            }
            if (targetRef.equals(sourceRef)) {
                continue;
            }
            if (isFriendlyPetTarget(sourceUuid, sourcePartyLeader, targetRef, commandBuffer, partyManager)) {
                continue;
            }
            if (isSamePartyTarget(sourceUuid, sourcePartyLeader, targetRef, commandBuffer, partyManager)) {
                continue;
            }
            if (isInvulnerableTarget(targetRef, commandBuffer)) {
                continue;
            }

            EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer,
                    targetRef,
                    EntityStatMap.getComponentType());
            EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
                continue;
            }

            double burnDamage = targetHp.getMax() * ratioThisTick;
            if (burnDamage <= 0.0D) {
                continue;
            }
            PlayerRef targetPlayer = AugmentUtils.getPlayerRef(commandBuffer, targetRef);
            boolean targetIsMonster = targetPlayer == null || !targetPlayer.isValid();
            if (targetIsMonster && maxDamagePerTick > 0.0D && burnDamage > maxDamagePerTick) {
                burnDamage = maxDamagePerTick;
            }

            Damage burnTickDamage = PlayerCombatSystem.createAugmentDotDamage(sourceRef, (float) burnDamage);

            // Validate entity is still usable before applying damage (entity may have
            // become invisible)
            if (!EntityRefUtil.isUsable(targetRef)) {
                continue;
            }

            DamageSystems.executeDamage(targetRef, commandBuffer, burnTickDamage);
            applyBurnEffect(targetRef, commandBuffer, burnEffect);
        }
    }

    private static void applyBurnEffect(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityEffect burnEffect) {
        if (targetRef == null || commandBuffer == null || burnEffect == null) {
            return;
        }

        EffectControllerComponent controller = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }

        controller.addEffect(targetRef,
                burnEffect,
                BURN_EFFECT_DURATION_SECONDS,
                OverlapBehavior.OVERWRITE,
                commandBuffer);
    }

    private void startPulse(Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer,
            long now,
            double auraRadius) {
        if (sourceRef == null || commandBuffer == null) {
            return;
        }

        String pulseKey = resolvePulseKey(sourceRef, commandBuffer);
        ActivePulse pulse = ACTIVE_PULSES.computeIfAbsent(pulseKey, unused -> new ActivePulse());
        pulse.sourceRef = sourceRef;
        pulse.endRadius = Math.max(PULSE_RING_START_RADIUS, auraRadius);
        if (pulse.expiresAt <= now || pulse.startedAt <= 0L) {
            pulse.startedAt = now;
            pulse.expiresAt = now + PULSE_RING_DURATION_MILLIS;
            pulse.lastVisualAt = 0L;
            pulse.soundPlayed = false;
        }
        updatePulse(sourceRef, commandBuffer, now);
    }

    private void updatePulse(Ref<EntityStore> sourceRef, CommandBuffer<EntityStore> commandBuffer, long now) {
        if (sourceRef == null || commandBuffer == null) {
            return;
        }

        String pulseKey = resolvePulseKey(sourceRef, commandBuffer);
        ActivePulse pulse = ACTIVE_PULSES.get(pulseKey);
        if (pulse == null) {
            return;
        }
        if (pulse.expiresAt <= now || pulse.sourceRef == null || !pulse.sourceRef.isValid()) {
            ACTIVE_PULSES.remove(pulseKey);
            return;
        }

        TransformComponent sourceTransform = EntityRefUtil.tryGetComponent(commandBuffer,
                pulse.sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            return;
        }

        Vector3d centerPosition = sourceTransform.getPosition();
        double centerY = centerPosition.getY() + PULSE_RING_Y_OFFSET;
        if (!pulse.soundPlayed) {
            playPulseSound(pulse.sourceRef, new Vector3d(centerPosition.getX(), centerY, centerPosition.getZ()));
            pulse.soundPlayed = true;
        }
        if (pulse.lastVisualAt > 0L && now - pulse.lastVisualAt < PULSE_RING_STEP_MILLIS) {
            return;
        }

        double progress = Math.max(0.0D,
                Math.min(1.0D, (double) (now - pulse.startedAt) / (double) PULSE_RING_DURATION_MILLIS));
        double ringRadius = PULSE_RING_START_RADIUS + ((pulse.endRadius - PULSE_RING_START_RADIUS) * progress);
        double baseAngleOffset = progress * Math.PI * 2.0D;
        int pointCount = resolvePulsePointCount(pulse.endRadius);
        int layerCount = resolvePulseLayerCount(pulse.endRadius);
        double layerSpacing = resolvePulseLayerSpacing(pulse.endRadius, layerCount);

        for (int layer = 0; layer < layerCount; layer++) {
            double layerRadius = Math.max(PULSE_RING_START_RADIUS, ringRadius - (layer * layerSpacing));
            double angleOffset = baseAngleOffset + ((Math.PI / pointCount) * layer);
            for (int i = 0; i < pointCount; i++) {
            double angle = angleOffset + ((Math.PI * 2.0D * i) / pointCount);
                Vector3d particlePosition = new Vector3d(
                        centerPosition.getX() + (Math.cos(angle) * layerRadius),
                        centerY,
                        centerPosition.getZ() + (Math.sin(angle) * layerRadius));
                spawnPulseParticle(pulse.sourceRef, particlePosition);
            }
        }

        pulse.lastVisualAt = now;
        if (progress >= 1.0D) {
            ACTIVE_PULSES.remove(pulseKey);
        }
    }

    private int resolvePulsePointCount(double targetRadius) {
        return Math.max(PULSE_RING_MIN_POINT_COUNT,
                Math.min(PULSE_RING_MAX_POINT_COUNT, (int) Math.ceil(targetRadius * 6.0D)));
    }

    private int resolvePulseLayerCount(double targetRadius) {
        return Math.max(PULSE_RING_MIN_LAYER_COUNT,
                Math.min(PULSE_RING_MAX_LAYER_COUNT, (int) Math.ceil(targetRadius / 2.5D)));
    }

    private double resolvePulseLayerSpacing(double targetRadius, int layerCount) {
        if (layerCount <= 1) {
            return 0.0D;
        }
        return Math.max(PULSE_RING_MIN_LAYER_SPACING,
                Math.min(PULSE_RING_MAX_LAYER_SPACING, targetRadius / (layerCount * 6.0D)));
    }

    private void clearPulse(Ref<EntityStore> sourceRef, CommandBuffer<EntityStore> commandBuffer) {
        if (sourceRef == null || commandBuffer == null) {
            return;
        }
        ACTIVE_PULSES.remove(resolvePulseKey(sourceRef, commandBuffer));
    }

    private String resolvePulseKey(Ref<EntityStore> sourceRef, CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, sourceRef);
        if (playerRef != null && playerRef.isValid() && playerRef.getUuid() != null) {
            return playerRef.getUuid().toString();
        }
        return sourceRef.toString();
    }

    private void spawnPulseParticle(Ref<EntityStore> sourceRef, Vector3d position) {
        for (String particleId : PULSE_RING_VFX_IDS) {
            try {
                ParticleUtil.spawnParticleEffect(particleId, position, sourceRef.getStore());
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void playPulseSound(Ref<EntityStore> sourceRef, Vector3d position) {
        for (String soundId : PULSE_RING_SFX_IDS) {
            int soundIndex = resolveSoundIndex(soundId);
            if (soundIndex == 0) {
                continue;
            }
            SoundUtil.playSoundEvent3d(null, soundIndex, position, sourceRef.getStore());
            return;
        }
    }

    private static int resolveSoundIndex(String id) {
        int index = SoundEvent.getAssetMap().getIndex(id);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    private static EntityEffect resolveBurnEffect() {
        for (String candidate : BURN_EFFECT_IDS) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(candidate);
            if (effect != null) {
                return effect;
            }
            effect = EntityEffect.getAssetMap().getAsset(candidate.toLowerCase());
            if (effect != null) {
                return effect;
            }
            effect = EntityEffect.getAssetMap().getAsset(candidate.toUpperCase());
            if (effect != null) {
                return effect;
            }
        }
        return null;
    }

    private double resolveRadius(double attackerMaxHealth) {
        if (healthPerRadiusBlock <= 0.0D) {
            return baseRadius;
        }
        return baseRadius + Math.floor(attackerMaxHealth / healthPerRadiusBlock);
    }

    private UUID resolveSourceUuid(AugmentHooks.PassiveStatContext context,
            Ref<EntityStore> sourceRef,
            CommandBuffer<EntityStore> commandBuffer) {
        if (context != null && context.getPlayerData() != null && context.getPlayerData().getUuid() != null) {
            return context.getPlayerData().getUuid();
        }

        PlayerRef sourcePlayer = AugmentUtils.getPlayerRef(commandBuffer, sourceRef);
        return sourcePlayer != null && sourcePlayer.isValid() ? sourcePlayer.getUuid() : null;
    }

    private boolean isInvulnerableTarget(Ref<EntityStore> targetRef, CommandBuffer<EntityStore> commandBuffer) {
        if (targetRef == null || commandBuffer == null || !EntityRefUtil.isUsable(targetRef)) {
            return false;
        }

        if (commandBuffer.getArchetype(targetRef).contains(Invulnerable.getComponentType())) {
            return true;
        }

        EffectControllerComponent effectController = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                EffectControllerComponent.getComponentType());
        return effectController != null && effectController.isInvulnerable();
    }

    private boolean isFriendlyPetTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            PartyManager partyManager) {
        NPCMountComponent mountComponent = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                NPCMountComponent.getComponentType());
        if (mountComponent == null) {
            return false;
        }

        PlayerRef ownerPlayer = mountComponent.getOwnerPlayerRef();
        if (ownerPlayer == null || !ownerPlayer.isValid() || ownerPlayer.getUuid() == null) {
            return true;
        }

        // Keep all owned mounts/tamed entities safe from burn ticks.
        UUID ownerUuid = ownerPlayer.getUuid();
        if (sourceUuid != null && sourceUuid.equals(ownerUuid)) {
            return true;
        }

        return isSamePartyUuid(sourceUuid, sourcePartyLeader, ownerUuid, partyManager);
    }

    private boolean isSamePartyTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            PartyManager partyManager) {
        if (sourceUuid == null || partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        PlayerRef targetPlayer = EntityRefUtil.tryGetComponent(commandBuffer,
                targetRef,
                PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return false;
        }

        UUID targetUuid = targetPlayer.getUuid();
        if (targetUuid == null) {
            return false;
        }

        return isSamePartyUuid(sourceUuid, sourcePartyLeader, targetUuid, partyManager);
    }

    private boolean isSamePartyUuid(UUID sourceUuid,
            UUID sourcePartyLeader,
            UUID targetUuid,
            PartyManager partyManager) {
        if (sourceUuid == null || targetUuid == null || partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        UUID effectiveSourceLeader = sourcePartyLeader != null
                ? sourcePartyLeader
                : resolvePartyLeader(partyManager, sourceUuid);
        if (effectiveSourceLeader == null) {
            return false;
        }

        UUID targetLeader = resolvePartyLeader(partyManager, targetUuid);
        return targetLeader != null && targetLeader.equals(effectiveSourceLeader);
    }

    private UUID resolvePartyLeader(PartyManager partyManager, UUID playerUuid) {
        if (partyManager == null || !partyManager.isAvailable() || playerUuid == null) {
            return null;
        }
        if (!partyManager.isInParty(playerUuid)) {
            return null;
        }
        return partyManager.getPartyLeader(playerUuid);
    }

    private PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }
}
