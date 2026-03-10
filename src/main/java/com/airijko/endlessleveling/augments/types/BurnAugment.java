package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PartyManager;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public final class BurnAugment extends YamlAugment implements AugmentHooks.PassiveStatAugment {
    public static final String ID = "burn";

    private final double basePercentPerSecond;
    private final double bonusScalingPer100Health;
    private final double baseRadius;
    private final double healthPerRadiusBlock;
    private final double lifeForceFlatBonus;

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
        this.lifeForceFlatBonus = Math.max(0.0D, AugmentValueReader.getDouble(lifeForce, "value", 0.0D));
    }

    @Override
    public void applyPassive(AugmentHooks.PassiveStatContext context) {
        if (context == null || context.getCommandBuffer() == null || context.getPlayerRef() == null
                || context.getStatMap() == null) {
            return;
        }

        if (context.getRuntimeState() != null) {
            context.getRuntimeState().setAttributeBonus(
                    SkillAttributeType.LIFE_FORCE,
                    ID + "_life_force",
                    lifeForceFlatBonus,
                    0L);
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
        double deltaSeconds = Math.max(0.0D, context.getDeltaSeconds());
        if (percentPerSecond <= 0.0D || deltaSeconds <= 0.0D) {
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> sourceRef = context.getPlayerRef();
        TransformComponent sourceTransform = commandBuffer.getComponent(sourceRef,
                TransformComponent.getComponentType());
        if (sourceTransform == null || sourceTransform.getPosition() == null) {
            return;
        }

        double ratioThisTick = percentPerSecond * deltaSeconds;
        UUID sourceUuid = context.getPlayerData() != null ? context.getPlayerData().getUuid() : null;
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
            if (isPetEntity(targetRef, commandBuffer)) {
                continue;
            }
            if (isSamePartyTarget(sourceUuid, sourcePartyLeader, targetRef, commandBuffer, partyManager)) {
                continue;
            }

            EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
            EntityStatValue targetHp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (targetHp == null || targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
                continue;
            }

            double burnDamage = targetHp.getMax() * ratioThisTick;
            if (burnDamage <= 0.0D) {
                continue;
            }

            if (burnDamage >= targetHp.get()) {
                markBurnKill(sourceRef, targetRef, commandBuffer, targetStats);
                continue;
            }

            float updatedHealth = (float) Math.max(0.0D, targetHp.get() - burnDamage);
            targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), updatedHealth);
        }
    }

    private void markBurnKill(Ref<EntityStore> sourceRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats) {
        if (targetRef == null || commandBuffer == null || targetStats == null) {
            return;
        }

        if (commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) == null) {
            Damage damage = sourceRef != null
                    ? new Damage(new Damage.EntitySource(sourceRef), DamageCause.PHYSICAL, Float.MAX_VALUE)
                    : new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE);
            DeathComponent.tryAddComponent(commandBuffer, targetRef, damage);
        }

        // Keep health state consistent with the lethal burn tick.
        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
    }

    private double resolveRadius(double attackerMaxHealth) {
        if (healthPerRadiusBlock <= 0.0D) {
            return baseRadius;
        }
        return baseRadius + Math.floor(attackerMaxHealth / healthPerRadiusBlock);
    }

    private boolean isPetEntity(Ref<EntityStore> targetRef, CommandBuffer<EntityStore> commandBuffer) {
        return commandBuffer.getComponent(targetRef, NPCMountComponent.getComponentType()) != null;
    }

    private boolean isSamePartyTarget(UUID sourceUuid,
            UUID sourcePartyLeader,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            PartyManager partyManager) {
        if (sourceUuid == null || partyManager == null || !partyManager.isAvailable()) {
            return false;
        }

        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return false;
        }

        UUID targetUuid = targetPlayer.getUuid();
        if (targetUuid == null) {
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
