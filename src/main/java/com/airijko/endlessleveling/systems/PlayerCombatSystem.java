package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.augments.types.BailoutAugment;
import com.airijko.endlessleveling.augments.types.FortressAugment;
import com.airijko.endlessleveling.augments.types.NestingDollAugment;
import com.airijko.endlessleveling.augments.types.ProtectiveBubbleAugment;
import com.airijko.endlessleveling.augments.types.RebirthAugment;
import com.airijko.endlessleveling.augments.types.UndyingRageAugment;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.passives.type.TrueBoltsPassive;
import com.airijko.endlessleveling.passives.type.TrueEdgePassive;
import com.airijko.endlessleveling.leveling.XpKillCreditTracker;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.airijko.endlessleveling.util.FirstStrikeTriggerEffects;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final boolean PASSIVE_DEBUG = Boolean
            .parseBoolean(System.getProperty("el.passive.debug", "true"));
        private static final String DEBUG_SECTION_MOB_COMMON_OFFENSE = "mob_common_offense";
    private static final String DEBUG_SECTION_MOB_COMMON_DEFENSE = "mob_common_defense";
    public static final MetaKey<Boolean> AUGMENT_DOT_DAMAGE = Damage.META_REGISTRY
            .registerMetaObject(data -> Boolean.FALSE);
    public static final MetaKey<Boolean> AUGMENT_PROC_DAMAGE = Damage.META_REGISTRY
            .registerMetaObject(data -> Boolean.FALSE);
    private static final double MOB_DEFENSE_MAX_REDUCTION_PERCENT = 80.0D;
        private static final long PLAYER_HIT_LOG_COOLDOWN_MS = 1500L;
        private static final long MOB_OFFENSE_LOG_COOLDOWN_MS = 2000L;
        private static final long MOB_DEFENSE_LOG_COOLDOWN_MS = 2000L;

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final AugmentExecutor augmentExecutor;
    private final MobAugmentExecutor mobAugmentExecutor;
    private final MobLevelingManager mobLevelingManager;
    private final CombatHookProcessor combatHookProcessor;
    private final Map<Integer, Long> playerHitLogTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> mobOffenseLogTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Long> mobDefenseLogTimes = new ConcurrentHashMap<>();

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
        TrueDamageSettings trueDamageSettings = resolveTrueDamageSettings(archetypeSnapshot);
        boolean trueEdgeReady = isTrueEdgeOffCooldown(runtimeState, now, trueDamageSettings);
        TrueEdgeComputation trueEdge = TrueEdgeComputation.none();
        adjusted = incomingBeforeDefense;

        if (mobLevelingManager != null
            && mobLevelingManager.isMobLevelingEnabled()
            && mobLevelingManager.isMobDefenseScalingEnabled(targetRef.getStore())) {
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

        // Re-resolve using the final matchup reduction (mob or player). Percent-based
        // true damage is additive bonus damage, while level-difference defense remains
        // the sole true-damage reducer.
        trueEdge = (!bypassOutgoingAugmentMath && trueEdgeReady)
                ? computeTrueEdgeConversion(
                        targetRef,
                        commandBuffer,
                        trueDamageSettings,
                        incomingBeforeDefense,
                        playerLevel,
                        reduction,
                        targetIsPlayer)
                : TrueEdgeComputation.none();
        if (!bypassOutgoingAugmentMath && trueEdgeReady && trueEdge.triggered() && trueDamageSettings.sourceType() != null) {
            if (trueDamageSettings.sourceType() == ArchetypePassiveType.TRUE_BOLTS) {
                FirstStrikeTriggerEffects.play(targetRef, commandBuffer);
            }
            logPassiveTrigger(playerData,
                    trueDamageSettings.sourceType(),
                    "bonus_true=%.2f true=%.2f",
                    trueEdge.bonusTrueDamageFromPercent(),
                    trueEdge.reducedTrueDamage());
        }
        if (!bypassOutgoingAugmentMath
                && trueEdgeReady
                && trueEdge.triggered()
                && runtimeState != null
                && trueDamageSettings.internalCooldownMillis() > 0L) {
            setTrueEdgeCooldownExpiresAt(runtimeState,
                    trueDamageSettings,
                    now + trueDamageSettings.internalCooldownMillis());
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
        if (appliedTrueDamage > 0f) {
            ArchetypePassiveType sourceType = trueDamageSettings.sourceType();
            if (sourceType != null) {
                logPassiveTrigger(playerData,
                        sourceType,
                        "applied_true=%.2f",
                        appliedTrueDamage);
            }
        }

        float predictedPostHp = Float.NaN;
        boolean predictedLethal = false;
        if (Float.isFinite(targetHp)) {
            predictedPostHp = targetHp - finalAdjusted - appliedTrueDamage;
            predictedLethal = predictedPostHp <= 0.0001f;
        }

        long nowMillis = System.currentTimeMillis();
        if (shouldEmitCooldownLog(playerHitLogTimes, targetRef.getIndex(), nowMillis, PLAYER_HIT_LOG_COOLDOWN_MS)) {
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
        }

        damage.setAmount(finalAdjusted);
    }

    private float applyMobAugmentsIfPresent(Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            EntityStatMap targetStats,
            float incomingDamage) {
        boolean offenseDebugEnabled = isDebugSectionEnabled(DEBUG_SECTION_MOB_COMMON_OFFENSE);
        boolean defenseDebugEnabled = isDebugSectionEnabled(DEBUG_SECTION_MOB_COMMON_DEFENSE);
        if (targetRef == null
                || targetStats == null
                || incomingDamage <= 0f
                || mobAugmentExecutor == null
                || mobLevelingManager == null) {
            return incomingDamage;
        }

        List<String> augmentIds = mobLevelingManager.getMobOverrideAugmentIds(targetRef, store, commandBuffer);
        if (augmentIds.isEmpty()) {
            if (defenseDebugEnabled) {
                LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE] target=%d skipped=no_mob_augments incoming=%.3f",
                        targetRef.getIndex(),
                        incomingDamage);
            }
            return incomingDamage;
        }

            int targetIndex = targetRef.getIndex();
            long nowMillis = System.currentTimeMillis();
            boolean offenseLogThisHit = offenseDebugEnabled
                && shouldEmitCooldownLog(mobOffenseLogTimes, targetIndex, nowMillis, MOB_OFFENSE_LOG_COOLDOWN_MS);
            boolean defenseLogThisHit = defenseDebugEnabled
                && shouldEmitCooldownLog(mobDefenseLogTimes, targetIndex, nowMillis, MOB_DEFENSE_LOG_COOLDOWN_MS);

            if (offenseLogThisHit) {
            Map<String, Integer> allAugmentCounts = new TreeMap<>();
            Map<String, Double> commonAttributeTotals = new TreeMap<>();
            int commonCount = 0;
            for (String augmentId : augmentIds) {
                String baseAugmentId = CommonAugment.resolveBaseAugmentId(augmentId);
                if (baseAugmentId == null || baseAugmentId.isBlank()) {
                    baseAugmentId = "unknown";
                }
                allAugmentCounts.merge(baseAugmentId.toLowerCase(Locale.ROOT), 1, Integer::sum);

                boolean isCommonBase = CommonAugment.ID.equalsIgnoreCase(baseAugmentId);
                if (isCommonBase) {
                    commonCount++;
                }

                CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(augmentId);
                if (offer == null) {
                    continue;
                }
                String attributeKey = offer.attributeKey() == null ? "unknown" : offer.attributeKey();
                double rolledValue = Double.isFinite(offer.rolledValue()) ? offer.rolledValue() : 0.0D;
                commonAttributeTotals.merge(attributeKey, rolledValue, Double::sum);
            }

            StringBuilder groupedAll = new StringBuilder();
            boolean firstAll = true;
            for (Map.Entry<String, Integer> entry : allAugmentCounts.entrySet()) {
                if (!firstAll) {
                    groupedAll.append(", ");
                }
                groupedAll.append(entry.getKey())
                        .append("=")
                        .append(entry.getValue());
                firstAll = false;
            }

            StringBuilder groupedCommon = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Double> entry : commonAttributeTotals.entrySet()) {
                String key = entry.getKey();
                double total = entry.getValue();
                if (!first) {
                    groupedCommon.append(", ");
                }
                groupedCommon.append(key)
                        .append("=")
                        .append(String.format(Locale.ROOT, "%.3f", total));
                first = false;
            }

            LOGGER.atInfo().log(
                    "[MOB_COMMON_OFFENSE][AUGMENT_SUMMARY] target=%d totalAugments=%d commonAugments=%d groupedAll={%s} groupedCommon={%s}",
                    targetIndex,
                    augmentIds.size(),
                    commonCount,
                    groupedAll,
                    groupedCommon);

                List<String> parsedCommon = new ArrayList<>();
                List<String> nonCommon = new ArrayList<>();
                for (String augmentId : augmentIds) {
                    CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(augmentId);
                    if (offer == null) {
                        nonCommon.add(augmentId);
                        continue;
                    }

                    String key = offer.attributeKey() == null || offer.attributeKey().isBlank()
                            ? "unknown"
                            : offer.attributeKey();
                    double value = Double.isFinite(offer.rolledValue()) ? offer.rolledValue() : 0.0D;
                    parsedCommon.add(String.format(Locale.ROOT, "%s=%.3f", key, value));
                }

                String parsedText = parsedCommon.isEmpty() ? "none" : String.join(", ", parsedCommon);
                String groupedText = groupedCommon.length() == 0 ? "none" : groupedCommon.toString();
                String nonCommonText = nonCommon.isEmpty() ? "none" : String.join(", ", nonCommon);

                LOGGER.atInfo().log(
                    "[MOB_COMMON_OFFENSE][AUGMENT_LIST] target=%d parsedCommon=[%s] groupedTotals=[%s] nonCommon=[%s]",
                    targetIndex,
                    parsedText,
                    groupedText,
                    nonCommonText);
        }

        UUID mobUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (mobUuid == null) {
            if (defenseLogThisHit) {
                LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE] target=%d skipped=missing_target_uuid augmentCount=%d incoming=%.3f",
                        targetIndex,
                        augmentIds.size(),
                        incomingDamage);
            }
            return incomingDamage;
        }

        if (!mobAugmentExecutor.hasMobAugments(mobUuid)) {
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin != null && plugin.getAugmentManager() != null && plugin.getAugmentRuntimeManager() != null) {
                mobAugmentExecutor.registerMobAugments(mobUuid,
                        augmentIds,
                        plugin.getAugmentManager(),
                        plugin.getAugmentRuntimeManager());
                if (offenseLogThisHit) {
                    LOGGER.atInfo().log("[MOB_OVERRIDE_AUGMENTS] target=%d uuid=%s augmentCount=%d",
                        targetIndex, mobUuid, augmentIds.size());
                }
            }
        }

            double defensePercent = Math.max(0.0D,
                Math.min(MOB_DEFENSE_MAX_REDUCTION_PERCENT,
                    mobAugmentExecutor.getAttributeBonus(mobUuid, SkillAttributeType.DEFENSE)));
            float afterDefense = (float) (incomingDamage * (1.0D - (defensePercent / 100.0D)));
            if (defenseLogThisHit) {
                float defenseReductionAmount = Math.max(0.0f, incomingDamage - afterDefense);
                LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE] target=%d base=%.3f reduced=%.3f afterDefense=%.3f defense=%.2f%%",
                        targetIndex,
                        incomingDamage,
                        defenseReductionAmount,
                        afterDefense,
                        defensePercent);

                int commonCount = 0;
                double commonLifeForceTotal = 0.0D;
                for (String augmentId : augmentIds) {
                    String baseAugmentId = CommonAugment.resolveBaseAugmentId(augmentId);
                    if (CommonAugment.ID.equalsIgnoreCase(baseAugmentId)) {
                        commonCount++;
                    }
                    CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(augmentId);
                    if (offer == null) {
                        continue;
                    }
                    if ("life_force".equalsIgnoreCase(offer.attributeKey()) && Double.isFinite(offer.rolledValue())) {
                        commonLifeForceTotal += offer.rolledValue();
                    }
                }

                EntityStatValue hp = targetStats.get(DefaultEntityStatTypes.getHealth());
                float currentHp = hp != null ? hp.get() : Float.NaN;
                float currentMax = hp != null ? hp.getMax() : Float.NaN;
                MobLevelingManager.MobHealthCompositionSnapshot snapshot =
                    mobLevelingManager.getEntityHealthCompositionSnapshot(targetIndex);
                if (snapshot != null) {
                    LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE][LIFE_FORCE_EXPECTED] target=%d source=authoritative commonCount=%d lifeForceTotal=%.3f hp=%.3f max=%.3f expectedBasePlusScaledMax=%.3f expectedCombinedMax=%.3f snapshotLifeForce=%.3f snapshotAgeMs=%d maxDelta=%.3f",
                        targetIndex,
                        commonCount,
                        commonLifeForceTotal,
                        currentHp,
                        currentMax,
                        snapshot.scaledMax(),
                        snapshot.combinedMax(),
                        snapshot.lifeForceBonus(),
                        Math.max(0L, System.currentTimeMillis() - snapshot.updatedAtMillis()),
                        Float.isFinite(currentMax) ? Math.abs(currentMax - snapshot.combinedMax()) : Float.NaN);
                } else {
                    float expectedBasePlusScaledMax = Float.isFinite(currentMax)
                        ? (float) Math.max(1.0D, currentMax - Math.max(0.0D, commonLifeForceTotal))
                        : Float.NaN;
                    LOGGER.atInfo().log(
                        "[MOB_COMMON_DEFENSE][LIFE_FORCE_EXPECTED] target=%d source=inferred commonCount=%d lifeForceTotal=%.3f hp=%.3f max=%.3f expectedBasePlusScaledMax=%.3f expectedCombinedMax=%.3f",
                        targetIndex,
                        commonCount,
                        commonLifeForceTotal,
                        currentHp,
                        currentMax,
                        expectedBasePlusScaledMax,
                        currentMax);
                }
            }

        float afterDamageTaken = mobAugmentExecutor.applyOnDamageTaken(
                mobUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                afterDefense);
        float finalDamage = mobAugmentExecutor.applyOnLowHp(
                mobUuid,
                targetRef,
                attackerRef,
                commandBuffer,
                targetStats,
                afterDamageTaken);

            if (defenseLogThisHit && (finalDamage != incomingDamage || Math.abs(afterDefense - incomingDamage) > 0.0001f)) {
                LOGGER.atInfo().log("MobAugments target=%d damage %.3f -> %.3f (afterDefense=%.3f, defense=%.2f%%)",
                    targetIndex,
                    incomingDamage,
                    finalDamage,
                    afterDefense,
                    defensePercent);
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

                for (String lowHpAugmentId : List.of(
                        RebirthAugment.ID,
                        FortressAugment.ID,
                        UndyingRageAugment.ID,
                        NestingDollAugment.ID,
                        BailoutAugment.ID)) {
                    float afterLowHp = augmentExecutor.applySpecificOnLowHp(defenderData,
                            targetRef,
                            attackerRef,
                            commandBuffer,
                            targetStats,
                            (float) trueDamageAmount,
                            lowHpAugmentId);
                    trueDamageAmount = Math.max(0.0D, afterLowHp);
                    if (trueDamageAmount <= 0.0D) {
                        return 0.0f;
                    }
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
            TrueDamageSettings settings,
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

        // True damage always scales from the hit's pre-defense value. Only
        // level-difference reduction may reduce the final direct HP loss.
        double bonusTrueDamageFromPercent = Math.max(0.0D, baseOutgoing * settings.trueDamagePercent());
        double maxHealthTrueDamage = computeMaxHealthTrueDamage(
                targetRef,
                commandBuffer,
                settings.maxHealthTrueDamagePercent());
        double rawTrueDamage = settings.flatTrueDamage() + bonusTrueDamageFromPercent + maxHealthTrueDamage;
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
            return new TrueEdgeComputation(bonusTrueDamageFromPercent, 0.0D);
        }

        return new TrueEdgeComputation(bonusTrueDamageFromPercent, reducedTrueDamage);
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

    private boolean isTrueEdgeOffCooldown(PassiveRuntimeState runtimeState,
            long now,
            TrueDamageSettings settings) {
        if (settings == null || settings.internalCooldownMillis() <= 0L) {
            return true;
        }
        if (runtimeState == null) {
            return true;
        }
        long cooldownExpiresAt = settings.sourceType() == ArchetypePassiveType.TRUE_BOLTS
                ? runtimeState.getTrueBoltsCooldownExpiresAt()
                : runtimeState.getTrueEdgeCooldownExpiresAt();
        return now >= cooldownExpiresAt;
    }

    private void setTrueEdgeCooldownExpiresAt(PassiveRuntimeState runtimeState,
            TrueDamageSettings settings,
            long expiresAt) {
        if (runtimeState == null || settings == null) {
            return;
        }
        if (settings.sourceType() == ArchetypePassiveType.TRUE_BOLTS) {
            runtimeState.setTrueBoltsCooldownExpiresAt(expiresAt);
            return;
        }
        runtimeState.setTrueEdgeCooldownExpiresAt(expiresAt);
    }

    private TrueDamageSettings resolveTrueDamageSettings(ArchetypePassiveSnapshot snapshot) {
        if (snapshot == null) {
            return TrueDamageSettings.disabled();
        }

        TrueBoltsPassive trueBolts = TrueBoltsPassive.fromSnapshot(snapshot);
        if (trueBolts.enabled()) {
            return new TrueDamageSettings(
                    ArchetypePassiveType.TRUE_BOLTS,
                    trueBolts.flatTrueDamage(),
                    trueBolts.trueDamagePercent(),
                    trueBolts.maxHealthTrueDamagePercent(),
                    trueBolts.internalCooldownMillis(),
                    trueBolts.monsterTrueDamageCap());
        }

        TrueEdgePassive trueEdge = TrueEdgePassive.fromSnapshot(snapshot);
        if (trueEdge.enabled()) {
            return new TrueDamageSettings(
                    ArchetypePassiveType.TRUE_EDGE,
                    trueEdge.flatTrueDamage(),
                    trueEdge.trueDamagePercent(),
                    trueEdge.maxHealthTrueDamagePercent(),
                    trueEdge.internalCooldownMillis(),
                    trueEdge.monsterTrueDamageCap());
        }

        return TrueDamageSettings.disabled();
    }

    private double resolveTrueEdgeReduction(Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            int attackerLevel,
            double mobReduction,
            boolean targetIsPlayer) {
        if (mobLevelingManager == null
                || !mobLevelingManager.isMobLevelingEnabled()
                || !mobLevelingManager.isMobDefenseScalingEnabled(targetRef != null ? targetRef.getStore() : null)) {
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
        return clampDefenseReduction(mobLevelingManager.getMobDefenseReductionForLevelDifference(
            targetRef != null ? targetRef.getStore() : null,
            levelDifference));
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

    private double clampDefenseReduction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    private void logPassiveTrigger(PlayerData playerData,
            ArchetypePassiveType type,
            String detailFormat,
            Object... detailArgs) {
        if (!PASSIVE_DEBUG || playerData == null || type == null) {
            return;
        }
        String detail = detailFormat == null ? "triggered" : String.format(detailFormat, detailArgs);
        LOGGER.atInfo().log("[PASSIVE_DEBUG] player=%s passive=%s %s",
                playerData.getUuid(),
                type.name(),
                detail);
    }

    private boolean isDebugSectionEnabled(String sectionKey) {
        if (sectionKey == null || sectionKey.isBlank()) {
            return false;
        }
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getConfigManager() == null) {
            return false;
        }

        Object raw = plugin.getConfigManager().get("logging.debug_sections", List.of(), false);
        if (raw == null) {
            raw = List.of();
        }

        Collection<?> sections = null;
        if (raw instanceof Collection<?> collection) {
            sections = collection;
        } else if (raw instanceof String str) {
            String trimmed = str.trim();
            if (!trimmed.isEmpty()) {
                sections = List.of(trimmed.split(","));
            }
        }

        if (sections == null || sections.isEmpty()) {
            raw = plugin.getConfigManager().get("debug_sections", List.of(), false);
            if (raw instanceof Collection<?> collection) {
                sections = collection;
            } else if (raw instanceof String str) {
                String trimmed = str.trim();
                if (!trimmed.isEmpty()) {
                    sections = List.of(trimmed.split(","));
                }
            }
        }

        if (sections == null || sections.isEmpty()) {
            return false;
        }

        String normalizedKey = sectionKey.trim().toLowerCase(Locale.ROOT);
        String systemsKey = "systems." + normalizedKey;
        String fqSystemsKey = "com.airijko.endlessleveling.systems." + normalizedKey;
        for (Object section : sections) {
            if (section == null) {
                continue;
            }
            String normalizedSection = String.valueOf(section).trim().toLowerCase(Locale.ROOT);
            if (normalizedSection.equals(normalizedKey)
                    || normalizedSection.equals(systemsKey)
                    || normalizedSection.equals(fqSystemsKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldEmitCooldownLog(Map<Integer, Long> logTimes,
            int entityId,
            long nowMillis,
            long cooldownMillis) {
        if (logTimes == null || cooldownMillis <= 0L) {
            return true;
        }
        Long previous = logTimes.get(entityId);
        if (previous != null && nowMillis - previous < cooldownMillis) {
            return false;
        }
        logTimes.put(entityId, nowMillis);
        return true;
    }

    private record TrueDamageSettings(ArchetypePassiveType sourceType,
            double flatTrueDamage,
            double trueDamagePercent,
            double maxHealthTrueDamagePercent,
            long internalCooldownMillis,
            double monsterTrueDamageCap) {
        private static final TrueDamageSettings DISABLED = new TrueDamageSettings(null, 0.0D, 0.0D, 0.0D, 0L, 0.0D);

        private static TrueDamageSettings disabled() {
            return DISABLED;
        }

        private boolean enabled() {
            return flatTrueDamage > 0.0D || trueDamagePercent > 0.0D || maxHealthTrueDamagePercent > 0.0D;
        }
    }

    private record TrueEdgeComputation(double bonusTrueDamageFromPercent, double reducedTrueDamage) {
        private static final TrueEdgeComputation NONE = new TrueEdgeComputation(0.0D, 0.0D);

        private static TrueEdgeComputation none() {
            return NONE;
        }

        private boolean triggered() {
            return bonusTrueDamageFromPercent > 0.0D || reducedTrueDamage > 0.0D;
        }
    }
}
