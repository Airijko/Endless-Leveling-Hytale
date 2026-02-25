package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.augments.AugmentHooks.DamageTakenContext;
import com.airijko.endlessleveling.augments.AugmentHooks.HitContext;
import com.airijko.endlessleveling.augments.AugmentHooks.KillContext;
import com.airijko.endlessleveling.augments.AugmentHooks.PassiveStatContext;
import com.airijko.endlessleveling.augments.AugmentHooks.OnCritAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnDamageTakenAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnHitAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnKillAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnLowHpAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnTargetConditionAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.OnMissAugment;
import com.airijko.endlessleveling.augments.AugmentHooks.PassiveStatAugment;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges event systems to augment logic implementations.
 */
public final class AugmentExecutor {

    private final AugmentManager augmentManager;
    private final AugmentRuntimeManager runtimeManager;
    private final SkillManager skillManager;

    public AugmentExecutor(@Nonnull AugmentManager augmentManager,
            @Nonnull AugmentRuntimeManager runtimeManager,
            @Nonnull SkillManager skillManager) {
        this.augmentManager = Objects.requireNonNull(augmentManager, "augmentManager");
        this.runtimeManager = Objects.requireNonNull(runtimeManager, "runtimeManager");
        this.skillManager = Objects.requireNonNull(skillManager, "skillManager");
    }

    public float applyOnHit(@Nonnull PlayerData playerData,
            Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap attackerStats,
            EntityStatMap targetStats,
            float startingDamage,
            boolean critical,
            boolean ranged,
            ClassWeaponType weaponType) {
        List<Augment> augments = resolve(playerData);
        if (augments.isEmpty()) {
            return startingDamage;
        }
        var runtime = runtimeManager.getRuntimeState(playerData.getUuid());
        notifyCooldowns(playerData, runtime, commandBuffer, attackerRef, augments);
        HitContext context = new HitContext(playerData, runtime, skillManager, attackerRef, targetRef, commandBuffer,
                attackerStats, targetStats, startingDamage, critical, ranged, weaponType);

        if (isMiss(context)) {
            for (Augment augment : augments) {
                if (augment instanceof OnMissAugment missHandler) {
                    missHandler.onMiss(context);
                }
            }
            return context.getDamage();
        }

        for (Augment augment : augments) {
            if (augment instanceof OnHitAugment handler) {
                float updated = handler.onHit(context);
                context.setDamage(updated);
            }
            if (critical && augment instanceof OnCritAugment critAugment) {
                critAugment.onCrit(context);
            }
            if (augment instanceof OnTargetConditionAugment conditional) {
                float updated = conditional.onTargetCondition(context);
                context.setDamage(updated);
            }
        }
        return context.getDamage();
    }

    private boolean isMiss(@Nonnull HitContext context) {
        if (context.getDamage() <= 0.0f) {
            return true;
        }
        if (context.getTargetRef() == null || !context.getTargetRef().isValid()) {
            return true;
        }
        EntityStatMap targetStats = context.getTargetStats();
        if (targetStats == null) {
            return true;
        }
        EntityStatValue targetHp = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (targetHp == null) {
            return true;
        }
        if (targetHp.getMax() <= 0f || targetHp.get() <= 0f) {
            return true;
        }
        if (context.getAttackerRef() != null && context.getAttackerRef().equals(context.getTargetRef())) {
            return true;
        }
        return false;
    }

    public float applyOnDamageTaken(@Nonnull PlayerData defender,
            Ref<EntityStore> defenderRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage) {
        List<Augment> augments = resolve(defender);
        if (augments.isEmpty()) {
            return incomingDamage;
        }
        var runtime = runtimeManager.getRuntimeState(defender.getUuid());
        notifyCooldowns(defender, runtime, commandBuffer, defenderRef, augments);
        DamageTakenContext context = new DamageTakenContext(defender, runtime, skillManager, defenderRef, attackerRef,
                commandBuffer, statMap, incomingDamage);
        float damage = incomingDamage;
        for (Augment augment : augments) {
            if (augment instanceof OnDamageTakenAugment handler) {
                damage = handler.onDamageTaken(context);
                context.setIncomingDamage(damage);
            }
            if (augment instanceof OnLowHpAugment lowHp) {
                damage = lowHp.onLowHp(context);
                context.setIncomingDamage(damage);
            }
        }
        return damage;
    }

    public void handleKill(@Nonnull PlayerData killer,
            Ref<EntityStore> killerRef,
            Ref<EntityStore> victimRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap victimStats) {
        List<Augment> augments = resolve(killer);
        if (augments.isEmpty()) {
            return;
        }
        var runtime = runtimeManager.getRuntimeState(killer.getUuid());
        notifyCooldowns(killer, runtime, commandBuffer, killerRef, augments);
        KillContext context = new KillContext(killer, runtime, skillManager, killerRef, victimRef, commandBuffer,
                victimStats);
        for (Augment augment : augments) {
            if (augment instanceof OnKillAugment handler) {
                handler.onKill(context);
            }
        }
    }

    public void applyPassive(@Nonnull PlayerData playerData,
            Ref<EntityStore> playerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            double deltaSeconds) {
        List<Augment> augments = resolve(playerData);
        if (augments.isEmpty()) {
            return;
        }
        var runtime = runtimeManager.getRuntimeState(playerData.getUuid());
        notifyCooldowns(playerData, runtime, commandBuffer, playerRef, augments);
        PassiveStatContext context = new PassiveStatContext(playerData, runtime, skillManager, playerRef, commandBuffer,
                statMap, deltaSeconds);
        for (Augment augment : augments) {
            if (augment instanceof PassiveStatAugment handler) {
                handler.applyPassive(context);
            }
        }
    }

    private void notifyCooldowns(@Nonnull PlayerData playerData,
            AugmentRuntimeManager.AugmentRuntimeState runtime,
            CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> entityRef,
            List<Augment> augments) {
        if (runtime == null || commandBuffer == null || entityRef == null) {
            return;
        }
        PlayerRef playerRef = AugmentUtils.getPlayerRef(commandBuffer, entityRef);
        if (playerRef == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, String> names = new java.util.HashMap<>();
        for (Augment augment : augments) {
            names.put(augment.getId().toLowerCase(), augment.getName());
        }
        for (var cooldown : runtime.getCooldowns()) {
            if (cooldown == null || cooldown.isReadyNotified()) {
                continue;
            }
            if (cooldown.getExpiresAt() > 0L && now >= cooldown.getExpiresAt()) {
                String display = names.getOrDefault(cooldown.getAugmentId(), cooldown.getDisplayName());
                sendAugmentMessage(playerRef,
                        String.format("%s is ready.", display != null ? display : "Augment"));
                cooldown.setReadyNotified(true);
            }
        }
    }

    private void sendAugmentMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        playerRef.sendMessage(Message.raw(text).color("#f7c74f"));
    }

    private List<Augment> resolve(@Nonnull PlayerData playerData) {
        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        if (selected.isEmpty()) {
            return List.of();
        }
        Collection<String> ids = selected.values();
        List<Augment> augments = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            Augment augment = augmentManager.createAugment(id);
            if (augment != null) {
                augments.add(augment);
            }
        }
        return augments;
    }
}
