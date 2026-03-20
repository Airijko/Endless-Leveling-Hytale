package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.augments.types.NestingDollAugment;
import com.airijko.endlessleveling.augments.types.ProtectiveBubbleAugment;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.leveling.XpKillCreditTracker;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long TRUE_EDGE_DEFAULT_INTERNAL_COOLDOWN_MILLIS = 400L;
    public static final MetaKey<Boolean> AUGMENT_DOT_DAMAGE = Damage.META_REGISTRY
            .registerMetaObject(data -> Boolean.FALSE);
    public static final MetaKey<Boolean> AUGMENT_PROC_DAMAGE = Damage.META_REGISTRY
            .registerMetaObject(data -> Boolean.FALSE);

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final AugmentExecutor augmentExecutor;
    private final MobAugmentExecutor mobAugmentExecutor;
    private final MobLevelingManager mobLevelingManager;
    private final CombatHookProcessor combatHookProcessor;

    public PlayerCombatSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull SkillManager skillManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            AugmentExecutor augmentExecutor,
            MobAugmentExecutor mobAugmentExecutor,
            MobLevelingManager mobLevelingManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.augmentExecutor = augmentExecutor;
        this.mobAugmentExecutor = mobAugmentExecutor;
        this.mobLevelingManager = mobLevelingManager;
        this.combatHookProcessor = new CombatHookProcessor(skillManager,
                passiveManager,
                archetypePassiveManager,
                classManager,
                playerDataManager,
                mobLevelingManager,
                augmentExecutor);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (!EntityRefUtil.isUsable(attackerRef) || !EntityRefUtil.isUsable(targetRef)) {
            return;
        }

        if (ArmyOfTheDeadPassive.shouldPreventFriendlyDamage(attackerRef, targetRef, store, commandBuffer)) {
            damage.setAmount(0.0f);
            return;
        }

        XpKillCreditTracker.recordDamage(targetRef, attackerRef, store, commandBuffer);

        ArmyOfTheDeadPassive.focusSummonsOnSummonAttacker(targetRef, attackerRef, store, commandBuffer);

        PlayerRef attackerPlayer = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef,
                PlayerRef.getComponentType());
        if (attackerPlayer == null || !attackerPlayer.isValid()) {
            return;
        }

        PlayerData playerData = playerDataManager.get(attackerPlayer.getUuid());
        if (playerData == null) {
            return;
        }

        PassiveRuntimeState runtimeState = passiveManager != null
                ? passiveManager.getRuntimeState(playerData.getUuid())
                : null;
        ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();

        Player player = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef, Player.getComponentType());
        ItemStack weapon = player != null && player.getInventory() != null
                ? player.getInventory().getItemInHand()
                : null;

        EntityStatMap attackerStats = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef,
                EntityStatMap.getComponentType());
        EntityStatMap targetStats = EntityRefUtil.tryGetComponent(commandBuffer, targetRef,
                EntityStatMap.getComponentType());

        boolean bypassOutgoingAugmentMath = shouldBypassOutgoingAugmentMath(damage);
        CombatHookProcessor.OutgoingResult result = bypassOutgoingAugmentMath
                ? new CombatHookProcessor.OutgoingResult(damage.getAmount(), false, 0.0D)
                : combatHookProcessor.processOutgoing(
                        new CombatHookProcessor.OutgoingContext(
                                playerData,
                                attackerPlayer,
                                attackerRef,
                                targetRef,
                                commandBuffer,
                                damage,
                                weapon,
                                runtimeState,
                                archetypeSnapshot,
                                attackerStats,
                                targetStats));

        float adjusted = Math.max(0.0f, result.finalDamage());
        float incomingBeforeDefense = adjusted;
        int mobLevel = -1;
        int playerLevel = Math.max(1, playerData.getLevel());
        double reduction = 0.0D;
        PlayerRef targetPlayer = EntityRefUtil.tryGetComponent(commandBuffer, targetRef, PlayerRef.getComponentType());
        boolean targetIsPlayer = targetPlayer != null && targetPlayer.isValid();
        long now = System.currentTimeMillis();
        TrueEdgeSettings trueEdgeSettings = resolveTrueEdgeSettings(archetypeSnapshot);
        boolean trueEdgeReady = isTrueEdgeOffCooldown(runtimeState, now, trueEdgeSettings.internalCooldownMillis());

        TrueEdgeComputation trueEdge = (!bypassOutgoingAugmentMath && trueEdgeReady)
                ? computeTrueEdgeConversion(
                        targetRef,
                        commandBuffer,
                        trueEdgeSettings,
                        incomingBeforeDefense,
                        playerLevel,
                        reduction,
                        targetIsPlayer)
                : TrueEdgeComputation.none();
        float normalAfterConversion = (float) Math.max(0.0D,
                incomingBeforeDefense - trueEdge.convertedDamageFromNormal());
        adjusted = normalAfterConversion;

        if (mobLevelingManager != null
                && mobLevelingManager.isMobLevelingEnabled()
                && mobLevelingManager.isMobDefenseScalingEnabled()) {
            if (!targetIsPlayer) {
                mobLevel = mobLevelingManager.resolveMobLevel(targetRef, commandBuffer);
                reduction = mobLevelingManager.getMobDefenseReductionForLevels(
                        targetRef,
                        commandBuffer,
                        mobLevel,
                        playerLevel);
                if (reduction != 0.0D) {
                    adjusted = (float) (adjusted * (1.0D - reduction));
                }
            }
        }

        // Re-resolve using the final matchup reduction (mob or player) so conversion
        // only
        // shifts damage buckets and level-difference defense is the sole true-damage
        // reducer.
        trueEdge = (!bypassOutgoingAugmentMath && trueEdgeReady)
                ? computeTrueEdgeConversion(
                        targetRef,
                        commandBuffer,
                        trueEdgeSettings,
                        incomingBeforeDefense,
                        playerLevel,
                        reduction,
                        targetIsPlayer)
                : TrueEdgeComputation.none();
        if (!bypassOutgoingAugmentMath
                && trueEdgeReady
                && trueEdge.triggered()
                && runtimeState != null
                && trueEdgeSettings.internalCooldownMillis() > 0L) {
            runtimeState.setTrueEdgeCooldownExpiresAt(now + trueEdgeSettings.internalCooldownMillis());
        }
        double reducedAugmentTrueDamage = bypassOutgoingAugmentMath
                ? 0.0D
                : applyLevelDifferenceReductionToTrueDamage(
                        result.trueDamageBonus(),
                        targetRef,
                        commandBuffer,
                        playerLevel,
                        reduction,
                        targetIsPlayer);

        float targetHp = Float.NaN;
        float targetMax = Float.NaN;
        boolean targetDead = commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) != null;
        if (targetStats != null) {
            EntityStatValue hp = targetStats.get(DefaultEntityStatTypes.getHealth());
            if (hp != null) {
                targetHp = hp.get();
                targetMax = hp.getMax();
            }
        }

        float finalAdjusted = Math.max(0.0f, adjusted);
        if (!targetIsPlayer) {
            finalAdjusted = applyMobAugmentsIfPresent(targetRef,
                    store,
                    attackerRef,
                    commandBuffer,
                    targetStats,
                    finalAdjusted);
        }
        float appliedTrueDamage = applyTrueEdgeDamage(
                attackerRef,
                targetRef,
                targetPlayer,
                commandBuffer,
                trueEdge.reducedTrueDamage() + reducedAugmentTrueDamage);

        float predictedPostHp = Float.NaN;
        boolean predictedLethal = false;
        if (Float.isFinite(targetHp)) {
            predictedPostHp = targetHp - finalAdjusted - appliedTrueDamage;
            predictedLethal = predictedPostHp <= 0.0001f;
        }

        LOGGER.atInfo().log(
                "PlayerHit target=%d attacker=%d dmg=%.3f->%.3f true=%.3f mobLevel=%d playerLevel=%d reduction=%.4f hp=%.3f max=%.3f predictedPostHp=%.3f predictedLethal=%s dead=%s targetIsPlayer=%s",
                targetRef.getIndex(),
                attackerRef.getIndex(),
                incomingBeforeDefense,
                finalAdjusted,
                appliedTrueDamage,
                mobLevel,
                playerLevel,
                reduction,
                targetHp,
                targetMax,
                predictedPostHp,
                predictedLethal,
                targetDead,
                targetIsPlayer);

        damage.setAmount(finalAdjusted);
    }

    private float applyMobAugmentsIfPresent(Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats,
            float incomingDamage) {
        if (targetRef == null
                || targetStats == null
                || incomingDamage <= 0f
                || mobAugmentExecutor == null
                || mobLevelingManager == null) {
            return incomingDamage;
        }

        List<String> augmentIds = mobLevelingManager.getMobOverrideAugmentIds(targetRef, store, commandBuffer);
        if (augmentIds.isEmpty()) {
            return incomingDamage;
        }

        UUID mobUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (mobUuid == null) {
            return incomingDamage;
        }

        if (!mobAugmentExecutor.hasMobAugments(mobUuid)) {
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin != null && plugin.getAugmentManager() != null && plugin.getAugmentRuntimeManager() != null) {
                mobAugmentExecutor.registerMobAugments(mobUuid,
                        augmentIds,
                        plugin.getAugmentManager(),
                        plugin.getAugmentRuntimeManager());
                LOGGER.atInfo().log("[MOB_OVERRIDE_AUGMENTS] target=%d uuid=%s augments=%s",
                        targetRef.getIndex(), mobUuid, augmentIds);
            }
        }

        float afterDamageTaken = mobAugmentExecutor.applyOnDamageTaken(
                mobUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                incomingDamage);
        float finalDamage = mobAugmentExecutor.applyOnLowHp(
                mobUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                afterDamageTaken);

        if (finalDamage != incomingDamage) {
            LOGGER.atInfo().log("MobAugments target=%d damage %.3f -> %.3f augments=%s",
                    targetRef.getIndex(), incomingDamage, finalDamage, augmentIds);
        }
        return Math.max(0.0f, finalDamage);
    }

    private UUID resolveEntityUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return null;
        }

        UUIDComponent uuidComponent = commandBuffer != null
                ? commandBuffer.getComponent(ref, UUIDComponent.getComponentType())
                : null;
        if (uuidComponent == null && store != null) {
            uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        }
        if (uuidComponent == null) {
            return null;
        }

        try {
            return uuidComponent.getUuid();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Damage createAugmentDotDamage(Ref<EntityStore> sourceRef, float amount) {
        Damage dotDamage = createPhysicalDamage(sourceRef, amount);
        dotDamage.putMetaObject(AUGMENT_DOT_DAMAGE, Boolean.TRUE);
        return dotDamage;
    }

    public static Damage createAugmentProcDamage(Ref<EntityStore> sourceRef, float amount) {
        Damage procDamage = createPhysicalDamage(sourceRef, amount);
        procDamage.putMetaObject(AUGMENT_PROC_DAMAGE, Boolean.TRUE);
        return procDamage;
    }

    private static Damage createPhysicalDamage(Ref<EntityStore> sourceRef, float amount) {
        if (EntityRefUtil.isUsable(sourceRef)) {
            try {
                return new Damage(new Damage.EntitySource(sourceRef), DamageCause.PHYSICAL, amount);
            } catch (IllegalStateException ignored) {
            }
        }
        return new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, amount);
    }

    public static boolean isAugmentDotDamage(Damage damage) {
        return damage != null && Boolean.TRUE.equals(damage.getIfPresentMetaObject(AUGMENT_DOT_DAMAGE));
    }

    public static boolean isAugmentProcDamage(Damage damage) {
        return damage != null && Boolean.TRUE.equals(damage.getIfPresentMetaObject(AUGMENT_PROC_DAMAGE));
    }

    public static boolean shouldBypassOutgoingAugmentMath(Damage damage) {
        return isAugmentDotDamage(damage) || isAugmentProcDamage(damage);
    }

    private float applyTrueEdgeDamage(Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            PlayerRef targetPlayer,
            CommandBuffer<EntityStore> commandBuffer,
            double trueDamageAmount) {
        if (trueDamageAmount <= 0.0D || targetRef == null || commandBuffer == null) {
            return 0.0f;
        }

        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (targetPlayer != null && targetPlayer.isValid() && targetStats != null && augmentExecutor != null) {
            PlayerData defenderData = playerDataManager.get(targetPlayer.getUuid());
            if (defenderData != null) {
                float afterNestingImmunity = augmentExecutor.applySpecificOnDamageTaken(defenderData,
                        targetRef,
                        attackerRef,
                        commandBuffer,
                        targetStats,
                        (float) trueDamageAmount,
                        NestingDollAugment.ID);
                trueDamageAmount = Math.max(0.0D, afterNestingImmunity);
                if (trueDamageAmount <= 0.0D) {
                    return 0.0f;
                }

                float afterBubble = augmentExecutor.applySpecificOnDamageTaken(defenderData,
                        targetRef,
                        attackerRef,
                        commandBuffer,
                        targetStats,
                        (float) trueDamageAmount,
                        ProtectiveBubbleAugment.ID);
                trueDamageAmount = Math.max(0.0D, afterBubble);
                if (trueDamageAmount <= 0.0D) {
                    return 0.0f;
                }

                float afterNestingLowHp = augmentExecutor.applySpecificOnLowHp(defenderData,
                        targetRef,
                        attackerRef,
                        commandBuffer,
                        targetStats,
                        (float) trueDamageAmount,
                        NestingDollAugment.ID);
                trueDamageAmount = Math.max(0.0D, afterNestingLowHp);
                if (trueDamageAmount <= 0.0D) {
                    return 0.0f;
                }
            }
        }

        EntityStatValue hp = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f || hp.get() <= 0f) {
            return 0.0f;
        }

        float current = hp.get();
        float applied = (float) Math.min(current, trueDamageAmount);
        if (applied <= 0f) {
            return 0.0f;
        }

        if (applied >= current - 0.0001f) {
            markTrueEdgeKill(attackerRef, targetRef, commandBuffer, targetStats);
            return applied;
        }

        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), current - applied);
        return applied;
    }

    private TrueEdgeComputation computeTrueEdgeConversion(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            TrueEdgeSettings settings,
            float outgoingDamageBeforeDefense,
            int attackerLevel,
            double mobReduction,
            boolean targetIsPlayer) {
        if (!settings.enabled()) {
            return TrueEdgeComputation.none();
        }

        double baseOutgoing = Math.max(0.0D, outgoingDamageBeforeDefense);
        if (baseOutgoing <= 0.0D) {
            return TrueEdgeComputation.none();
        }

        double convertedDamageFromNormal = Math.min(baseOutgoing, baseOutgoing * settings.trueDamagePercent());
        double maxHealthTrueDamage = computeMaxHealthTrueDamage(
                targetRef,
                commandBuffer,
                settings.maxHealthTrueDamagePercent());
        double rawTrueDamage = settings.flatTrueDamage() + convertedDamageFromNormal + maxHealthTrueDamage;
        if (!targetIsPlayer && settings.monsterTrueDamageCap() > 0.0D) {
            rawTrueDamage = Math.min(rawTrueDamage, settings.monsterTrueDamageCap());
        }
        if (rawTrueDamage <= 0.0D) {
            return TrueEdgeComputation.none();
        }

        double reducedTrueDamage = applyLevelDifferenceReductionToTrueDamage(
                rawTrueDamage,
                targetRef,
                commandBuffer,
                attackerLevel,
                mobReduction,
                targetIsPlayer);
        if (reducedTrueDamage <= 0.0D) {
            return new TrueEdgeComputation(convertedDamageFromNormal, 0.0D);
        }

        return new TrueEdgeComputation(convertedDamageFromNormal, reducedTrueDamage);
    }

    private double computeMaxHealthTrueDamage(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            double maxHealthTrueDamagePercent) {
        if (maxHealthTrueDamagePercent <= 0.0D || targetRef == null || commandBuffer == null) {
            return 0.0D;
        }

        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (targetStats == null) {
            return 0.0D;
        }

        EntityStatValue hp = targetStats.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || hp.getMax() <= 0f) {
            return 0.0D;
        }

        return Math.max(0.0D, hp.getMax()) * maxHealthTrueDamagePercent;
    }

    private boolean isTrueEdgeOffCooldown(PassiveRuntimeState runtimeState, long now, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return true;
        }
        if (runtimeState == null) {
            return true;
        }
        return now >= runtimeState.getTrueEdgeCooldownExpiresAt();
    }

    private TrueEdgeSettings resolveTrueEdgeSettings(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return TrueEdgeSettings.disabled();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.TRUE_EDGE);
        if (definitions.isEmpty()) {
            return TrueEdgeSettings.disabled();
        }

        double flatTrueDamage = 0.0D;
        double trueDamagePercent = 0.0D;
        double maxHealthTrueDamagePercent = 0.0D;
        long internalCooldownMillis = TRUE_EDGE_DEFAULT_INTERNAL_COOLDOWN_MILLIS;
        double monsterTrueDamageCap = 0.0D;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }

            Map<String, Object> props = definition.properties();

            double flat = Math.max(0.0D, definition.value());
            double explicitFlat = parsePositiveDouble(props == null ? null : props.get("flat_true_damage"));
            if (explicitFlat > 0.0D) {
                flat = explicitFlat;
            }
            flatTrueDamage += flat;

            trueDamagePercent += parsePercent(props == null ? null : props.get("true_damage_percent"));

            maxHealthTrueDamagePercent += parsePercent(props == null
                    ? null
                    : firstNonNull(
                            props.get("max_health_true_damage_percent"),
                            props.get("max_health_true_damage")));

            long cooldownCandidate = parseInternalCooldownMillis(props);
            if (cooldownCandidate >= 0L) {
                internalCooldownMillis = cooldownCandidate;
            }

            double capCandidate = parsePositiveDouble(props == null
                    ? null
                    : firstNonNull(
                            props.get("monster_true_damage_cap"),
                            props.get("monster_cap"),
                            props.get("max_true_damage_vs_monsters")));
            if (capCandidate > 0.0D) {
                monsterTrueDamageCap = capCandidate;
            }
        }

        if (flatTrueDamage <= 0.0D && trueDamagePercent <= 0.0D && maxHealthTrueDamagePercent <= 0.0D) {
            return TrueEdgeSettings.disabled();
        }
        return new TrueEdgeSettings(
                flatTrueDamage,
                trueDamagePercent,
                maxHealthTrueDamagePercent,
                Math.max(0L, internalCooldownMillis),
                monsterTrueDamageCap);
    }

    private double resolveTrueEdgeReduction(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            int attackerLevel,
            double mobReduction,
            boolean targetIsPlayer) {
        if (mobLevelingManager == null
                || !mobLevelingManager.isMobLevelingEnabled()
                || !mobLevelingManager.isMobDefenseScalingEnabled()) {
            return 0.0D;
        }

        if (!targetIsPlayer) {
            return clampDefenseReduction(mobReduction);
        }

        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return 0.0D;
        }

        PlayerData targetData = playerDataManager.get(targetPlayer.getUuid());
        int targetLevel = targetData == null ? Math.max(1, attackerLevel) : Math.max(1, targetData.getLevel());
        int levelDifference = targetLevel - Math.max(1, attackerLevel);
        return clampDefenseReduction(mobLevelingManager.getMobDefenseReductionForLevelDifference(levelDifference));
    }

    private double applyLevelDifferenceReductionToTrueDamage(double rawTrueDamage,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            int attackerLevel,
            double mobReduction,
            boolean targetIsPlayer) {
        if (rawTrueDamage <= 0.0D) {
            return 0.0D;
        }
        double levelDifferenceReduction = resolveTrueEdgeReduction(
                targetRef,
                commandBuffer,
                attackerLevel,
                mobReduction,
                targetIsPlayer);
        return Math.max(0.0D, rawTrueDamage * (1.0D - levelDifferenceReduction));
    }

    private void markTrueEdgeKill(Ref<EntityStore> sourceRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats) {
        if (targetRef == null || commandBuffer == null || targetStats == null) {
            return;
        }

        if (commandBuffer.getComponent(targetRef, DeathComponent.getComponentType()) == null) {
            Damage killDamage = createPhysicalDamage(sourceRef, Float.MAX_VALUE);
            DeathComponent.tryAddComponent(commandBuffer, targetRef, killDamage);
        }

        targetStats.setStatValue(DefaultEntityStatTypes.getHealth(), 0.0f);
    }

    private double parsePositiveDouble(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value > 0.0D ? value : 0.0D;
        }
        if (raw instanceof String stringValue) {
            try {
                double value = Double.parseDouble(stringValue.trim());
                return value > 0.0D ? value : 0.0D;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0D;
    }

    private double parsePercent(Object raw) {
        double value = parsePositiveDouble(raw);
        if (value <= 0.0D) {
            return 0.0D;
        }
        return value > 1.0D ? value / 100.0D : value;
    }

    private long parseInternalCooldownMillis(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return -1L;
        }

        double explicitMillis = parsePositiveDouble(props.get("internal_cooldown_ms"));
        if (explicitMillis > 0.0D) {
            return Math.round(explicitMillis);
        }

        double seconds = parsePositiveDouble(firstNonNull(
                props.get("internal_cooldown_seconds"),
                props.get("internal_cooldown")));
        if (seconds > 0.0D) {
            return Math.round(seconds * 1000.0D);
        }

        return -1L;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private double clampDefenseReduction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    private record TrueEdgeSettings(double flatTrueDamage,
            double trueDamagePercent,
            double maxHealthTrueDamagePercent,
            long internalCooldownMillis,
            double monsterTrueDamageCap) {
        private static final TrueEdgeSettings DISABLED = new TrueEdgeSettings(0.0D, 0.0D, 0.0D, 0L, 0.0D);

        private static TrueEdgeSettings disabled() {
            return DISABLED;
        }

        private boolean enabled() {
            return flatTrueDamage > 0.0D || trueDamagePercent > 0.0D || maxHealthTrueDamagePercent > 0.0D;
        }
    }

    private record TrueEdgeComputation(double convertedDamageFromNormal, double reducedTrueDamage) {
        private static final TrueEdgeComputation NONE = new TrueEdgeComputation(0.0D, 0.0D);

        private static TrueEdgeComputation none() {
            return NONE;
        }

        private boolean triggered() {
            return convertedDamageFromNormal > 0.0D || reducedTrueDamage > 0.0D;
        }
    }
}
