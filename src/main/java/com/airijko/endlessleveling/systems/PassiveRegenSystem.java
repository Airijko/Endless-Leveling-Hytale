package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.passives.AdrenalineSettings;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 * Applies passive regeneration, mana/stamina recovery, and signature gain
 * bonuses each tick.
 */
public class PassiveRegenSystem extends TickingSystem<EntityStore> {

    private static final long HEALTH_REGEN_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(10);
    public static final double RESOURCE_REGEN_DIVISOR = 5.0D;
    private static final Query<EntityStore> PLAYER_QUERY = Query.any();

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;

    public PassiveRegenSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (passiveManager == null || playerDataManager == null || store == null || store.isShutdown()) {
            return;
        }

        store.forEachChunk(PLAYER_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef == null || !playerRef.isValid()) {
                            continue;
                        }

                        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
                        if (playerData == null) {
                            continue;
                        }

                        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                        if (statMap == null) {
                            continue;
                        }

                        PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
                        if (runtimeState == null) {
                            continue;
                        }

                        ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
                                ? archetypePassiveManager.getSnapshot(playerData)
                                : ArchetypePassiveSnapshot.empty();

                        applyHealthRegeneration(playerRef, playerData, statMap, deltaSeconds, runtimeState);
                        applyArchetypeHealthRegeneration(playerData, statMap, deltaSeconds, archetypeSnapshot);
                        applyManaRegeneration(playerData, statMap, deltaSeconds, archetypeSnapshot);
                        applyAdrenalineStamina(playerRef, statMap, deltaSeconds, archetypeSnapshot, runtimeState);
                        applyStaminaGainBonus(playerData, statMap, runtimeState);
                        expireSwiftnessIfNeeded(runtimeState);
                        applySignatureGainBonus(playerData, statMap, archetypeSnapshot, runtimeState);
                        applyHealingBonus(statMap, archetypeSnapshot, runtimeState);
                        applyLastStandHealing(statMap, runtimeState, deltaSeconds);
                        notifyPassiveCooldowns(playerRef, runtimeState);
                    }
                });
    }

    private void expireSwiftnessIfNeeded(@Nonnull PassiveRuntimeState runtimeState) {
        if (runtimeState == null) {
            return;
        }
        if (runtimeState.getSwiftnessStacks() <= 0) {
            return;
        }
        long activeUntil = runtimeState.getSwiftnessActiveUntil();
        if (activeUntil <= 0L) {
            runtimeState.clearSwiftness();
            return;
        }
        if (System.currentTimeMillis() > activeUntil) {
            runtimeState.clearSwiftness();
        }
    }

    private void applyHealthRegeneration(@Nonnull PlayerRef playerRef,
            @Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds,
            @Nonnull PassiveRuntimeState runtimeState) {

        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData, PassiveType.REGENERATION);
        if (!snapshot.isUnlocked() || snapshot.value() <= 0) {
            runtimeState.setRegenerationActive(false);
            return;
        }
        if (!passiveManager.isOutOfCombat(playerData.getUuid(), HEALTH_REGEN_COOLDOWN_MS)) {
            runtimeState.setRegenerationActive(false);
            return;
        }

        if (!runtimeState.isRegenerationActive()) {
            if (playerData.isHealthRegenNotifEnabled()) {
                sendPassiveRegenNotification(playerRef);
            }
            runtimeState.setRegenerationActive(true);
        }

        addResource(statMap, DefaultEntityStatTypes.getHealth(), snapshot.value(), deltaSeconds);
    }

    private void applyArchetypeHealthRegeneration(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot) {
        if (archetypeSnapshot.isEmpty() || deltaSeconds <= 0) {
            return;
        }
        double percentOverFiveSeconds = archetypeSnapshot.getValue(ArchetypePassiveType.INCREASED_HEALTH_REGEN);
        if (percentOverFiveSeconds <= 0) {
            return;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return;
        }
        float maxHealth = healthStat.getMax();
        if (maxHealth <= 0f) {
            return;
        }

        double totalHeal = maxHealth * percentOverFiveSeconds;
        if (totalHeal <= 0.0D) {
            return;
        }

        double perSecond = totalHeal / RESOURCE_REGEN_DIVISOR;
        if (perSecond <= 0) {
            return;
        }
        addResource(statMap, DefaultEntityStatTypes.getHealth(), perSecond, deltaSeconds);
    }

    private void applyManaRegeneration(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot) {
        double perSecond = 0.0D;
        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData, PassiveType.MANA_REGENERATION);
        if (snapshot.isUnlocked() && snapshot.value() > 0) {
            perSecond += snapshot.value();
        }

        double archetypeBonus = archetypeSnapshot.getValue(ArchetypePassiveType.INCREASED_MANA_REGEN);
        if (archetypeBonus > 0) {
            perSecond += archetypeBonus / RESOURCE_REGEN_DIVISOR;
        }

        if (perSecond <= 0) {
            return;
        }
        addResource(statMap, DefaultEntityStatTypes.getMana(), perSecond, deltaSeconds);
    }

    private void applyStaminaGainBonus(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            @Nonnull PassiveRuntimeState runtimeState) {
        if (runtimeState == null) {
            return;
        }

        EntityStatValue staminaStat = statMap.get(DefaultEntityStatTypes.getStamina());
        if (staminaStat == null) {
            runtimeState.setLastStaminaSample(Float.NaN);
            return;
        }

        float current = staminaStat.get();
        float max = staminaStat.getMax();
        if (max <= 0f) {
            runtimeState.setLastStaminaSample(Float.NaN);
            return;
        }

        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData,
                PassiveType.STAMINA_GAIN_BONUS);
        if (!snapshot.isUnlocked() || snapshot.value() <= 0) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        float lastSample = runtimeState.getLastStaminaSample();
        if (Float.isNaN(lastSample)) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        float delta = current - lastSample;
        if (delta <= 0f) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        double bonusFactor = Math.max(0.0D, snapshot.value()) / 100.0D;
        if (bonusFactor <= 0.0D) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        double bonusAmount = delta * bonusFactor;
        if (bonusAmount <= 0.0D) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        float applied = (float) Math.min(max - current, bonusAmount);
        if (applied <= 0f) {
            runtimeState.setLastStaminaSample(current);
            return;
        }

        float newValue = current + applied;
        statMap.setStatValue(DefaultEntityStatTypes.getStamina(), newValue);
        runtimeState.setLastStaminaSample(newValue);
    }

    private void applyAdrenalineStamina(@Nonnull PlayerRef playerRef,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot,
            @Nonnull PassiveRuntimeState runtimeState) {
        if (deltaSeconds <= 0) {
            return;
        }

        AdrenalineSettings settings = AdrenalineSettings.fromSnapshot(archetypeSnapshot);
        if (!settings.enabled()) {
            clearAdrenalineState(runtimeState);
            return;
        }

        EntityStatValue staminaStat = statMap.get(DefaultEntityStatTypes.getStamina());
        if (staminaStat == null) {
            return;
        }

        float max = staminaStat.getMax();
        if (max <= 0f) {
            return;
        }

        long now = System.currentTimeMillis();
        if (runtimeState.getAdrenalineActiveUntil() > 0 && now >= runtimeState.getAdrenalineActiveUntil()) {
            clearAdrenalineState(runtimeState);
        }

        double perSecond = runtimeState.getAdrenalineRestorePerSecond();
        double remaining = runtimeState.getAdrenalineRestoreRemaining();
        boolean effectActive = perSecond > 0.0D
                && remaining > 0.0D
                && runtimeState.getAdrenalineActiveUntil() > now;

        if (effectActive) {
            float current = staminaStat.get();
            if (current < max) {
                double potential = perSecond * deltaSeconds;
                double allowed = Math.min(remaining, potential);
                if (allowed > 0.0D) {
                    float applied = (float) Math.min(max - current, allowed);
                    if (applied > 0f) {
                        statMap.setStatValue(DefaultEntityStatTypes.getStamina(), current + applied);
                        runtimeState.setAdrenalineRestoreRemaining(remaining - applied);
                    }
                }
            }

            if (runtimeState.getAdrenalineRestoreRemaining() <= 0.0001D
                    || staminaStat.get() >= staminaStat.getMax()) {
                clearAdrenalineState(runtimeState);
            }
            return;
        }

        float ratio = staminaStat.get() / max;
        if (ratio > settings.thresholdPercent()) {
            return;
        }

        if (now < runtimeState.getAdrenalineCooldownExpiresAt()) {
            return;
        }

        double restorePercent = Math.max(0.0D, settings.restorePercent());
        if (restorePercent <= 0.0D) {
            return;
        }

        double totalRestore = Math.min(max, max * restorePercent);
        if (totalRestore <= 0.0D) {
            return;
        }

        double durationSeconds = Math.max(0.1D, settings.durationSeconds());
        double perSecondRestore = totalRestore / durationSeconds;

        runtimeState.setAdrenalineRestorePerSecond(perSecondRestore);
        runtimeState.setAdrenalineRestoreRemaining(totalRestore);
        runtimeState.setAdrenalineActiveUntil(now + settings.durationMillis());
        runtimeState.setAdrenalineCooldownExpiresAt(now + settings.cooldownMillis());
        runtimeState.setAdrenalineReadyNotified(false);

        sendAdrenalineMessage(playerRef,
                restorePercent,
                settings.durationSeconds());
    }

    private void applySignatureGainBonus(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot,
            @Nonnull PassiveRuntimeState runtimeState) {
        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData, PassiveType.SIGNATURE_GAIN);
        EntityStatValue signatureStat = statMap.get(DefaultEntityStatTypes.getSignatureEnergy());
        if (signatureStat == null) {
            return;
        }

        float currentValue = signatureStat.get();
        float lastValue = runtimeState.getLastSignatureValue();
        if (Float.isNaN(lastValue)) {
            runtimeState.setLastSignatureValue(currentValue);
            return;
        }

        float delta = currentValue - lastValue;
        if (delta <= 0) {
            runtimeState.setLastSignatureValue(currentValue);
            return;
        }

        double skillBonusFactor = snapshot.isUnlocked() && snapshot.value() > 0
                ? Math.max(0.0D, snapshot.value() / 100.0D)
                : 0.0D;
        double archetypeBonus = Math.max(0.0D,
                archetypeSnapshot.getValue(ArchetypePassiveType.SPECIAL_CHARGE_BONUS));
        double totalBonusFactor = skillBonusFactor > 0.0D
                ? skillBonusFactor * (1.0D + archetypeBonus)
                : archetypeBonus;

        if (totalBonusFactor <= 0.0D) {
            runtimeState.setLastSignatureValue(currentValue);
            return;
        }

        float bonus = (float) (delta * totalBonusFactor);
        if (bonus <= 0) {
            runtimeState.setLastSignatureValue(currentValue);
            return;
        }

        float maxValue = signatureStat.getMax();
        float newValue = Math.min(maxValue, currentValue + bonus);
        statMap.setStatValue(DefaultEntityStatTypes.getSignatureEnergy(), newValue);
        runtimeState.setLastSignatureValue(newValue);
    }

    private void applyHealingBonus(@Nonnull EntityStatMap statMap,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot,
            @Nonnull PassiveRuntimeState runtimeState) {
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            runtimeState.setLastHealingSample(Float.NaN);
            return;
        }

        double healingBonus = archetypeSnapshot.getValue(ArchetypePassiveType.HEALING_BONUS);
        float currentHealth = healthStat.get();
        float maxHealth = healthStat.getMax();
        float lastSample = runtimeState.getLastHealingSample();

        if (healingBonus <= 0 || currentHealth >= maxHealth) {
            runtimeState.setLastHealingSample(currentHealth);
            return;
        }

        if (Float.isNaN(lastSample)) {
            runtimeState.setLastHealingSample(currentHealth);
            return;
        }

        float delta = currentHealth - lastSample;
        if (delta <= 0) {
            runtimeState.setLastHealingSample(currentHealth);
            return;
        }

        double bonusAmount = delta * Math.max(0.0D, healingBonus);
        if (bonusAmount <= 0) {
            runtimeState.setLastHealingSample(currentHealth);
            return;
        }

        float applied = (float) Math.min(maxHealth - currentHealth, bonusAmount);
        if (applied <= 0) {
            runtimeState.setLastHealingSample(currentHealth);
            return;
        }

        float newValue = currentHealth + applied;
        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), newValue);
        runtimeState.setLastHealingSample(newValue);
    }

    private void addResource(@Nonnull EntityStatMap statMap,
            int statIndex,
            double perSecond,
            float deltaSeconds) {
        if (perSecond <= 0 || deltaSeconds <= 0) {
            return;
        }

        EntityStatValue statValue = statMap.get(statIndex);
        if (statValue == null) {
            return;
        }

        float current = statValue.get();
        float max = statValue.getMax();
        if (current >= max) {
            return;
        }

        float gain = (float) Math.min(max - current, perSecond * deltaSeconds);
        if (gain <= 0) {
            return;
        }

        statMap.setStatValue(statIndex, current + gain);
    }

    private void sendPassiveRegenNotification(@Nonnull PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        var packetHandler = playerRef.getPacketHandler();
        if (packetHandler == null) {
            return;
        }

        Message primary = Message.raw("Passive Regeneration Activated").color("#4fd7f7");
        Message secondary = Message.raw("Health is slowly returning").color("#ffffff");
        var icon = new ItemStack("Consumable_Potion_Health", 1).toPacket();
        NotificationUtil.sendNotification(packetHandler, primary, secondary, icon);
    }

    private void applyLastStandHealing(@Nonnull EntityStatMap statMap,
            PassiveRuntimeState runtimeState,
            float deltaSeconds) {
        if (runtimeState == null || deltaSeconds <= 0) {
            return;
        }

        double perSecond = runtimeState.getLastStandHealPerSecond();
        double remaining = runtimeState.getLastStandHealRemaining();
        if (perSecond <= 0.0D || remaining <= 0.0D) {
            return;
        }

        long activeUntil = runtimeState.getLastStandActiveUntil();
        if (activeUntil > 0 && System.currentTimeMillis() > activeUntil) {
            runtimeState.setLastStandHealPerSecond(0.0D);
            runtimeState.setLastStandHealRemaining(0.0D);
            return;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return;
        }

        float current = healthStat.get();
        float max = healthStat.getMax();
        if (current >= max) {
            return;
        }

        double potential = perSecond * deltaSeconds;
        if (potential <= 0.0D) {
            return;
        }

        double allowed = Math.min(remaining, potential);
        float applied = (float) Math.min(max - current, allowed);
        if (applied <= 0f) {
            return;
        }

        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), current + applied);
        runtimeState.setLastStandHealRemaining(remaining - applied);
        if (runtimeState.getLastStandHealRemaining() <= 0.0001D) {
            runtimeState.setLastStandHealPerSecond(0.0D);
            runtimeState.setLastStandHealRemaining(0.0D);
        }
    }

    private void notifyPassiveCooldowns(PlayerRef playerRef, PassiveRuntimeState runtimeState) {
        if (playerRef == null || !playerRef.isValid() || runtimeState == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!runtimeState.isLastStandReadyNotified()
                && runtimeState.getLastStandCooldownExpiresAt() > 0
                && now >= runtimeState.getLastStandCooldownExpiresAt()) {
            sendCooldownMessage(playerRef, "Last Stand is ready again!");
            runtimeState.setLastStandReadyNotified(true);
        }

        if (!runtimeState.isFirstStrikeReadyNotified()
                && runtimeState.getFirstStrikeCooldownExpiresAt() > 0
                && now >= runtimeState.getFirstStrikeCooldownExpiresAt()) {
            sendCooldownMessage(playerRef, "First Strike is ready again!");
            runtimeState.setFirstStrikeReadyNotified(true);
        }

        if (!runtimeState.isAdrenalineReadyNotified()
                && runtimeState.getAdrenalineCooldownExpiresAt() > 0
                && now >= runtimeState.getAdrenalineCooldownExpiresAt()) {
            sendCooldownMessage(playerRef, "Adrenaline is ready again!");
            runtimeState.setAdrenalineReadyNotified(true);
        }

        if (!runtimeState.isExecutionerReadyNotified()
                && runtimeState.getExecutionerCooldownExpiresAt() > 0
                && now >= runtimeState.getExecutionerCooldownExpiresAt()) {
            sendCooldownMessage(playerRef, "Executioner is ready again!");
            runtimeState.setExecutionerReadyNotified(true);
        }

        if (!runtimeState.isRetaliationReadyNotified()
                && runtimeState.getRetaliationCooldownExpiresAt() > 0
                && now >= runtimeState.getRetaliationCooldownExpiresAt()) {
            sendCooldownMessage(playerRef, "Retaliation is ready again!");
            runtimeState.setRetaliationReadyNotified(true);
        }
    }

    private void sendCooldownMessage(PlayerRef playerRef, String text) {
        if (playerRef == null || !playerRef.isValid() || text == null || text.isBlank()) {
            return;
        }
        playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
    }

    private void clearAdrenalineState(@Nonnull PassiveRuntimeState runtimeState) {
        runtimeState.setAdrenalineRestorePerSecond(0.0D);
        runtimeState.setAdrenalineRestoreRemaining(0.0D);
        runtimeState.setAdrenalineActiveUntil(0L);
    }

    private void sendAdrenalineMessage(PlayerRef playerRef,
            double restorePercent,
            double durationSeconds) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        double percentDisplay = Math.max(0.0D, restorePercent) * 100.0D;
        playerRef.sendMessage(Message.raw(
                String.format("Adrenaline triggered! Restoring %.0f%% stamina over %.0fs",
                        percentDisplay,
                        Math.max(0.0D, durationSeconds)))
                .color("#4fd7f7"));
    }
}
