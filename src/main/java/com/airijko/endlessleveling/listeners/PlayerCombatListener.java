package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.classes.ClassWeaponResolver;
import com.airijko.endlessleveling.combat.DamageLayerBuffer;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.settings.BerzerkerSettings;
import com.airijko.endlessleveling.passives.settings.ExecutionerSettings;
import com.airijko.endlessleveling.passives.settings.FirstStrikeSettings;
import com.airijko.endlessleveling.passives.settings.RetaliationSettings;
import com.airijko.endlessleveling.passives.util.PassiveContributionBlueprint;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Listens for player-inflicted damage and applies EndlessLeveling combat logic.
 */
public class PlayerCombatListener extends DamageEventSystem {
    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final ClassManager classManager;
    private final AugmentExecutor augmentExecutor;

    public PlayerCombatListener(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull SkillManager skillManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            ClassManager classManager,
            AugmentExecutor augmentExecutor) {
        this.playerDataManager = playerDataManager;
        this.skillManager = skillManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.classManager = classManager;
        this.augmentExecutor = augmentExecutor;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        // Run before vanilla damage is applied
        return Set.of(new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        // Only notify if the source is a player
        if (damage.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attackerRef = entitySource.getRef();
            Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
            PlayerRef attackerPlayer = commandBuffer.getComponent(attackerRef, PlayerRef.getComponentType());
            if (attackerPlayer != null && attackerPlayer.isValid()) {
                PlayerData playerData = playerDataManager.get(attackerPlayer.getUuid());
                if (playerData != null) {
                    PassiveRuntimeState runtimeState = passiveManager != null
                            ? passiveManager.getRuntimeState(playerData.getUuid())
                            : null;
                    ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
                            ? archetypePassiveManager.getSnapshot(playerData)
                            : ArchetypePassiveSnapshot.empty();
                    FirstStrikeSettings firstStrikeSettings = FirstStrikeSettings.fromSnapshot(archetypeSnapshot);
                    BerzerkerSettings berzerkerSettings = BerzerkerSettings.fromSnapshot(archetypeSnapshot);
                    ExecutionerSettings executionerSettings = ExecutionerSettings.fromSnapshot(archetypeSnapshot);
                    RetaliationSettings retaliationSettings = RetaliationSettings.fromSnapshot(archetypeSnapshot);

                    Player player = commandBuffer.getComponent(attackerRef, Player.getComponentType());
                    ItemStack weapon = player != null && player.getInventory() != null
                            ? player.getInventory().getItemInHand()
                            : null;

                    ClassWeaponType weaponType = ClassWeaponResolver.resolve(weapon);
                    boolean usesSorcery = weaponType == ClassWeaponType.STAFF || weaponType == ClassWeaponType.WAND;

                    float weaponMultiplier = classManager != null
                            ? (float) classManager.getWeaponDamageMultiplier(playerData, weaponType)
                            : 1.0f;

                    float baseAmount = usesSorcery
                            ? skillManager.applySorceryModifier(damage.getAmount(), playerData)
                            : skillManager.applyStrengthModifier(damage.getAmount(), playerData);
                    SkillManager.CritResult critResult = skillManager.applyCriticalHit(playerData, baseAmount);
                    float bonusLayerBase = critResult.damage;
                    DamageLayerBuffer layerBuffer = new DamageLayerBuffer();
                    float prospectiveDamage = bonusLayerBase;

                    if (runtimeState != null && firstStrikeSettings.enabled()) {
                        float bonusDamage = applyFirstStrike(runtimeState, firstStrikeSettings, attackerPlayer,
                                bonusLayerBase);
                        if (bonusDamage > 0f) {
                            registerLayerBonus(layerBuffer,
                                    resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.FIRST_STRIKE),
                                    bonusDamage,
                                    bonusLayerBase);
                            prospectiveDamage += bonusDamage;
                        }
                    }

                    float berzerkerBonus = computeBerzerkerBonus(berzerkerSettings,
                            attackerRef,
                            commandBuffer,
                            bonusLayerBase);
                    if (berzerkerBonus > 0f) {
                        registerLayerBonus(layerBuffer,
                                resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.BERZERKER),
                                berzerkerBonus,
                                bonusLayerBase);
                        prospectiveDamage += berzerkerBonus;
                    }

                    if (runtimeState != null) {
                        float retaliationBonus = consumeRetaliationBonus(runtimeState,
                                retaliationSettings,
                                attackerPlayer);
                        if (retaliationBonus > 0f) {
                            registerLayerBonus(layerBuffer,
                                    resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.RETALIATION),
                                    retaliationBonus,
                                    bonusLayerBase);
                            prospectiveDamage += retaliationBonus;
                        }
                    }

                    float executionerBonus = applyExecutionerBonus(runtimeState,
                            executionerSettings,
                            attackerPlayer,
                            targetRef,
                            commandBuffer,
                            prospectiveDamage);
                    if (executionerBonus > 0f) {
                        registerLayerBonus(layerBuffer,
                                resolveBlueprint(archetypeSnapshot, ArchetypePassiveType.EXECUTIONER),
                                executionerBonus,
                                bonusLayerBase);
                        prospectiveDamage += executionerBonus;
                    }

                    float damageBeforeWeapon = layerBuffer.apply(DamageLayer.BONUS, bonusLayerBase);
                    float finalDamage = damageBeforeWeapon;
                    if (weaponMultiplier > 0f && Math.abs(weaponMultiplier - 1.0f) > 0.0001f) {
                        finalDamage *= weaponMultiplier;
                    }

                    EntityStatMap attackerStats = commandBuffer.getComponent(attackerRef,
                            EntityStatMap.getComponentType());
                    EntityStatMap targetStats = commandBuffer.getComponent(targetRef,
                            EntityStatMap.getComponentType());
                    boolean rangedAttack = weaponType == ClassWeaponType.BOW || weaponType == ClassWeaponType.CROSSBOW;
                    if (augmentExecutor != null) {
                        finalDamage = augmentExecutor.applyOnHit(playerData,
                                attackerRef,
                                targetRef,
                                commandBuffer,
                                attackerStats,
                                targetStats,
                                finalDamage,
                                critResult.isCrit,
                                rangedAttack,
                                weaponType);
                    }

                    damage.setAmount(finalDamage);
                    applyLifeSteal(attackerRef, commandBuffer, archetypeSnapshot, finalDamage);
                    passiveManager.markCombat(playerData.getUuid());
                }
            }
        }
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
        playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
    }
}