package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.combat.CombatHookProcessor;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatListener extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long TRUE_EDGE_INTERNAL_COOLDOWN_MILLIS = 400L;

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final MobLevelingManager mobLevelingManager;
    private final CombatHookProcessor combatHookProcessor;

    public PlayerCombatListener(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull SkillManager skillManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            AugmentExecutor augmentExecutor,
            MobLevelingManager mobLevelingManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.mobLevelingManager = mobLevelingManager;
        this.combatHookProcessor = new CombatHookProcessor(skillManager,
                passiveManager,
                archetypePassiveManager,
                classManager,
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
        PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
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

        Player player = commandBuffer.getComponent(attackerRef, Player.getComponentType());
        ItemStack weapon = player != null && player.getInventory() != null
                ? player.getInventory().getItemInHand()
                : null;

        EntityStatMap attackerStats = commandBuffer.getComponent(attackerRef, EntityStatMap.getComponentType());
        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());

        CombatHookProcessor.OutgoingResult result = combatHookProcessor.processOutgoing(
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

        float adjusted = result.finalDamage();
        float incomingBeforeDefense = adjusted;
        int mobLevel = -1;
        int playerLevel = Math.max(1, playerData.getLevel());
        double reduction = 0.0D;
        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        boolean targetIsPlayer = targetPlayer != null && targetPlayer.isValid();
        long now = System.currentTimeMillis();
        TrueEdgeSettings trueEdgeSettings = resolveTrueEdgeSettings(archetypeSnapshot);
        boolean trueEdgeReady = isTrueEdgeOffCooldown(runtimeState, now);

        TrueEdgeComputation trueEdge = trueEdgeReady
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
                if (reduction > 0.0D) {
                    adjusted = (float) (adjusted * (1.0D - reduction));
                }
            }
        }

        // Re-resolve using the final matchup reduction (mob or player) so conversion
        // only
        // shifts damage buckets and level-difference defense is the sole true-damage
        // reducer.
        trueEdge = trueEdgeReady
                ? computeTrueEdgeConversion(
                        targetRef,
                        commandBuffer,
                        trueEdgeSettings,
                        incomingBeforeDefense,
                        playerLevel,
                        reduction,
                        targetIsPlayer)
                : TrueEdgeComputation.none();
        if (trueEdgeReady && trueEdge.triggered() && runtimeState != null) {
            runtimeState.setTrueEdgeCooldownExpiresAt(now + TRUE_EDGE_INTERNAL_COOLDOWN_MILLIS);
        }
        double reducedAugmentTrueDamage = applyLevelDifferenceReductionToTrueDamage(
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
        float appliedTrueDamage = applyTrueEdgeDamage(
                attackerRef,
                targetRef,
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

    private float applyTrueEdgeDamage(Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            double trueDamageAmount) {
        if (trueDamageAmount <= 0.0D || targetRef == null || commandBuffer == null) {
            return 0.0f;
        }

        EntityStatMap targetStats = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
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
        double rawTrueDamage = settings.flatTrueDamage() + convertedDamageFromNormal;
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

    private boolean isTrueEdgeOffCooldown(PassiveRuntimeState runtimeState, long now) {
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
        }

        if (flatTrueDamage <= 0.0D && trueDamagePercent <= 0.0D) {
            return TrueEdgeSettings.disabled();
        }
        return new TrueEdgeSettings(flatTrueDamage, trueDamagePercent);
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
            return clamp01(mobReduction);
        }

        PlayerRef targetPlayer = commandBuffer.getComponent(targetRef, PlayerRef.getComponentType());
        if (targetPlayer == null || !targetPlayer.isValid()) {
            return 0.0D;
        }

        PlayerData targetData = playerDataManager.get(targetPlayer.getUuid());
        int targetLevel = targetData == null ? Math.max(1, attackerLevel) : Math.max(1, targetData.getLevel());
        int levelDifference = targetLevel - Math.max(1, attackerLevel);
        return clamp01(mobLevelingManager.getMobDefenseReductionForLevelDifference(levelDifference));
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
            Damage killDamage = sourceRef != null
                    ? new Damage(new Damage.EntitySource(sourceRef), DamageCause.PHYSICAL, Float.MAX_VALUE)
                    : new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, Float.MAX_VALUE);
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

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private record TrueEdgeSettings(double flatTrueDamage, double trueDamagePercent) {
        private static final TrueEdgeSettings DISABLED = new TrueEdgeSettings(0.0D, 0.0D);

        private static TrueEdgeSettings disabled() {
            return DISABLED;
        }

        private boolean enabled() {
            return flatTrueDamage > 0.0D || trueDamagePercent > 0.0D;
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
