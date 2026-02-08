package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.ArchetypePassiveType;
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

                        ArchetypePassiveSnapshot archetypeSnapshot = archetypePassiveManager != null
                                ? archetypePassiveManager.getSnapshot(playerData)
                                : ArchetypePassiveSnapshot.empty();

                        applyHealthRegeneration(playerRef, playerData, statMap, deltaSeconds);
                        applyArchetypeHealthRegeneration(playerData, statMap, deltaSeconds, archetypeSnapshot);
                        applyManaRegeneration(playerData, statMap, deltaSeconds, archetypeSnapshot);
                        applyStaminaRegeneration(playerData, statMap, deltaSeconds);
                        applySignatureGainBonus(playerData, statMap, archetypeSnapshot);
                        applyHealingBonus(playerData, statMap, archetypeSnapshot);
                    }
                });
    }

    private void applyHealthRegeneration(@Nonnull PlayerRef playerRef,
            @Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds) {
        PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
        if (runtimeState == null) {
            return;
        }

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
        double perSecond = archetypeSnapshot.getValue(ArchetypePassiveType.INCREASED_HEALTH_REGEN);
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
            perSecond += archetypeBonus;
        }

        if (perSecond <= 0) {
            return;
        }
        addResource(statMap, DefaultEntityStatTypes.getMana(), perSecond, deltaSeconds);
    }

    private void applyStaminaRegeneration(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            float deltaSeconds) {
        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData,
                PassiveType.STAMINA_REGENERATION);
        if (!snapshot.isUnlocked() || snapshot.value() <= 0) {
            return;
        }
        double perSecond = snapshot.value();
        if (perSecond <= 0) {
            return;
        }
        addResource(statMap, DefaultEntityStatTypes.getStamina(), perSecond, deltaSeconds);
    }

    private void applySignatureGainBonus(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot) {
        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(playerData, PassiveType.SIGNATURE_GAIN);
        PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
        EntityStatValue signatureStat = statMap.get(DefaultEntityStatTypes.getSignatureEnergy());
        if (signatureStat == null || runtimeState == null) {
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

        double passivePercent = snapshot.isUnlocked() && snapshot.value() > 0
                ? snapshot.value() / 100.0D
                : 0.0D;
        double archetypeBonus = Math.max(0.0D,
                archetypeSnapshot.getValue(ArchetypePassiveType.SPECIAL_CHARGE_BONUS));
        double totalBonusFactor = passivePercent + archetypeBonus;

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

    private void applyHealingBonus(@Nonnull PlayerData playerData,
            @Nonnull EntityStatMap statMap,
            @Nonnull ArchetypePassiveSnapshot archetypeSnapshot) {
        PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
        if (runtimeState == null) {
            return;
        }

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
}
