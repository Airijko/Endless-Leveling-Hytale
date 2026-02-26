package com.airijko.endlessleveling.combat;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.classes.ClassWeaponResolver;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.BerzerkerSettings;
import com.airijko.endlessleveling.passives.settings.ExecutionerSettings;
import com.airijko.endlessleveling.passives.settings.FirstStrikeSettings;
import com.airijko.endlessleveling.passives.settings.RetaliationSettings;
import com.airijko.endlessleveling.passives.util.PassiveContributionBlueprint;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

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
    private final AugmentExecutor augmentExecutor;

    public CombatHookProcessor(SkillManager skillManager,
            PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            AugmentExecutor augmentExecutor) {
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.classManager = classManager;
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
            return new OutgoingResult(ctx.damage() != null ? ctx.damage().getAmount() : 0f, false);
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

        FirstStrikeSettings firstStrikeSettings = FirstStrikeSettings.fromSnapshot(archetypeSnapshot);
        BerzerkerSettings berzerkerSettings = BerzerkerSettings.fromSnapshot(archetypeSnapshot);
        ExecutionerSettings executionerSettings = ExecutionerSettings.fromSnapshot(archetypeSnapshot);
        RetaliationSettings retaliationSettings = RetaliationSettings.fromSnapshot(archetypeSnapshot);

        ClassWeaponType weaponType = ClassWeaponResolver.resolve(ctx.weapon());
        boolean usesSorcery = weaponType == ClassWeaponType.STAFF || weaponType == ClassWeaponType.WAND;
        boolean rangedAttack = weaponType == ClassWeaponType.BOW || weaponType == ClassWeaponType.CROSSBOW;

        float weaponMultiplier = classManager != null
                ? (float) classManager.getWeaponDamageMultiplier(playerData, weaponType)
                : 1.0f;

        float baseAmount = usesSorcery
                ? skillManager.applySorceryModifier(damage.getAmount(), playerData)
                : skillManager.applyStrengthModifier(damage.getAmount(), playerData);
        SkillManager.CritResult critResult = skillManager.applyCriticalHit(playerData, baseAmount);
        float critDamage = critResult.damage;

        DamageLayerBuffer layerBuffer = new DamageLayerBuffer();
        float prospectiveDamage = critDamage;

        if (runtimeState != null && firstStrikeSettings.enabled()) {
            float bonusDamage = applyFirstStrike(runtimeState, firstStrikeSettings, ctx.attackerPlayerRef(),
                    critDamage);
            if (bonusDamage > 0f) {
                registerLayerBonus(layerBuffer,
                        resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.FIRST_STRIKE),
                        bonusDamage,
                        critDamage);
                prospectiveDamage += bonusDamage;
            }
        }

        float berzerkerBonus = computeBerzerkerBonus(berzerkerSettings, ctx.attackerRef(), ctx.commandBuffer(),
                critDamage);
        if (berzerkerBonus > 0f) {
            registerLayerBonus(layerBuffer,
                    resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.BERZERKER),
                    berzerkerBonus,
                    critDamage);
            prospectiveDamage += berzerkerBonus;
        }

        if (runtimeState != null) {
            float retaliationBonus = consumeRetaliationBonus(runtimeState, retaliationSettings,
                    ctx.attackerPlayerRef());
            if (retaliationBonus > 0f) {
                registerLayerBonus(layerBuffer,
                        resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.RETALIATION),
                        retaliationBonus,
                        critDamage);
                prospectiveDamage += retaliationBonus;
            }
        }

        float executionerBonus = applyExecutionerBonus(runtimeState,
                executionerSettings,
                ctx.attackerPlayerRef(),
                ctx.targetRef(),
                ctx.commandBuffer(),
                prospectiveDamage);
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
        if (augmentExecutor != null) {
            finalDamage = augmentExecutor.applyOnHit(playerData,
                    ctx.attackerRef(),
                    ctx.targetRef(),
                    ctx.commandBuffer(),
                    attackerStats,
                    targetStats,
                    finalDamage,
                    critResult.isCrit,
                    rangedAttack,
                    weaponType);
        }

        applyLifeSteal(ctx.attackerRef(), ctx.commandBuffer(), archetypeSnapshot, finalDamage);
        if (passiveManager != null) {
            passiveManager.markCombat(playerData.getUuid());
        }

        return new OutgoingResult(finalDamage, critResult.isCrit);
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

        FirstStrikeSettings firstStrikeSettings = FirstStrikeSettings.fromSnapshot(archetypeSnapshot);
        RetaliationSettings retaliationSettings = RetaliationSettings.fromSnapshot(archetypeSnapshot);

        float originalAmount = damage.getAmount();
        float resistance = skillManager != null ? skillManager.calculatePlayerDefense(defender) : 0f;
        resistance = Math.max(0f, Math.min(0.95f, resistance));
        float defenseReducedAmount = originalAmount * (1.0f - resistance);

        float postAugmentAmount = defenseReducedAmount;
        if (augmentExecutor != null && ctx.statMap() != null) {
            postAugmentAmount = augmentExecutor.applyOnDamageTaken(defender,
                    ctx.defenderRef(),
                    ctx.attackerRef(),
                    ctx.commandBuffer(),
                    ctx.statMap(),
                    defenseReducedAmount);
        }

        float adjustedAmount = postAugmentAmount;
        if (runtimeState != null && ctx.statMap() != null && !archetypeSnapshot.isEmpty()) {
            adjustedAmount = applySecondWind(defender,
                    ctx.defenderPlayer(),
                    runtimeState,
                    archetypeSnapshot,
                    ctx.statMap(),
                    postAugmentAmount);
        }

        double dr2 = defenseReducedAmount > 0f
                ? Math.max(0.0D, Math.min(1.0D, 1.0D - (postAugmentAmount / defenseReducedAmount)))
                : 0.0D;
        LOGGER.atFine().log(
                "Incoming DR chain: player=%s base=%.2f dr1(defense)=%.2f%% afterDr1=%.2f dr2(augments)=%.2f%% afterDr2=%.2f final=%.2f",
                defender != null ? defender.getUuid() : "unknown",
                originalAmount,
                resistance * 100.0f,
                defenseReducedAmount,
                dr2 * 100.0D,
                postAugmentAmount,
                adjustedAmount);

        if (runtimeState != null) {
            handleRetaliation(runtimeState, retaliationSettings, adjustedAmount);
        }

        if (runtimeState != null) {
            suppressFirstStrikeIfHit(runtimeState, firstStrikeSettings);
        }

        if (passiveManager != null) {
            passiveManager.markCombat(defender.getUuid());
        }

        return new IncomingResult(originalAmount, resistance, adjustedAmount);
    }

    private float applyFirstStrike(@Nonnull PassiveRuntimeState runtimeState,
            @Nonnull FirstStrikeSettings settings,
            PlayerRef playerRef,
            float currentDamage) {
        if (!settings.enabled() || currentDamage <= 0) {
            return 0f;
        }

        double bonusPercent = Math.max(0.0D, settings.bonusPercent());
        if (bonusPercent <= 0) {
            return 0f;
        }

        long now = System.currentTimeMillis();
        if (now < runtimeState.getFirstStrikeCooldownExpiresAt()) {
            return 0f;
        }

        float bonusDamage = (float) (currentDamage * bonusPercent);
        if (bonusDamage <= 0) {
            return 0f;
        }

        runtimeState.setFirstStrikeCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setFirstStrikeReadyNotified(false);
        sendPassiveMessage(playerRef,
                String.format("First Strike triggered! Cooldown: %.0fs",
                        settings.cooldownMillis() / 1000.0D));
        return bonusDamage;
    }

    private float computeBerzerkerBonus(@Nonnull BerzerkerSettings settings,
            Ref<EntityStore> attackerRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            float baseDamage) {
        if (!settings.enabled() || attackerRef == null || baseDamage <= 0f) {
            return 0f;
        }
        EntityStatMap statMap = commandBuffer.getComponent(attackerRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            return 0f;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return 0f;
        }
        float max = healthStat.getMax();
        float current = healthStat.get();
        if (max <= 0f || current <= 0f) {
            return 0f;
        }
        float ratio = current / max;
        double totalBonus = 0.0D;
        for (BerzerkerSettings.Entry entry : settings.entries()) {
            double maxBonus = Math.max(0.0D, entry.bonusPercent());
            if (maxBonus <= 0.0D) {
                continue;
            }

            double threshold = Math.min(Math.max(0.0D, entry.thresholdPercent()), 0.999D);
            double scale;
            if (ratio <= threshold) {
                scale = 1.0D;
            } else if (ratio >= 1.0D) {
                scale = 0.0D;
            } else {
                double denominator = 1.0D - threshold;
                scale = denominator <= 0.0D ? 0.0D : (1.0D - ratio) / denominator;
            }

            scale = Math.max(0.0D, Math.min(1.0D, scale));
            totalBonus += maxBonus * scale;
        }
        if (totalBonus <= 0.0D) {
            return 0f;
        }
        float bonusDamage = (float) (baseDamage * totalBonus);
        return bonusDamage > 0f ? bonusDamage : 0f;
    }

    private float consumeRetaliationBonus(@Nonnull PassiveRuntimeState runtimeState,
            @Nonnull RetaliationSettings settings,
            PlayerRef playerRef) {
        if (!settings.enabled()) {
            return 0f;
        }

        long now = System.currentTimeMillis();
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (windowExpiresAt <= 0L) {
            return 0f;
        }
        if (now > windowExpiresAt) {
            runtimeState.setRetaliationWindowExpiresAt(0L);
            runtimeState.setRetaliationDamageStored(0.0D);
            return 0f;
        }
        if (now < runtimeState.getRetaliationCooldownExpiresAt()) {
            return 0f;
        }

        double bonus = runtimeState.getRetaliationDamageStored();
        if (bonus <= 0.0D) {
            return 0f;
        }

        runtimeState.setRetaliationDamageStored(0.0D);
        runtimeState.setRetaliationWindowExpiresAt(0L);
        runtimeState.setRetaliationCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setRetaliationReadyNotified(false);

        sendPassiveMessage(playerRef,
                String.format("Retaliation unleashed! Added %.0f flat damage.", bonus));

        return (float) bonus;
    }

    private float applyExecutionerBonus(PassiveRuntimeState runtimeState,
            @Nonnull ExecutionerSettings settings,
            PlayerRef playerRef,
            Ref<EntityStore> targetRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            float currentDamage) {
        if (runtimeState == null || !settings.enabled() || targetRef == null || currentDamage <= 0f) {
            return 0f;
        }
        EntityStatMap statMap = commandBuffer.getComponent(targetRef, EntityStatMap.getComponentType());
        if (statMap == null) {
            return 0f;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return 0f;
        }
        float current = healthStat.get();
        float max = healthStat.getMax();
        if (max <= 0f || current <= 0f) {
            return 0f;
        }
        float predicted = Math.max(0f, current - currentDamage);
        double bonusPercent = 0.0D;
        boolean execute = false;
        for (ExecutionerSettings.Entry entry : settings.entries()) {
            double threshold = entry.thresholdPercent();
            if (threshold <= 0.0D) {
                continue;
            }
            float thresholdHealth = (float) (max * threshold);
            if (current <= thresholdHealth || predicted <= thresholdHealth) {
                if (entry.isExecute()) {
                    execute = true;
                    break;
                }
                bonusPercent += Math.max(0.0D, entry.bonusPercent());
            }
        }

        if (!execute && bonusPercent <= 0.0D) {
            return 0f;
        }

        long cooldownMillis = settings.cooldownMillis();
        long now = System.currentTimeMillis();
        if (cooldownMillis > 0 && now < runtimeState.getExecutionerCooldownExpiresAt()) {
            return 0f;
        }

        float bonusDamage = execute ? Math.max(0f, current) : (float) (currentDamage * bonusPercent);
        if (bonusDamage <= 0f) {
            return 0f;
        }

        if (cooldownMillis > 0) {
            runtimeState.setExecutionerCooldownExpiresAt(now + cooldownMillis);
            runtimeState.setExecutionerReadyNotified(false);
        }

        sendPassiveMessage(playerRef,
                execute
                        ? "Executioner triggered! Target executed."
                        : String.format("Executioner triggered! +%.0f%% damage.", bonusPercent * 100.0D));

        return bonusDamage;
    }

    private void applyLifeSteal(@Nonnull Ref<EntityStore> attackerRef,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot,
            float damageDealt) {
        if (passiveManager == null || damageDealt <= 0) {
            return;
        }

        double lifeStealPercent = Math.max(0.0D, archetypeSnapshot.getValue(ArchetypePassiveType.LIFE_STEAL));
        if (lifeStealPercent <= 0.0D) {
            return;
        }

        double healPercent = lifeStealPercent / 100.0D;
        double healAmount = damageDealt * healPercent;
        if (healAmount <= 0) {
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(attackerRef, EntityStatMap.getComponentType());
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

    private float applySecondWind(@Nonnull PlayerData playerData,
            @Nonnull PlayerRef defenderPlayer,
            @Nonnull PassiveRuntimeState runtimeState,
            @Nonnull ArchetypePassiveSnapshot snapshot,
            @Nonnull EntityStatMap statMap,
            float incomingDamage) {
        SecondWindSettings settings = resolveSecondWindSettings(snapshot);
        if (!settings.enabled() || incomingDamage <= 0) {
            return incomingDamage;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return incomingDamage;
        }

        float maxHealth = healthStat.getMax();
        float currentHealth = healthStat.get();
        if (maxHealth <= 0 || currentHealth <= 0) {
            return incomingDamage;
        }

        float predictedHealth = Math.max(0f, currentHealth - incomingDamage);
        if (predictedHealth <= 0f) {
            return incomingDamage;
        }

        float thresholdHealth = (float) (maxHealth * settings.thresholdPercent());
        if (thresholdHealth <= 0 || predictedHealth > thresholdHealth) {
            return incomingDamage;
        }

        long now = System.currentTimeMillis();
        if (now < runtimeState.getSecondWindCooldownExpiresAt()
                || now < runtimeState.getSecondWindActiveUntil()) {
            return incomingDamage;
        }

        float healAmount = (float) Math.max(0.0D, maxHealth * settings.healPercent());
        if (healAmount <= 0f) {
            return incomingDamage;
        }

        double durationSeconds = Math.max(0.1D, settings.durationSeconds());
        double totalHeal = Math.min(healAmount, maxHealth);
        double perSecond = totalHeal / durationSeconds;
        runtimeState.setSecondWindHealPerSecond(perSecond);
        runtimeState.setSecondWindHealRemaining(totalHeal);

        runtimeState.setSecondWindCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setSecondWindActiveUntil(now + settings.durationMillis());
        runtimeState.setSecondWindReadyNotified(false);
        sendPassiveMessage(defenderPlayer,
                String.format("Second Wind triggered! Healing %.0f%% HP over %.0fs",
                        settings.healPercent() * 100.0D,
                        settings.durationSeconds()));
        return incomingDamage;
    }

    private SecondWindSettings resolveSecondWindSettings(ArchetypePassiveSnapshot snapshot) {
        double healPercent = Math.max(0.0D, snapshot.getValue(ArchetypePassiveType.SECOND_WIND));
        if (healPercent <= 0) {
            return SecondWindSettings.disabled();
        }

        List<RacePassiveDefinition> definitions = snapshot.getDefinitions(ArchetypePassiveType.SECOND_WIND);
        double thresholdSum = 0.0D;
        int thresholdSources = 0;
        double durationSum = 0.0D;
        int durationSources = 0;
        double cooldownSum = 0.0D;
        int cooldownSources = 0;

        for (RacePassiveDefinition definition : definitions) {
            Map<String, Object> props = definition.properties();
            double thresholdValue = parsePositiveDouble(props.get("threshold"));
            if (thresholdValue > 0.0D) {
                thresholdSum += thresholdValue;
                thresholdSources++;
            }
            double durationValue = parsePositiveDouble(props.get("duration"));
            if (durationValue > 0.0D) {
                durationSum += durationValue;
                durationSources++;
            }
            double cooldownCandidate = parsePositiveDouble(props.get("cooldown"));
            if (cooldownCandidate > 0.0D) {
                cooldownSum += cooldownCandidate;
                cooldownSources++;
            }
        }

        double resolvedThreshold = thresholdSources > 0 ? thresholdSum / thresholdSources : 0.2D;
        double resolvedDuration = durationSources > 0 ? durationSum / durationSources : 5.0D;
        double resolvedCooldown = cooldownSources > 0 ? cooldownSum / cooldownSources : 60.0D;
        return new SecondWindSettings(true, healPercent, resolvedThreshold, resolvedDuration, resolvedCooldown);
    }

    private double parsePositiveDouble(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value > 0 ? value : 0.0D;
        }
        if (raw instanceof String string) {
            try {
                double parsed = Double.parseDouble(string.trim());
                return parsed > 0 ? parsed : 0.0D;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0D;
    }

    private void handleRetaliation(@Nonnull PassiveRuntimeState runtimeState,
            @Nonnull RetaliationSettings settings,
            float damageTaken) {
        double reflectPercent = Math.max(0.0D, settings.reflectPercent());
        if (!settings.enabled() || reflectPercent <= 0.0D) {
            clearRetaliationState(runtimeState);
            return;
        }
        if (damageTaken <= 0f) {
            return;
        }

        double contribution = damageTaken * reflectPercent;
        if (contribution <= 0.0D) {
            return;
        }

        long now = System.currentTimeMillis();
        expireRetaliationWindowIfNeeded(runtimeState, now);

        if (now < runtimeState.getRetaliationCooldownExpiresAt()) {
            return;
        }

        long windowMillis = settings.windowMillis();
        if (windowMillis <= 0L) {
            return;
        }

        double stored = runtimeState.getRetaliationDamageStored();
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (stored <= 0.0D || windowExpiresAt <= 0L || now > windowExpiresAt) {
            runtimeState.setRetaliationDamageStored(contribution);
            runtimeState.setRetaliationWindowExpiresAt(now + windowMillis);
        } else {
            runtimeState.setRetaliationDamageStored(stored + contribution);
        }
    }

    private void expireRetaliationWindowIfNeeded(@Nonnull PassiveRuntimeState runtimeState, long now) {
        long windowExpiresAt = runtimeState.getRetaliationWindowExpiresAt();
        if (windowExpiresAt > 0L && now > windowExpiresAt) {
            runtimeState.setRetaliationWindowExpiresAt(0L);
            runtimeState.setRetaliationDamageStored(0.0D);
        }
    }

    private void clearRetaliationState(@Nonnull PassiveRuntimeState runtimeState) {
        runtimeState.setRetaliationWindowExpiresAt(0L);
        runtimeState.setRetaliationDamageStored(0.0D);
        runtimeState.setRetaliationCooldownExpiresAt(0L);
        runtimeState.setRetaliationReadyNotified(true);
    }

    private void suppressFirstStrikeIfHit(PassiveRuntimeState runtimeState,
            FirstStrikeSettings settings) {
        if (runtimeState == null || !settings.enabled() || settings.cooldownMillis() <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < runtimeState.getFirstStrikeCooldownExpiresAt()) {
            return;
        }

        runtimeState.setFirstStrikeCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setFirstStrikeReadyNotified(false);
    }

    private void sendPassiveMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
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

    public record OutgoingResult(float finalDamage, boolean critical) {
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

    private record SecondWindSettings(boolean enabled,
            double healPercent,
            double thresholdPercent,
            double durationSeconds,
            double cooldownSeconds) {

        static SecondWindSettings disabled() {
            return new SecondWindSettings(false, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        long durationMillis() {
            return (long) Math.max(0L, Math.round(durationSeconds * 1000.0D));
        }

        long cooldownMillis() {
            return (long) Math.max(0L, Math.round(cooldownSeconds * 1000.0D));
        }
    }
}
