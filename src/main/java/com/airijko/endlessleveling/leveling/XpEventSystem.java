package com.airijko.endlessleveling.leveling;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

import javax.annotation.Nonnull;
import com.airijko.endlessleveling.drops.LuckDoubleDropSystem;

public class XpEventSystem extends DeathSystems.OnDeathSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final PartyManager partyManager;
    private final PassiveManager passiveManager;
    private final MobLevelingManager mobLevelingManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final LuckDoubleDropSystem luckDoubleDropSystem;

    public XpEventSystem(PlayerDataManager playerDataManager,
            LevelingManager levelingManager,
            PartyManager partyManager,
            PassiveManager passiveManager,
            MobLevelingManager mobLevelingManager,
            ArchetypePassiveManager archetypePassiveManager,
            LuckDoubleDropSystem luckDoubleDropSystem) {
        this.playerDataManager = playerDataManager;
        this.levelingManager = levelingManager;
        this.partyManager = partyManager;
        this.passiveManager = passiveManager;
        this.mobLevelingManager = mobLevelingManager;
        this.archetypePassiveManager = archetypePassiveManager;
        this.luckDoubleDropSystem = luckDoubleDropSystem;
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
        if (ArmyOfTheDeadPassive.isManagedSummon(ref, store, commandBuffer)) {
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        var deathInfo = component.getDeathInfo();
        if (deathInfo == null) {
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        UUID playerUuid = null;

        if (deathInfo.getSource() instanceof Damage.EntitySource entitySource) {
            var attackerRef = entitySource.getRef();
            if (EntityRefUtil.isUsable(attackerRef)) {
                PlayerRef player = EntityRefUtil.tryGetComponent(store, attackerRef, PlayerRef.getComponentType());
                if (player != null && player.isValid()) {
                    playerUuid = player.getUuid();
                } else {
                    playerUuid = ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(attackerRef, store, commandBuffer);
                }
            }
        }

        if (playerUuid == null) {
            playerUuid = XpKillCreditTracker.resolveRecentKiller(ref, store, commandBuffer);
        }

        if (playerUuid == null) {
            PlayerRef deadPlayerRef = EntityRefUtil.tryGetComponent(store, ref, PlayerRef.getComponentType());
            if (deadPlayerRef == null || !deadPlayerRef.isValid()) {
                Object source = deathInfo.getSource();
                String sourceName = source == null ? "unknown" : source.getClass().getSimpleName();
                LOGGER.atWarning().log(
                        "[MOB_LUCK] onDeath: no killer resolved for mob target=%d (source=%s); skipping XP and mob-drop registration",
                        ref.getIndex(), sourceName);
            }
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        PlayerRef deadPlayerRef = EntityRefUtil.tryGetComponent(store, ref, PlayerRef.getComponentType());
        Object source = deathInfo.getSource();
        String sourceName = source == null ? "unknown" : source.getClass().getSimpleName();
        LOGGER.atFine().log("[MOB_LUCK] onDeath: killer=%s target=%d targetIsPlayer=%s source=%s",
                playerUuid, ref.getIndex(), deadPlayerRef != null && deadPlayerRef.isValid(), sourceName);

        var playerData = playerDataManager.get(playerUuid);
        if (playerData == null) {
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        var healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        boolean mobIsBlacklisted = mobLevelingManager != null
                && mobLevelingManager.isEntityBlacklisted(ref, store, commandBuffer);

        boolean worldXpBlacklisted = mobLevelingManager != null
            && mobLevelingManager.isWorldXpBlacklisted(store);

        if (worldXpBlacklisted) {
            String worldId = mobLevelingManager != null ? mobLevelingManager.resolveWorldIdentifier(store) : "unknown";
            LOGGER.atFine().log("XP gain blocked for player %s in world %s (matched XP_Blacklisted_Worlds)",
                playerUuid,
                worldId == null ? "unknown" : worldId);
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
            return;
        }

        Integer snapshotMobLevel = mobLevelingManager != null
            ? mobLevelingManager.getEntityResolvedLevelSnapshot(ref, store, commandBuffer)
            : null;
        int mobLevel = snapshotMobLevel != null
            ? snapshotMobLevel
            : (mobLevelingManager != null ? mobLevelingManager.resolveMobLevel(ref, commandBuffer) : 1);
        String mobLevelSource = snapshotMobLevel != null ? "snapshot" : "fallback";

        float cachedMaxHealth = mobLevelingManager != null
            ? mobLevelingManager.getEntityMaxHealthSnapshot(ref, store, commandBuffer)
            : -1.0f;
        float liveMaxHealth = healthStat.getMax();
        boolean hasFiniteCached = Float.isFinite(cachedMaxHealth) && cachedMaxHealth > 0.0f;
        boolean hasFiniteLive = Float.isFinite(liveMaxHealth) && liveMaxHealth > 0.0f;
        // Use the larger finite value so late/passive health layers are not undercounted
        // when the cached snapshot lags behind the live stat value at death time.
        double maxHealthForXp = hasFiniteCached && hasFiniteLive
            ? Math.max(cachedMaxHealth, liveMaxHealth)
            : (hasFiniteCached ? cachedMaxHealth : (hasFiniteLive ? liveMaxHealth : 1.0f));
        double baseXp = Math.max(1.0, maxHealthForXp);
        double xpAfterKillRules = levelingManager.applyMobKillXpRules(
            playerData,
            mobLevel,
            baseXp,
            mobIsBlacklisted,
            store,
            mobLevelingManager);
        if (xpAfterKillRules <= 0.0) {
            String mobLevelText = mobIsBlacklisted ? "N/A" : Integer.toString(Math.max(1, mobLevel));
            LOGGER.atFine().log("XP gain blocked for player %s due to level gap (player=%d, mob=%s)",
                    playerUuid, playerData.getLevel(), mobLevelText);
            XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
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

        double totalLuck = getLuckValue(playerData, snapshot);
        double luckXpBonusPercent = levelingManager != null
                ? levelingManager.getLuckXpBonusPercent(totalLuck)
                : 0.0D;

        double additiveBonus = archetypeXpBonus
                + (disciplineBonusPercent / 100.0D)
                + (luckXpBonusPercent / 100.0D);
        double projectedPersonalXp = xpAfterKillRules * Math.max(0.0D, 1.0D + additiveBonus);
        double killRulesMultiplier = baseXp > 0.0D ? xpAfterKillRules / baseXp : 0.0D;

        LOGGER.atInfo().log(
            "XP-Report target=%d player=%s playerLvl=%d mobLvl=%d mobLvlSource=%s blacklisted=%s sourceMaxHP=%.3f (cached=%.3f live=%.3f) baseXP=%.3f killRulesXP=%.3f killRulesMult=%.4f archetypeXpBonus=%.4f disciplineBonusPct=%.3f luckXpBonusPct=%.3f additiveMult=%.4f projectedPersonalXP=%.3f luck=%.4f luckAffectsXp=%s",
                ref.getIndex(),
                playerUuid,
                playerData.getLevel(),
                mobLevel,
            mobLevelSource,
                mobIsBlacklisted,
                maxHealthForXp,
                cachedMaxHealth,
                healthStat.getMax(),
                baseXp,
                xpAfterKillRules,
                killRulesMultiplier,
                archetypeXpBonus,
                disciplineBonusPercent,
                luckXpBonusPercent,
                Math.max(0.0D, 1.0D + additiveBonus),
                projectedPersonalXp,
                totalLuck,
                true);

        LOGGER.atInfo().log(
            "Granting XP (pre-personal-bonuses, before party share): %.3f to player %s (projected personal XP after bonuses: %.3f)",
            xpAfterKillRules,
            playerUuid,
            projectedPersonalXp);

        if (partyManager != null) {
            double partyShareRange = levelingManager != null
                    ? levelingManager.getPartyXpShareRange()
                    : 25.0D;
            partyManager.handleMobKillXpGainInRange(
                    playerUuid,
                    xpAfterKillRules,
                    partyShareRange,
                    mobLevel,
                    baseXp,
                    mobIsBlacklisted);
        } else {
            levelingManager.addXp(playerUuid, xpAfterKillRules);
        }
        if (luckDoubleDropSystem != null) {
            PlayerRef targetPlayerRef = EntityRefUtil.tryGetComponent(store, ref, PlayerRef.getComponentType());
            if (targetPlayerRef == null) {
                ItemStack[] mobDrops = resolveMobDrops(component);
                luckDoubleDropSystem.registerMobKillLoot(playerUuid, ref, store, commandBuffer, mobDrops);
            }
        }

        XpKillCreditTracker.clearTarget(ref, store, commandBuffer);
    }

    private double getLuckValue(PlayerData playerData, ArchetypePassiveSnapshot snapshot) {
        if (passiveManager != null && playerData != null) {
            return passiveManager.getLuckValue(playerData);
        }
        return snapshot == null ? 0.0D : snapshot.getValue(ArchetypePassiveType.LUCK);
    }

    private ItemStack[] resolveMobDrops(@Nonnull DeathComponent component) {
        var deathItemLoss = component.getDeathItemLoss();
        if (deathItemLoss != null && deathItemLoss.getItemsLost() != null && deathItemLoss.getItemsLost().length > 0) {
            ItemStack[] drops = deathItemLoss.getItemsLost();
            LOGGER.atFine().log("[MOB_LUCK] resolveMobDrops: using deathItemLoss (%d drops)", drops.length);
            return drops;
        }
        ItemStack[] drops = component.getItemsLostOnDeath();
        LOGGER.atFine().log("[MOB_LUCK] resolveMobDrops: using itemsLostOnDeath (%d drops)",
                drops == null ? 0 : drops.length);
        return drops;
    }
}
