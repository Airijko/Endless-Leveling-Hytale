package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
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
    private final ArchetypePassiveManager archetypePassiveManager;

    public XpEventListener(PlayerDataManager playerDataManager,
            LevelingManager levelingManager,
            PartyManager partyManager,
            PassiveManager passiveManager,
            MobLevelingManager mobLevelingManager,
            ArchetypePassiveManager archetypePassiveManager) {
        this.playerDataManager = playerDataManager;
        this.levelingManager = levelingManager;
        this.partyManager = partyManager;
        this.passiveManager = passiveManager;
        this.mobLevelingManager = mobLevelingManager;
        this.archetypePassiveManager = archetypePassiveManager;
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

        UUID playerUuid = null;

        if (deathInfo.getSource() instanceof Damage.EntitySource entitySource) {
            var attackerRef = entitySource.getRef();
            if (attackerRef != null && attackerRef.isValid()) {
                PlayerRef player = store.getComponent(attackerRef, PlayerRef.getComponentType());
                if (player != null && player.isValid()) {
                    playerUuid = player.getUuid();
                }
            }
        }

        if (playerUuid == null)
            return;

        var playerData = playerDataManager.get(playerUuid);
        if (playerData == null)
            return;

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null)
            return;

        var healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null)
            return;

        boolean mobIsBlacklisted = mobLevelingManager != null
                && mobLevelingManager.isEntityBlacklisted(ref, store, commandBuffer);

        int mobLevel = mobLevelingManager != null ? mobLevelingManager.resolveMobLevel(ref, commandBuffer) : 1;

        float cachedMaxHealth = mobLevelingManager != null
                ? mobLevelingManager.getEntityMaxHealthSnapshot(ref.getIndex())
                : -1.0f;
        double maxHealthForXp = cachedMaxHealth > 0.0f ? cachedMaxHealth : healthStat.getMax();
        double baseXp = Math.max(1.0, maxHealthForXp);
        double xpAfterKillRules = levelingManager.applyMobKillXpRules(playerData, mobLevel, baseXp, mobIsBlacklisted);
        if (xpAfterKillRules <= 0.0) {
            String mobLevelText = mobIsBlacklisted ? "N/A" : Integer.toString(Math.max(1, mobLevel));
            LOGGER.atFine().log("XP gain blocked for player %s due to level gap (player=%d, mob=%s)",
                    playerUuid, playerData.getLevel(), mobLevelText);
            return;
        }

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();
        double archetypeXpBonus = snapshot.getValue(ArchetypePassiveType.XP_BONUS);

        double disciplineBonusPercent = 0.0D;
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        SkillManager skillManager = plugin != null ? plugin.getSkillManager() : null;
        if (skillManager != null) {
            disciplineBonusPercent = skillManager.getDisciplineXpBonusPercent(playerData);
        }

        double additiveBonus = archetypeXpBonus + (disciplineBonusPercent / 100.0D);
        double projectedPersonalXp = xpAfterKillRules * Math.max(0.0D, 1.0D + additiveBonus);
        double killRulesMultiplier = baseXp > 0.0D ? xpAfterKillRules / baseXp : 0.0D;

        double totalLuck = getLuckValue(playerData, snapshot);

        LOGGER.atInfo().log(
                "XP-Report target=%d player=%s playerLvl=%d mobLvl=%d blacklisted=%s sourceMaxHP=%.3f (cached=%.3f live=%.3f) baseXP=%.3f killRulesXP=%.3f killRulesMult=%.4f archetypeXpBonus=%.4f disciplineBonusPct=%.3f additiveMult=%.4f projectedPersonalXP=%.3f luck=%.4f luckAffectsXp=%s",
                ref.getIndex(),
                playerUuid,
                playerData.getLevel(),
                mobLevel,
                mobIsBlacklisted,
                maxHealthForXp,
                cachedMaxHealth,
                healthStat.getMax(),
                baseXp,
                xpAfterKillRules,
                killRulesMultiplier,
                archetypeXpBonus,
                disciplineBonusPercent,
                Math.max(0.0D, 1.0D + additiveBonus),
                projectedPersonalXp,
                totalLuck,
                false);

        LOGGER.atInfo().log("Granting XP (before party share): %f to player %s", xpAfterKillRules, playerUuid);

        if (partyManager != null) {
            double partyShareRange = mobLevelingManager != null
                    ? mobLevelingManager.getPartyXpShareRange(store)
                    : 25.0D;
            partyManager.handleXpGainInRange(playerUuid, xpAfterKillRules, partyShareRange);
        } else {
            levelingManager.addXp(playerUuid, xpAfterKillRules);
        }
        if (passiveManager != null && totalLuck > 0.0D) {
            passiveManager.openMobDropWindow(playerUuid);
        }
    }

    private double getLuckValue(PlayerData playerData, ArchetypePassiveSnapshot snapshot) {
        double archetypeLuck = snapshot == null ? 0.0D : snapshot.getValue(ArchetypePassiveType.LUCK);
        if (passiveManager == null || playerData == null) {
            return archetypeLuck;
        }
        var passiveSnapshot = passiveManager.getSnapshot(playerData, PassiveType.LUCK);
        double innateLuck = passiveSnapshot != null && passiveSnapshot.isUnlocked() ? passiveSnapshot.value() : 0.0D;
        return archetypeLuck + innateLuck;
    }
}
