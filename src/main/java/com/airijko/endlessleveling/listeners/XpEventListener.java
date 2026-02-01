package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

import javax.annotation.Nonnull;

public class XpEventListener extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final PartyManager partyManager;
    private final PassiveManager passiveManager;
    private final MobLevelingManager mobLevelingManager;

    public XpEventListener(PlayerDataManager playerDataManager,
            LevelingManager levelingManager,
            PartyManager partyManager,
            PassiveManager passiveManager,
            MobLevelingManager mobLevelingManager) {
        this.playerDataManager = playerDataManager;
        this.levelingManager = levelingManager;
        this.partyManager = partyManager;
        this.passiveManager = passiveManager;
        this.mobLevelingManager = mobLevelingManager;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        LOGGER.atInfo().log("onComponentAdded called for entity: %s", ref);

        var deathInfo = component.getDeathInfo();
        if (deathInfo == null)
            return;

        if (!(deathInfo.getSource() instanceof Damage.EntitySource entitySource))
            return;

        var attackerRef = entitySource.getRef();
        if (!attackerRef.isValid())
            return;

        PlayerRef player = store.getComponent(attackerRef, PlayerRef.getComponentType());
        if (player == null)
            return;

        UUID playerUuid = player.getUuid();
        var playerData = playerDataManager.get(playerUuid);
        if (playerData == null)
            return;

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null)
            return;

        var healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null)
            return;

        int mobLevel = mobLevelingManager != null ? mobLevelingManager.resolveMobLevel(ref, commandBuffer) : 1;

        double xpGained = Math.max(1, healthStat.getMax());
        xpGained = levelingManager.applyMobKillXpRules(playerData, mobLevel, xpGained);
        if (xpGained <= 0.0) {
            LOGGER.atFine().log("XP gain blocked for player %s due to level gap (player=%d, mob=%d)",
                    playerUuid, playerData.getLevel(), mobLevel);
            return;
        }
        LOGGER.atInfo().log("Granting XP (before party share): %f to player %s", xpGained, playerUuid);

        if (partyManager != null) {
            partyManager.handleXpGain(playerUuid, xpGained);
        } else {
            levelingManager.addXp(playerUuid, xpGained);
        }

        if (passiveManager != null && passiveManager.getLuckValue(playerData) > 0.0D) {
            passiveManager.openMobDropWindow(playerUuid);
        }
    }
}
