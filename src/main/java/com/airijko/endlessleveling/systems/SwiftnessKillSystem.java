package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.passives.type.FocusedStrikePassive;
import com.airijko.endlessleveling.passives.settings.SwiftnessSettings;
import com.airijko.endlessleveling.passives.type.FinalIncantationPassive;
import com.airijko.endlessleveling.util.EntityRefUtil;
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
    private final SkillManager skillManager;
    private final AugmentExecutor augmentExecutor;

    public SwiftnessKillSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull PassiveManager passiveManager,
            ArchetypePassiveManager archetypePassiveManager,
            @Nonnull SkillManager skillManager,
            AugmentExecutor augmentExecutor) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.skillManager = skillManager;
        this.augmentExecutor = augmentExecutor;
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
        if (!EntityRefUtil.isUsable(attackerRef)) {
            return;
        }

        PlayerRef attackerPlayer = EntityRefUtil.tryGetComponent(store, attackerRef, PlayerRef.getComponentType());
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

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (augmentExecutor != null) {
            augmentExecutor.handleKill(playerData, attackerRef, ref, commandBuffer, statMap);
        }

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();

        FocusedStrikePassive.fromSnapshot(snapshot).resetCooldownOnKill(runtimeState);
        FinalIncantationPassive.fromSnapshot(snapshot).reduceCooldownOnKill(runtimeState);

        SwiftnessSettings settings = SwiftnessSettings.fromSnapshot(snapshot);
        if (!settings.enabled()) {
            return;
        }
        if (settings.triggerOnHit()) {
            return;
        }

        long durationMillis = settings.durationMillis();
        if (durationMillis <= 0L) {
            return;
        }

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
        refreshMovementSpeed(attackerRef, store, playerData);

        if (wasInactive) {
            double totalBonusPercent = settings.totalBonusPercent(newStacks) * 100.0D;
            sendSwiftnessActivatedMessage(attackerPlayer, settings.durationSeconds(), totalBonusPercent);
        }
    }

    private void refreshMovementSpeed(Ref<EntityStore> attackerRef,
            Store<EntityStore> store,
            PlayerData playerData) {
        if (skillManager == null || store == null || attackerRef == null || playerData == null
                || !attackerRef.isValid()) {
            return;
        }
        try {
            skillManager.applyMovementSpeedModifier(attackerRef, store, playerData);
        } catch (Exception ignored) {
        }
    }

    private void sendSwiftnessActivatedMessage(PlayerRef playerRef, double durationSeconds, double bonusPercent) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        String durationText = formatDuration(durationSeconds);
        String bonusText = bonusPercent > 0.0D
                ? String.format("+%.0f%% movement speed", bonusPercent)
                : "Movement speed increased";
        String text = durationText == null
                ? "Swiftness activated! " + bonusText + "."
                : String.format("Swiftness activated! %s for %s.", bonusText, durationText);
        playerRef.sendMessage(Message.raw(text).color("#4fd7f7"));
    }

    private String formatDuration(double durationSeconds) {
        if (durationSeconds <= 0.0D) {
            return null;
        }
        if (Math.abs(durationSeconds - Math.rint(durationSeconds)) < 0.05D) {
            return String.format("%.0fs", durationSeconds);
        }
        return String.format("%.1fs", durationSeconds);
    }
}
