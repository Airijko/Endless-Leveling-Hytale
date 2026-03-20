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
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.augments.types.DeathBombAugment;
import com.airijko.endlessleveling.util.ChatMessageTemplate;
import com.airijko.endlessleveling.util.ChatMessageStrings;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bridges event systems to augment logic implementations.
 */
public final class AugmentExecutor {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String COMMON_AUGMENT_ID = "common";
    private static final String FLEET_FOOTWORK_ID = "fleet_footwork";
    private static final String NESTING_DOLL_ID = "nesting_doll";
    private static final List<HealthModifierSpec> PASSIVE_HEALTH_MODIFIERS = List.of(
            new HealthModifierSpec("raid_boss", "max_hp_bonus"),
            new HealthModifierSpec("goliath", "max_hp_bonus"),
            new HealthModifierSpec("tank_engine", "max_hp_bonus"),
            new HealthModifierSpec("glass_cannon", "max_hp_penalty"),
            new HealthModifierSpec(NESTING_DOLL_ID, "max_hp_penalty"));

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

    public AugmentDispatch.OnHitResult applyOnHit(@Nonnull PlayerData playerData,
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
            return new AugmentDispatch.OnHitResult(startingDamage, 0.0D);
        }
        var runtime = runtimeManager.getRuntimeState(playerData.getUuid());
        notifyCooldowns(playerData, runtime, commandBuffer, attackerRef, augments);
        HitContext context = new HitContext(playerData, runtime, skillManager, attackerRef, targetRef, commandBuffer,
                attackerStats, targetStats, startingDamage, critical, ranged, weaponType);

        if (AugmentDispatch.isMiss(context)) {
            for (Augment augment : augments) {
                if (augment instanceof OnMissAugment missHandler) {
                    missHandler.onMiss(context);
                }
            }
            return new AugmentDispatch.OnHitResult(context.getDamage(), context.getTrueDamageBonus());
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
        return new AugmentDispatch.OnHitResult(context.getDamage(), context.getTrueDamageBonus());
    }

    public float applyOnDamageTaken(@Nonnull PlayerData defender,
            Ref<EntityStore> defenderRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage) {
        List<Augment> augments = resolve(defender);
        if (augments.isEmpty()) {
            LOGGER.atFine().log("[AUGMENT] No augments for defender %s", defender.getUuid());
            return incomingDamage;
        }
        LOGGER.atFine().log("[AUGMENT] Player %s taking %.3f damage with %d augments",
                defender.getUuid(), incomingDamage, augments.size());
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
        }

        if (damage <= 0f) {
            return 0f;
        }

        for (Augment augment : AugmentDispatch.resolveLowHpTriggerOrder(augments)) {
            LOGGER.atFine().log("[AUGMENT] Checking low HP trigger for: %s", augment.getId());
            OnLowHpAugment lowHp = (OnLowHpAugment) augment;
            damage = lowHp.onLowHp(context);
            context.setIncomingDamage(damage);
            if (damage <= 0f) {
                LOGGER.atInfo().log("[AUGMENT] %s ACTIVATED for player %s and blocked damage!", augment.getId(),
                        defender.getUuid());
                return 0f;
            }
        }
        return damage;
    }

    public float applySpecificOnDamageTaken(@Nonnull PlayerData defender,
            Ref<EntityStore> defenderRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage,
            @Nonnull String augmentId) {
        if (incomingDamage <= 0f || augmentId == null || augmentId.isBlank()) {
            return Math.max(0f, incomingDamage);
        }

        List<Augment> augments = resolve(defender);
        if (augments.isEmpty()) {
            return incomingDamage;
        }

        Augment matched = null;
        for (Augment augment : augments) {
            if (augment != null && augment.getId() != null && augmentId.equalsIgnoreCase(augment.getId())) {
                matched = augment;
                break;
            }
        }
        if (!(matched instanceof OnDamageTakenAugment handler)) {
            return incomingDamage;
        }

        var runtime = runtimeManager.getRuntimeState(defender.getUuid());
        notifyCooldowns(defender, runtime, commandBuffer, defenderRef, List.of(matched));
        DamageTakenContext context = new DamageTakenContext(defender,
                runtime,
                skillManager,
                defenderRef,
                attackerRef,
                commandBuffer,
                statMap,
                incomingDamage);
        float updated = handler.onDamageTaken(context);
        return Math.max(0f, updated);
    }

    public float applySpecificOnLowHp(@Nonnull PlayerData defender,
            Ref<EntityStore> defenderRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap statMap,
            float incomingDamage,
            @Nonnull String augmentId) {
        if (incomingDamage <= 0f || augmentId == null || augmentId.isBlank()) {
            return Math.max(0f, incomingDamage);
        }

        List<Augment> augments = resolve(defender);
        if (augments.isEmpty()) {
            return incomingDamage;
        }

        Augment matched = null;
        for (Augment augment : augments) {
            if (augment != null && augment.getId() != null && augmentId.equalsIgnoreCase(augment.getId())) {
                matched = augment;
                break;
            }
        }
        if (!(matched instanceof OnLowHpAugment handler)) {
            return incomingDamage;
        }

        var runtime = runtimeManager.getRuntimeState(defender.getUuid());
        notifyCooldowns(defender, runtime, commandBuffer, defenderRef, List.of(matched));
        DamageTakenContext context = new DamageTakenContext(defender,
                runtime,
                skillManager,
                defenderRef,
                attackerRef,
                commandBuffer,
                statMap,
                incomingDamage);
        float updated = handler.onLowHp(context);
        return Math.max(0f, updated);
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
        DeathBombAugment.tickPendingBombs(commandBuffer, playerRef);

        var runtime = runtimeManager.getRuntimeState(playerData.getUuid());
        runtime.clearPermanentAttributeBonuses();

        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        Set<String> selectedAugmentIds = toSelectedAugmentIds(selected.values());
        String signature = buildSelectionSignature(selectedAugmentIds);
        String previousSignature = runtime.getPassiveSelectionSignature();

        if (!Objects.equals(previousSignature, signature) && statMap != null) {
            cleanupStalePassiveHealthModifiers(statMap, selectedAugmentIds);
            runtime.retainAugmentStates(selectedAugmentIds);
            runtime.setPassiveSelectionSignature(signature);
        }

        if (selected.isEmpty()) {
            return;
        }

        List<Augment> augments = resolve(playerData);
        if (augments.isEmpty()) {
            return;
        }

        notifyCooldowns(playerData, runtime, commandBuffer, playerRef, augments);
        for (Map.Entry<String, String> entry : selected.entrySet()) {
            String selectionKey = entry.getKey();
            String augmentId = entry.getValue();
            if (augmentId == null || augmentId.isBlank()) {
                continue;
            }

            Augment augment = augmentManager.createAugment(augmentId);
            if (augment == null) {
                continue;
            }

            if (augment instanceof PassiveStatAugment handler) {
                PassiveStatContext context = new PassiveStatContext(playerData,
                        runtime,
                        skillManager,
                        selectionKey,
                        playerRef,
                        commandBuffer,
                        statMap,
                        deltaSeconds);
                handler.applyPassive(context);
            }
        }
    }

    private void cleanupStalePassiveHealthModifiers(EntityStatMap statMap, Set<String> selectedAugmentIds) {
        if (statMap == null) {
            return;
        }

        var health = statMap.get(DefaultEntityStatTypes.getHealth());
        if (health == null) {
            return;
        }
        float previousMax = Math.max(1.0f, health.getMax());
        float previousCurrent = Math.max(0.0f, health.get());

        boolean shouldUpdate = false;
        for (HealthModifierSpec spec : PASSIVE_HEALTH_MODIFIERS) {
            // Always strip legacy non-prefixed keys when selections change.
            statMap.removeModifier(DefaultEntityStatTypes.getHealth(), spec.legacyKey());

            // Prefixed keys must only exist while the augment is actively selected.
            if (!selectedAugmentIds.contains(spec.augmentId())) {
                statMap.removeModifier(DefaultEntityStatTypes.getHealth(), spec.prefixedKey());
                shouldUpdate = true;
            }
        }

        if (!shouldUpdate) {
            return;
        }

        statMap.update();
        var updated = statMap.get(DefaultEntityStatTypes.getHealth());
        if (updated == null || updated.getMax() <= 0f) {
            return;
        }

        float newMax = Math.max(1.0f, updated.getMax());
        float ratio = previousMax > 0.01f ? (previousCurrent / previousMax) : 1.0f;
        float adjustedCurrent = Math.max(1.0f, Math.min(newMax, ratio * newMax));
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), adjustedCurrent);
    }

    private Set<String> toSelectedAugmentIds(Collection<String> selectedAugmentValues) {
        Set<String> selected = new HashSet<>();
        if (selectedAugmentValues == null || selectedAugmentValues.isEmpty()) {
            return selected;
        }
        for (String selectedValue : selectedAugmentValues) {
            String normalized = normalizeSelectedAugmentId(selectedValue);
            if (normalized != null && !normalized.isBlank()) {
                selected.add(normalized);
            }
        }
        return selected;
    }

    private String normalizeSelectedAugmentId(String augmentId) {
        if (augmentId == null || augmentId.isBlank()) {
            return null;
        }
        String normalized = augmentId.trim().toLowerCase(Locale.ROOT);
        int encodedDelimiter = normalized.indexOf("::");
        if (encodedDelimiter > 0) {
            normalized = normalized.substring(0, encodedDelimiter);
        }
        if ("basic".equals(normalized)) {
            return COMMON_AUGMENT_ID;
        }
        return normalized;
    }

    private String buildSelectionSignature(Set<String> selectedAugmentIds) {
        if (selectedAugmentIds == null || selectedAugmentIds.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(selectedAugmentIds);
        sorted.sort(String::compareTo);
        return String.join("|", sorted);
    }

    private record HealthModifierSpec(String augmentId, String suffix) {
        private String prefixedKey() {
            return "EL_" + augmentId + "_" + suffix;
        }

        private String legacyKey() {
            return augmentId + "_" + suffix;
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
        if (!playerData.isAugmentNotifEnabled()) {
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
                String readyText = PlayerChatNotifier.text(playerRef,
                        ChatMessageTemplate.AUGMENT_READY_AGAIN,
                        display != null ? display : ChatMessageStrings.Name.AUGMENT);
                sendAugmentMessage(playerRef, readyText);
                if (FLEET_FOOTWORK_ID.equalsIgnoreCase(cooldown.getAugmentId())) {
                    LOGGER.atInfo().log("Fleet Footwork available for player=%s", playerData.getUuid());
                }
                cooldown.setReadyNotified(true);
            }
        }
    }

    private void sendAugmentMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        PlayerChatNotifier.send(playerRef, ChatMessageTemplate.AUGMENT_GENERIC, text);
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
            } else {
                LOGGER.atWarning().log("[AUGMENT] Failed to create augment: %s", id);
            }
        }
        return augments;
    }
}
