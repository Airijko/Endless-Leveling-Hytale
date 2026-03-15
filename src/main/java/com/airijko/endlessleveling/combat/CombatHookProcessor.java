package com.airijko.endlessleveling.combat;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.types.ExecutionerAugment;
import com.airijko.endlessleveling.augments.types.FirstStrikeAugment;
import com.airijko.endlessleveling.classes.ClassWeaponResolver;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.type.AbsorbPassive;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.passives.type.BerzerkerPassive;
import com.airijko.endlessleveling.passives.type.ExecutionerPassive;
import com.airijko.endlessleveling.passives.type.FirstStrikePassive;
import com.airijko.endlessleveling.passives.type.HealingTouchPassive;
import com.airijko.endlessleveling.passives.type.PartyBuffingAuraPassive;
import com.airijko.endlessleveling.passives.type.PartyShieldingAuraPassive;
import com.airijko.endlessleveling.passives.type.RavenousStrikePassive;
import com.airijko.endlessleveling.passives.type.RetaliationPassive;
import com.airijko.endlessleveling.passives.type.SecondWindPassive;
import com.airijko.endlessleveling.passives.util.PassiveContributionBlueprint;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.ChatMessageTemplate;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Centralized combat hook processing so listeners share ordering between
 * passives and augments.
 */
public final class CombatHookProcessor {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;
    private final MobLevelingManager mobLevelingManager;
    private final AugmentExecutor augmentExecutor;

    public CombatHookProcessor(SkillManager skillManager,
            PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            PlayerDataManager playerDataManager,
            MobLevelingManager mobLevelingManager,
            AugmentExecutor augmentExecutor) {
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.classManager = classManager;
        this.playerDataManager = playerDataManager;
        this.mobLevelingManager = mobLevelingManager;
        this.augmentExecutor = augmentExecutor;
    }

    /**
     * Applies outgoing combat modifiers (passives + augments) and returns the
     * final damage value.
     */
    public OutgoingResult processOutgoing(@Nonnull OutgoingContext ctx) {
        PlayerData playerData = ctx.playerData();
        Damage damage = ctx.damage();
        if (playerData == null || damage == null) {
            return new OutgoingResult(ctx.damage() != null ? ctx.damage().getAmount() : 0f, false, 0.0D);
        }

        PassiveRuntimeState runtimeState = ctx.runtimeState() != null
                ? ctx.runtimeState()
                : passiveManager != null
                        ? passiveManager.getRuntimeState(playerData.getUuid())
                        : null;
        ArchetypePassiveSnapshot archetypeSnapshot = ctx.archetypeSnapshot() != null
                ? ctx.archetypeSnapshot()
                : archetypePassiveManager != null
                        ? archetypePassiveManager.getSnapshot(playerData)
                        : ArchetypePassiveSnapshot.empty();

        FirstStrikePassive firstStrikePassive = FirstStrikePassive.fromSnapshot(archetypeSnapshot);
        BerzerkerPassive berzerkerPassive = BerzerkerPassive.fromSnapshot(archetypeSnapshot);
        ExecutionerPassive executionerPassive = ExecutionerPassive.fromSnapshot(archetypeSnapshot);
        RetaliationPassive retaliationPassive = RetaliationPassive.fromSnapshot(archetypeSnapshot);
        HealingTouchPassive healingTouchPassive = HealingTouchPassive.fromSnapshot(archetypeSnapshot);

        boolean firstStrikeAugmentSelected = hasSelectedAugment(playerData, FirstStrikeAugment.ID);
        boolean executionerAugmentSelected = hasSelectedAugment(playerData, ExecutionerAugment.ID);

        String weaponCategoryKey = ClassWeaponResolver.resolveCategoryKey(ctx.weapon());
        ClassWeaponType weaponType = ClassWeaponResolver.resolve(ctx.weapon());
        boolean usesSorcery = weaponType == ClassWeaponType.STAFF || weaponType == ClassWeaponType.WAND;
        boolean rangedAttack = weaponType == ClassWeaponType.BOW || weaponType == ClassWeaponType.CROSSBOW;

        float weaponMultiplier = classManager != null
                ? (float) classManager.getWeaponDamageMultiplier(playerData, weaponCategoryKey)
                : 1.0f;

        float baseAmount = usesSorcery
                ? skillManager.applySorceryModifier(damage.getAmount(), playerData)
                : skillManager.applyStrengthModifier(damage.getAmount(), playerData);
        SkillManager.CritResult critResult = skillManager.applyCriticalHit(playerData, baseAmount);
        float critDamage = critResult.damage;

        DamageLayerBuffer layerBuffer = new DamageLayerBuffer();
        float prospectiveDamage = critDamage;

        if (runtimeState != null && firstStrikePassive.enabled() && !firstStrikeAugmentSelected) {
            float bonusDamage = firstStrikePassive.apply(runtimeState,
                    ctx.attackerPlayerRef(),
                    critDamage,
                    this::sendPassiveMessage);
            if (bonusDamage > 0f) {
                registerLayerBonus(layerBuffer,
                        resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.FIRST_STRIKE),
                        bonusDamage,
                        critDamage);
                prospectiveDamage += bonusDamage;
            }
        }

        float berzerkerBonus = berzerkerPassive.computeBonus(ctx.attackerRef(), ctx.commandBuffer(), critDamage);
        if (berzerkerBonus > 0f) {
            registerLayerBonus(layerBuffer,
                    resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.BERZERKER),
                    berzerkerBonus,
                    critDamage);
            prospectiveDamage += berzerkerBonus;
        }

        if (runtimeState != null) {
            float retaliationBonus = retaliationPassive.consumeBonus(runtimeState,
                    ctx.attackerPlayerRef(),
                    this::sendPassiveMessage);
            if (retaliationBonus > 0f) {
                registerLayerBonus(layerBuffer,
                        resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.RETALIATION),
                        retaliationBonus,
                        critDamage);
                prospectiveDamage += retaliationBonus;
            }
        }

        float executionerBonus = executionerAugmentSelected
                ? 0f
                : executionerPassive.apply(runtimeState,
                        ctx.attackerPlayerRef(),
                        ctx.targetRef(),
                        ctx.commandBuffer(),
                        prospectiveDamage,
                        this::sendPassiveMessage);
        if (executionerBonus > 0f) {
            registerLayerBonus(layerBuffer,
                    resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.EXECUTIONER),
                    executionerBonus,
                    critDamage);
            prospectiveDamage += executionerBonus;
        }

        float damageBeforeWeapon = layerBuffer.apply(DamageLayer.BONUS, critDamage);
        float finalDamage = damageBeforeWeapon;
        if (weaponMultiplier > 0f && Math.abs(weaponMultiplier - 1.0f) > 0.0001f) {
            finalDamage *= weaponMultiplier;
        }

        EntityStatMap attackerStats = ctx.attackerStats();
        if (attackerStats == null && ctx.commandBuffer() != null && ctx.attackerRef() != null) {
            attackerStats = ctx.commandBuffer().getComponent(ctx.attackerRef(), EntityStatMap.getComponentType());
        }
        EntityStatMap targetStats = ctx.targetStats();
        if (targetStats == null && ctx.commandBuffer() != null && ctx.targetRef() != null) {
            targetStats = ctx.commandBuffer().getComponent(ctx.targetRef(), EntityStatMap.getComponentType());
        }
        double augmentTrueDamageBonus = 0.0D;
        if (augmentExecutor != null) {
            AugmentExecutor.OnHitResult onHitResult = augmentExecutor.applyOnHit(playerData,
                    ctx.attackerRef(),
                    ctx.targetRef(),
                    ctx.commandBuffer(),
                    attackerStats,
                    targetStats,
                    finalDamage,
                    critResult.isCrit,
                    rangedAttack,
                    weaponType);
            finalDamage = onHitResult.damage();
            augmentTrueDamageBonus = Math.max(0.0D, onHitResult.trueDamageBonus());
        }

        if (runtimeState != null) {
            double auraDamageBonus = PartyBuffingAuraPassive.currentDamageBonus(runtimeState,
                    System.currentTimeMillis());
            if (auraDamageBonus > 0.0D) {
                float beforeAura = finalDamage;
                finalDamage = (float) (finalDamage * (1.0D + auraDamageBonus));
                LOGGER.atFine().log(
                        "[BUFFING_AURA] Applied outgoing multiplier for %s: base=%.3f bonus=%.3f final=%.3f.",
                        playerData.getUuid(),
                        beforeAura,
                        auraDamageBonus,
                        finalDamage);
            } else {
                LOGGER.atFiner().log("[BUFFING_AURA] No outgoing bonus for %s (active bonus %.3f).",
                        playerData.getUuid(),
                        auraDamageBonus);
            }
        } else {
            LOGGER.atFine().log("[BUFFING_AURA] No runtime state while processing outgoing damage for %s.",
                    playerData.getUuid());
        }

        if (runtimeState != null
                && finalDamage > 0f
                && archetypeSnapshot.getValue(ArchetypePassiveType.SHIELDING_AURA) > 0.0D) {
            runtimeState.setLastDamageDealtMillis(System.currentTimeMillis());
        }

        healingTouchPassive.apply(playerData,
                ctx.attackerRef(),
                ctx.commandBuffer(),
                attackerStats,
                skillManager,
                finalDamage);

        ArmyOfTheDeadPassive.focusCurrentTarget(playerData, ctx.targetRef(), ctx.commandBuffer());
        ArmyOfTheDeadPassive.markOnHitTrigger(playerData, archetypeSnapshot);

        applyLifeSteal(playerData, ctx.attackerRef(), ctx.commandBuffer(), archetypeSnapshot, finalDamage);
        if (passiveManager != null) {
            passiveManager.markCombat(playerData.getUuid());
        }

        return new OutgoingResult(finalDamage, critResult.isCrit, augmentTrueDamageBonus);
    }

    /**
     * Applies incoming combat modifiers (defense + passives + augments) and
     * returns the adjusted damage and resistance used.
     */
    public IncomingResult processIncoming(@Nonnull IncomingContext ctx) {
        PlayerData defender = ctx.defender();
        Damage damage = ctx.damage();
        if (defender == null || damage == null) {
            return new IncomingResult(damage != null ? damage.getAmount() : 0f, 0f,
                    damage != null ? damage.getAmount() : 0f);
        }

        PassiveRuntimeState runtimeState = ctx.runtimeState() != null
                ? ctx.runtimeState()
                : passiveManager != null
                        ? passiveManager.getRuntimeState(defender.getUuid())
                        : null;
        ArchetypePassiveSnapshot archetypeSnapshot = ctx.archetypeSnapshot() != null
                ? ctx.archetypeSnapshot()
                : archetypePassiveManager != null
                        ? archetypePassiveManager.getSnapshot(defender)
                        : ArchetypePassiveSnapshot.empty();

        FirstStrikePassive firstStrikePassive = FirstStrikePassive.fromSnapshot(archetypeSnapshot);
        RetaliationPassive retaliationPassive = RetaliationPassive.fromSnapshot(archetypeSnapshot);
        AbsorbPassive absorbPassive = AbsorbPassive.fromSnapshot(archetypeSnapshot);
        SecondWindPassive secondWindPassive = SecondWindPassive.fromSnapshot(archetypeSnapshot);
        boolean firstStrikeAugmentSelected = hasSelectedAugment(defender, FirstStrikeAugment.ID);

        float originalAmount = damage.getAmount();
        float resistance = skillManager != null ? skillManager.calculatePlayerDefense(defender) : 0f;
        resistance = Math.max(-0.95f, Math.min(0.95f, resistance));
        float defenseReducedAmount = originalAmount * (1.0f - resistance);
        float levelDifferenceReduction = resolvePlayerCombatScalingReduction(ctx, defender);
        float levelDifferenceReducedAmount = defenseReducedAmount * (1.0f - levelDifferenceReduction);

        float postAugmentAmount = levelDifferenceReducedAmount;
        if (augmentExecutor != null && ctx.statMap() != null) {
            postAugmentAmount = augmentExecutor.applyOnDamageTaken(defender,
                    ctx.defenderRef(),
                    ctx.attackerRef(),
                    ctx.commandBuffer(),
                    ctx.statMap(),
                    levelDifferenceReducedAmount);
        }

        if (runtimeState != null && postAugmentAmount > 0f) {
            postAugmentAmount = PartyShieldingAuraPassive.absorbIncomingDamage(runtimeState, postAugmentAmount);
        }

        if (runtimeState != null) {
            postAugmentAmount = absorbPassive.apply(runtimeState,
                    ctx.defenderPlayer(),
                    postAugmentAmount,
                    this::sendPassiveMessage);
        }

        float adjustedAmount = postAugmentAmount;
        if (runtimeState != null && ctx.statMap() != null && !archetypeSnapshot.isEmpty()) {
            adjustedAmount = secondWindPassive.tryTrigger(runtimeState,
                    ctx.defenderPlayer(),
                    ctx.statMap(),
                    postAugmentAmount,
                    this::sendPassiveMessage);
        }

        double drLevel = defenseReducedAmount > 0f
                ? Math.max(0.0D, Math.min(1.0D, 1.0D - (levelDifferenceReducedAmount / defenseReducedAmount)))
                : 0.0D;
        double dr2 = levelDifferenceReducedAmount > 0f
                ? Math.max(0.0D, Math.min(1.0D, 1.0D - (postAugmentAmount / levelDifferenceReducedAmount)))
                : 0.0D;
        LOGGER.atFine().log(
                "Incoming DR chain: player=%s base=%.2f dr1(defense)=%.2f%% afterDr1=%.2f dr2(levelDiff)=%.2f%% afterDr2=%.2f dr3(augments)=%.2f%% afterDr3=%.2f final=%.2f",
                defender != null ? defender.getUuid() : "unknown",
                originalAmount,
                resistance * 100.0f,
                defenseReducedAmount,
                drLevel * 100.0D,
                levelDifferenceReducedAmount,
                dr2 * 100.0D,
                postAugmentAmount,
                adjustedAmount);

        if (runtimeState != null) {
            runtimeState.setLastDamageTakenMillis(System.currentTimeMillis());
            retaliationPassive.onDamageTaken(runtimeState, adjustedAmount);
        }

        if (runtimeState != null && !firstStrikeAugmentSelected) {
            firstStrikePassive.suppressOnHit(runtimeState);
        }

        if (passiveManager != null) {
            passiveManager.markCombat(defender.getUuid());
        }

        return new IncomingResult(originalAmount, resistance, adjustedAmount);
    }

    private float resolvePlayerCombatScalingReduction(@Nonnull IncomingContext ctx, @Nonnull PlayerData defender) {
        if (mobLevelingManager == null || ctx.commandBuffer() == null || ctx.defenderRef() == null) {
            return 0.0f;
        }

        int defenderLevel = Math.max(1, defender.getLevel());
        int attackerLevel = resolveIncomingAttackerLevel(ctx, defenderLevel);
        if (attackerLevel <= 0) {
            attackerLevel = defenderLevel;
        }

        double reduction = mobLevelingManager.getPlayerCombatDefenseReductionForLevels(
                ctx.defenderRef(),
                ctx.commandBuffer(),
                attackerLevel,
                defenderLevel);
        if (Double.isNaN(reduction) || Double.isInfinite(reduction)) {
            return 0.0f;
        }
        return (float) Math.max(-0.95D, Math.min(0.95D, reduction));
    }

    private int resolveIncomingAttackerLevel(@Nonnull IncomingContext ctx, int fallbackLevel) {
        Ref<EntityStore> attackerRef = ctx.attackerRef();
        if (attackerRef == null || !EntityRefUtil.isUsable(attackerRef) || ctx.commandBuffer() == null) {
            return fallbackLevel;
        }

        PlayerRef attackerPlayer = EntityRefUtil.tryGetComponent(ctx.commandBuffer(), attackerRef,
                PlayerRef.getComponentType());
        if (attackerPlayer != null && attackerPlayer.isValid()) {
            if (playerDataManager != null) {
                PlayerData attackerData = playerDataManager.get(attackerPlayer.getUuid());
                if (attackerData != null) {
                    return Math.max(1, attackerData.getLevel());
                }
            }
            return fallbackLevel;
        }

        if (mobLevelingManager != null) {
            int mobLevel = mobLevelingManager.resolveMobLevel(attackerRef, ctx.commandBuffer());
            if (mobLevel > 0) {
                return mobLevel;
            }
        }
        return fallbackLevel;
    }

    private void applyLifeSteal(@Nonnull PlayerData playerData,
            @Nonnull Ref<EntityStore> attackerRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot,
            float damageDealt) {
        if (passiveManager == null || damageDealt <= 0) {
            return;
        }

        double lifeStealPercent = Math.max(0.0D, archetypeSnapshot.getValue(ArchetypePassiveType.LIFE_STEAL));
        double ravenousStrikeHeal = RavenousStrikePassive.resolveHeal(archetypeSnapshot, playerData, skillManager);
        if (lifeStealPercent <= 0.0D && ravenousStrikeHeal <= 0.0D) {
            return;
        }

        double healPercent = lifeStealPercent / 100.0D;
        double healAmount = (damageDealt * healPercent) + ravenousStrikeHeal;
        if (healAmount <= 0) {
            return;
        }

        EntityStatMap statMap = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef,
                EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return;
        }

        float currentHealth = healthStat.get();
        float maxHealth = healthStat.getMax();
        float updatedHealth = (float) Math.min(maxHealth, currentHealth + healAmount);
        if (updatedHealth > currentHealth) {
            statMap.setStatValue(DefaultEntityStatTypes.getHealth(), updatedHealth);
        }
    }

    private PassiveContributionBlueprint resolveBlueprint(ArchetypePassiveSnapshot snapshot,
            ArchetypePassiveType type) {
        List<RacePassiveDefinition> definitions = snapshot == null
                ? List.of()
                : snapshot.getDefinitions(type);
        return PassiveContributionBlueprint.fromDefinitions(type, definitions);
    }

    private void registerLayerBonus(DamageLayerBuffer buffer,
            PassiveContributionBlueprint blueprint,
            float bonusDamage,
            float baseDamage) {
        if (buffer == null || blueprint == null || bonusDamage <= 0f) {
            return;
        }
        if (baseDamage <= 0f) {
            buffer.addFlat(blueprint.layer(), bonusDamage);
            return;
        }
        double percent = bonusDamage / baseDamage;
        buffer.addPercent(blueprint.layer(), blueprint.tag(), percent, blueprint.stackingStyle());
    }

    private void sendPassiveMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        PlayerChatNotifier.send(playerRef, ChatMessageTemplate.PASSIVE_GENERIC, text);
    }

    private boolean hasSelectedAugment(PlayerData playerData, String augmentId) {
        if (playerData == null || augmentId == null || augmentId.isBlank()) {
            return false;
        }
        for (String selectedId : playerData.getSelectedAugmentsSnapshot().values()) {
            if (selectedId != null && augmentId.equalsIgnoreCase(selectedId.trim())) {
                return true;
            }
        }
        return false;
    }

    public record OutgoingContext(PlayerData playerData,
            PlayerRef attackerPlayerRef,
            Ref<EntityStore> attackerRef,
            Ref<EntityStore> targetRef,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage,
            ItemStack weapon,
            PassiveRuntimeState runtimeState,
            ArchetypePassiveSnapshot archetypeSnapshot,
            EntityStatMap attackerStats,
            EntityStatMap targetStats) {
    }

    public record OutgoingResult(float finalDamage, boolean critical, double trueDamageBonus) {
    }

    public record IncomingContext(PlayerData defender,
            PlayerRef defenderPlayer,
            Ref<EntityStore> defenderRef,
            Ref<EntityStore> attackerRef,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage,
            PassiveRuntimeState runtimeState,
            ArchetypePassiveSnapshot archetypeSnapshot,
            EntityStatMap statMap) {
    }

    public record IncomingResult(float originalDamage, float resistance, float finalDamage) {
    }
}
