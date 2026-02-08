package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.SwiftnessSettings;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Applies Swiftness stacks only after a player-secured kill has been confirmed.
 */
public class SwiftnessKillSystem extends DeathSystems.OnDeathSystem {

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;

    public SwiftnessKillSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        var deathInfo = component.getDeathInfo();
        if (deathInfo == null || !(deathInfo.getSource() instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) {
            return;
        }

        PlayerRef attackerPlayer = store.getComponent(attackerRef, PlayerRef.getComponentType());
        if (attackerPlayer == null || !attackerPlayer.isValid()) {
            return;
        }

        PlayerData playerData = playerDataManager.get(attackerPlayer.getUuid());
        if (playerData == null) {
            return;
        }

        PassiveRuntimeState runtimeState = passiveManager.getRuntimeState(playerData.getUuid());
        if (runtimeState == null) {
            return;
        }

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();
        SwiftnessSettings settings = SwiftnessSettings.fromSnapshot(snapshot);
        if (!settings.enabled()) {
            return;
        }

        long durationMillis = settings.durationMillis();
        if (durationMillis <= 0L) {
            return;
        }

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }
        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null || healthStat.get() > 0f) {
            return;
        }

        boolean wasInactive = runtimeState.getSwiftnessStacks() <= 0
                || System.currentTimeMillis() > runtimeState.getSwiftnessActiveUntil();

        int maxStacks = Math.max(1, settings.maxStacks());
        int currentStacks = Math.max(0, runtimeState.getSwiftnessStacks());
        int newStacks = Math.min(maxStacks, currentStacks + 1);
        runtimeState.setSwiftnessStacks(newStacks);
        runtimeState.setSwiftnessActiveUntil(System.currentTimeMillis() + durationMillis);

        if (wasInactive) {
            sendSwiftnessActivatedMessage(attackerPlayer, settings.durationSeconds());
        }
    }

    private void sendSwiftnessActivatedMessage(PlayerRef playerRef, double durationSeconds) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        String durationText = durationSeconds > 0.0D
                ? String.format("%.0fs", durationSeconds)
                : null;
        String text = durationText == null
                ? "Swiftness activated!"
                : "Swiftness activated! Bonus lasts for " + durationText + ".";
        playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
    }
}
