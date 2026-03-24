package com.airijko.endlessleveling.leveling;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.MobAugmentDiagnostics;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MobLevelingManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int CHUNK_BIT_SHIFT = 5;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final long MOB_LEVEL_DEBUG_COOLDOWN_MS = 5000L;
    private static final Object UNSET_CONFIG_VALUE = new Object();

    private final ConfigManager configManager;
    private final ConfigManager worldsConfigManager;
    private final PlayerDataManager playerDataManager;
    private final Map<Long, Long> mobLevelDebugLogTimes = new ConcurrentHashMap<>();
    private final Map<Long, MobHealthCompositionSnapshot> entityHealthCompositionSnapshots = new ConcurrentHashMap<>();
    private final Map<Long, Integer> entityResolvedLevelSnapshots = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> entityAssignedAugmentSnapshots = new ConcurrentHashMap<>();
    private final Map<Long, TieredInstanceLock> tieredInstanceLocks = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> worldIdentifierCandidatesByStore = new ConcurrentHashMap<>();
    private final Map<Long, Long> worldIdentifierMissRetryAtStore = new ConcurrentHashMap<>();
    private final Map<Long, String> worldOverrideKeyByStore = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> worldXpBlacklistByStore = new ConcurrentHashMap<>();
    /** Anchors each mob's base health to the value captured on the very first applyHealthModifier
     *  call, before any augment percent-health passives have run. Without this cache, augment passives
     *  (e.g. goliath +20%, raid_boss +50%) compound with level scaling on every tick, producing
     *  exponentially-growing HP values. putIfAbsent guarantees the true base is written only once. */
    private final Map<Long, Float> trueBaseHealthCache = new ConcurrentHashMap<>();
    private volatile boolean missingLevelSourceModeWarned;

    private final Map<String, AreaOverride> areaOverrides = new ConcurrentHashMap<>();
    // Stateless design: do not maintain per-entity mutable state in maps.
    private volatile MobAugmentTierPools cachedMobAugmentTierPools;

    private enum LevelSourceMode {
        PLAYER,
        MIXED,
        DISTANCE,
        FIXED,
        TIERS
    }

    private enum PartyLevelCalculation {
        AVERAGE,
        MEDIAN
    }

    private enum TierAdaptationMode {
        ABOVE,
        BELOW,
        BOTH
    }

    private record ViewportCandidate(PlayerRef playerRef, Vector3d position, double distanceSq, int viewRadiusChunks) {
    }

    private record MobAugmentTierPools(int sourceCount,
                                       int blacklistHash,
                                       List<String> elite,
                                       List<String> legendary,
                                       List<String> mythic,
                                       List<String> fallback) {
    }

    private record HighTierRollProfile(double eliteWeight,
                                       double legendaryWeight,
                                       double mythicWeight) {
    }

    private record TieredInstanceLock(int tierOffset,
                                      int levelsPerTier,
                                      long lockedAtMillis,
                                      UUID sourcePlayerUuid) {
    }

    public record MobHealthCompositionSnapshot(float baseMax,
                                               float scaledMax,
                                               float lifeForceBonus,
                                               float combinedMax,
                                               long updatedAtMillis) {
    }

    public MobLevelingManager(PluginFilesManager filesManager, PlayerDataManager playerDataManager) {
        this.configManager = new ConfigManager(filesManager, filesManager.getLevelingFile());
        this.worldsConfigManager = new ConfigManager(filesManager, filesManager.getWorldsFile());
        this.playerDataManager = playerDataManager;
    }

    public void reloadConfig() {
        configManager.load();
        worldsConfigManager.load();
        clearAllEntityLevelOverrides();
        cachedMobAugmentTierPools = null;
    }

    public void syncTierLevelOverridesForDungeon(Store<EntityStore> store, UUID sourcePlayerUuid) {
        if (store == null || sourcePlayerUuid == null || getLevelSourceMode(store) != LevelSourceMode.TIERS) {
            return;
        }

        PlayerRef sourcePlayer = Universe.get().getPlayer(sourcePlayerUuid);
        if (sourcePlayer == null || !sourcePlayer.isValid()) {
            return;
        }

        LevelRange baseRange = parseTieredBaseLevelRange(store);
        int levelsPerTier = Math.max(1, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 20, store));
        int tierOffset = resolveDynamicTierOffsetForPlayer(store, sourcePlayer, baseRange, levelsPerTier);

        tieredInstanceLocks.put(toStoreKey(store),
                new TieredInstanceLock(tierOffset, levelsPerTier, System.currentTimeMillis(), sourcePlayerUuid));
    }

    public Integer resolveMobLevelForEntity(Ref<EntityStore> ref,
                                            Store<EntityStore> store,
                                            CommandBuffer<EntityStore> commandBuffer) {
        return resolveMobLevelForEntity(ref, store, commandBuffer, 1);
    }

    public Integer resolveMobLevelForEntity(Ref<EntityStore> ref,
                                            Store<EntityStore> store,
                                            CommandBuffer<EntityStore> commandBuffer,
                                            int resolveAttempts) {
        if (ref == null) {
            return null;
        }

        if (!isMobLevelingEnabled()) {
            if (shouldLogMobLevelDebug(ref, store != null ? store : ref.getStore())) {
                LOGGER.atWarning().log(
                        "[MOB_LEVEL_DEBUG] source=DISABLED mob=%d resolveAttempts=%d Mob_Leveling.Enabled=false; skipping resolve.",
                        ref.getIndex(),
                        resolveAttempts);
            }
            return null;
        }

        Store<EntityStore> effectiveStore = store != null ? store : ref.getStore();
        if (isWorldXpBlacklisted(effectiveStore)) {
            if (shouldLogMobLevelDebug(ref, effectiveStore)) {
                LOGGER.atWarning().log(
                        "[MOB_LEVEL_DEBUG] source=SKIP mob=%d resolveAttempts=%d world='%s' is XP-blacklisted; skipping resolve.",
                        ref.getIndex(),
                        resolveAttempts,
                        String.valueOf(resolveWorldIdentifier(effectiveStore)));
            }
            return null;
        }

        if (isEntityBlacklisted(ref, effectiveStore, commandBuffer)) {
            if (shouldLogMobLevelDebug(ref, effectiveStore)) {
                LOGGER.atWarning().log(
                        "[MOB_LEVEL_DEBUG] source=SKIP mob=%d resolveAttempts=%d entity is blacklisted; skipping resolve.",
                        ref.getIndex(),
                        resolveAttempts);
            }
            return null;
        }

        LevelSourceMode mode = getLevelSourceMode(effectiveStore);

        ViewportCandidate candidate = null;
        if (mode == LevelSourceMode.PLAYER || mode == LevelSourceMode.MIXED || mode == LevelSourceMode.TIERS) {
            candidate = resolveViewportCandidate(ref, effectiveStore, commandBuffer);
            if (candidate == null && (mode == LevelSourceMode.PLAYER || mode == LevelSourceMode.MIXED)) {
                Vector3d mobPos = resolveWorldPosition(ref, commandBuffer);
                int fallbackLevel = mode == LevelSourceMode.MIXED
                        ? resolveDistanceLevel(effectiveStore, mobPos)
                        : getFixedLevel(effectiveStore, ref.getIndex(), mobPos);
                fallbackLevel = Math.max(1, clampToConfiguredRange(fallbackLevel, effectiveStore));
                if (shouldLogMobLevelDebug(ref, effectiveStore)) {
                    LOGGER.atWarning().log(
                            "[MOB_LEVEL_DEBUG] source=%s mob=%d resolveAttempts=%d no viewport candidate; using fallback level=%d.",
                            mode,
                            ref.getIndex(),
                            resolveAttempts,
                            fallbackLevel);
                }
                return fallbackLevel;
            }
        }

        Integer overrideLevel = resolveMobOverrideFixedLevel(ref, effectiveStore, commandBuffer,
                candidate != null ? candidate.playerRef() : null);
        if (overrideLevel != null && overrideLevel > 0) {
            int clamped = clampToConfiguredRange(overrideLevel, effectiveStore);
            if (shouldLogMobLevelDebug(ref, effectiveStore)) {
                LOGGER.atFine().log(
                        "[MOB_LEVEL_DEBUG] source=MOB_OVERRIDE mob=%d resolveAttempts=%d resolved=%d clamped=%d",
                        ref.getIndex(),
                        resolveAttempts,
                        overrideLevel,
                        clamped);
            }
            return clamped;
        }

        int level = resolveLevelByConfiguredSource(ref, effectiveStore, commandBuffer, candidate);
        if (level <= 0) {
            if (shouldLogMobLevelDebug(ref, effectiveStore)) {
                LOGGER.atWarning().log(
                        "[MOB_LEVEL_DEBUG] source=%s mob=%d resolveAttempts=%d resolved level <= 0 (%d); returning unresolved so caller may fallback.",
                        mode,
                        ref.getIndex(),
                        resolveAttempts,
                        level);
            }
            return null;
        }

        int clamped = clampToConfiguredRange(level, effectiveStore);
        if (shouldLogMobLevelDebug(ref, effectiveStore)) {
            String playerId = candidate != null && candidate.playerRef() != null
                    ? String.valueOf(candidate.playerRef().getUuid())
                    : "none";
            LOGGER.atFine().log(
                    "[MOB_LEVEL_DEBUG] source=%s mob=%d resolveAttempts=%d resolved=%d clamped=%d player=%s",
                    mode,
                    ref.getIndex(),
                    resolveAttempts,
                    level,
                    clamped,
                    playerId);
        }
        return clamped;
    }

    public String describePlayerContextForEntity(Ref<EntityStore> ref,
                                                 Store<EntityStore> store,
                                                 CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return "no-ref";
        }
        Store<EntityStore> effectiveStore = store != null ? store : ref.getStore();
        ViewportCandidate candidate = resolveViewportCandidate(ref, effectiveStore, commandBuffer);
        if (candidate == null) {
            return "viewport=no-player";
        }
        return String.format(
                Locale.ROOT,
                "viewport=ok player=%s dist=%.2f chunks=%d",
                candidate.playerRef().getUuid(),
                Math.sqrt(candidate.distanceSq()),
                candidate.viewRadiusChunks());
    }

    public void forgetEntity(int entityIndex) {
        // Caller lacks store/UUID context. Prefer forgetEntityByKey or forgetEntity(store, index).
    }

    public void forgetEntity(Store<EntityStore> store, int entityIndex) {
        long fallbackKey = toEntityKey(store, entityIndex);
        entityHealthCompositionSnapshots.remove(fallbackKey);
        entityResolvedLevelSnapshots.remove(fallbackKey);
        entityAssignedAugmentSnapshots.remove(fallbackKey);
        trueBaseHealthCache.remove(fallbackKey);
    }

    public void forgetEntityByKey(long entityKey) {
        if (entityKey < 0L) {
            return;
        }
        entityHealthCompositionSnapshots.remove(entityKey);
        entityResolvedLevelSnapshots.remove(entityKey);
        entityAssignedAugmentSnapshots.remove(entityKey);
        trueBaseHealthCache.remove(entityKey);
    }

    public void recordEntityMaxHealth(long trackingKey, float maxHealth) {
        if (trackingKey < 0L) {
            return;
        }
        if (!Float.isFinite(maxHealth) || maxHealth <= 0.0f) {
            entityHealthCompositionSnapshots.remove(trackingKey);
            return;
        }

        MobHealthCompositionSnapshot existing = entityHealthCompositionSnapshots.get(trackingKey);
        if (existing == null) {
            entityHealthCompositionSnapshots.put(trackingKey,
                    new MobHealthCompositionSnapshot(maxHealth, maxHealth, 0.0f, maxHealth,
                            System.currentTimeMillis()));
            return;
        }

        entityHealthCompositionSnapshots.put(trackingKey,
                new MobHealthCompositionSnapshot(
                        existing.baseMax(),
                        existing.scaledMax(),
                        existing.lifeForceBonus(),
                        maxHealth,
                        System.currentTimeMillis()));
    }

    public float getEntityMaxHealthSnapshot(long trackingKey) {
        MobHealthCompositionSnapshot snapshot = entityHealthCompositionSnapshots.get(trackingKey);
        if (snapshot == null || !Float.isFinite(snapshot.combinedMax())) {
            return -1.0f;
        }
        return snapshot.combinedMax();
    }

    public float getEntityMaxHealthSnapshot(Ref<EntityStore> ref,
                                            Store<EntityStore> store,
                                            CommandBuffer<EntityStore> commandBuffer) {
        return getEntityMaxHealthSnapshot(resolveTrackingKey(ref, store, commandBuffer));
    }

    public void recordEntityHealthComposition(long trackingKey,
                                              float baseMax,
                                              float scaledMax,
                                              float lifeForceBonus,
                                              float combinedMax) {
        if (trackingKey < 0L
                || !Float.isFinite(baseMax)
                || !Float.isFinite(scaledMax)
                || !Float.isFinite(lifeForceBonus)
                || !Float.isFinite(combinedMax)
                || combinedMax <= 0.0f) {
            return;
        }

        entityHealthCompositionSnapshots.put(trackingKey,
                new MobHealthCompositionSnapshot(baseMax,
                        scaledMax,
                        Math.max(0.0f, lifeForceBonus),
                        combinedMax,
                        System.currentTimeMillis()));
    }

    public MobHealthCompositionSnapshot getEntityHealthCompositionSnapshot(long trackingKey) {
        return entityHealthCompositionSnapshots.get(trackingKey);
    }

    public MobHealthCompositionSnapshot getEntityHealthCompositionSnapshot(Ref<EntityStore> ref,
                                                                           Store<EntityStore> store,
                                                                           CommandBuffer<EntityStore> commandBuffer) {
        return getEntityHealthCompositionSnapshot(resolveTrackingKey(ref, store, commandBuffer));
    }

    public void recordEntityResolvedLevel(long trackingKey, int level) {
        if (trackingKey < 0L || level <= 0) {
            return;
        }
        entityResolvedLevelSnapshots.put(trackingKey, level);
    }

    public Integer getEntityResolvedLevelSnapshot(long trackingKey) {
        Integer level = entityResolvedLevelSnapshots.get(trackingKey);
        return (level != null && level > 0) ? level : null;
    }

    public Integer getEntityResolvedLevelSnapshot(Ref<EntityStore> ref,
                                                  Store<EntityStore> store,
                                                  CommandBuffer<EntityStore> commandBuffer) {
        return getEntityResolvedLevelSnapshot(resolveTrackingKey(ref, store, commandBuffer));
    }

    /**
     * Anchors the true base (pre-augment) health for the given entity.
     * Uses putIfAbsent so the value is captured exactly once — on the first health application,
     * before any augment percent-health passives have modified the stat. This prevents the
     * compounding of augment modifiers (goliath, raid_boss, etc.) with level scaling across ticks.
     */
    public void cacheTrueBaseHealth(long trackingKey, float trueBase) {
        if (trackingKey >= 0L && Float.isFinite(trueBase) && trueBase > 0.0f) {
            trueBaseHealthCache.putIfAbsent(trackingKey, trueBase);
        }
    }

    /**
     * Returns the cached true base health for the given entity, or {@code null} if not yet cached.
     */
    public Float getTrueBaseHealth(long trackingKey) {
        return trueBaseHealthCache.get(trackingKey);
    }

    public int resolveMobLevel(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return 1;
        }

        Integer resolvedSnapshot = getEntityResolvedLevelSnapshot(ref, ref.getStore(), commandBuffer);
        if (resolvedSnapshot != null) {
            return resolvedSnapshot;
        }

        int resolved = resolveMobLevel(ref.getStore(), resolveWorldPosition(ref, commandBuffer), ref.getIndex());
        return Math.max(1, resolved);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition) {
        return resolveMobLevel(store, mobPosition, null);
    }

    public int resolveMobLevel(Store<EntityStore> store, Vector3d mobPosition, Integer entityId) {
        // Stateless resolution by configured source; no override checks.

        int level = switch (getLevelSourceMode(store)) {
            case PLAYER, MIXED -> {
                // Non-entity calls do not have viewport context, so this falls back to fixed safely.
                yield getFixedLevel(store, entityId, mobPosition);
            }
            case DISTANCE -> resolveDistanceLevel(store, mobPosition);
            case FIXED -> getFixedLevel(store, entityId, mobPosition);
            case TIERS -> resolveTieredLevel(store, null, entityId, mobPosition);
        };
        return Math.max(1, clampToConfiguredRange(level, store));
    }

    public boolean isPlayerBasedMode() {
        LevelSourceMode mode = getLevelSourceMode(null);
        return mode == LevelSourceMode.PLAYER || mode == LevelSourceMode.MIXED;
    }

    public boolean isLevelSourcePlayerMode() {
        return getLevelSourceMode(null) == LevelSourceMode.PLAYER;
    }

    public boolean isLevelSourcePlayerMode(Store<EntityStore> store) {
        return getLevelSourceMode(store) == LevelSourceMode.PLAYER;
    }

    public boolean isLevelSourceMixedMode() {
        return getLevelSourceMode(null) == LevelSourceMode.MIXED;
    }

    public int getPlayerBasedOffset(Store<EntityStore> store) {
        return getConfigInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0, store);
    }

    public boolean isMobExperienceRulesEnabled(Store<EntityStore> store) {
        return getConfigBoolean("Mob_Leveling.Experience.Enabled", true, store);
    }

    public double getMobGlobalXpMultiplier(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.Global_XP_Multiplier", 1.0D, store);
    }

    public boolean isMobXpLevelRangeEnabled(Store<EntityStore> store) {
        return getConfigBoolean("Mob_Leveling.Experience.XP_Level_Range.Enabled", true, store);
    }

    public int getMobXpMaxDifference(Store<EntityStore> store) {
        return getConfigInt("Mob_Leveling.Experience.XP_Level_Range.Max_Difference", 10, store);
    }

    public double getMobXpBelowRangeMultiplier(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.XP_Level_Range.Below_Range.Multiplier", 0.0D, store);
    }

    public double getMobXpAboveRangeMultiplier(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.XP_Level_Range.Above_Range.Multiplier", 0.0D, store);
    }

    public double getMobXpAdditiveMinimum(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.Additive_Minimum_XP", 50.0D, store);
    }

    public String getMobXpScalingMode(Store<EntityStore> store) {
        return getConfigString("Mob_Leveling.Experience.Scaling.Mode", "NONE", store);
    }

    public double getMobXpScalingBonusAtMax(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.Scaling.BonusAtMax", 0.0D, store);
    }

    public double getMobXpScalingMinMultiplier(Store<EntityStore> store) {
        return getConfigDouble("Mob_Leveling.Experience.Scaling.MinMultiplier", 0.1D, store);
    }

    public String describeMixedPromotionTrigger(Ref<EntityStore> ref,
                                                Store<EntityStore> store,
                                                CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return "mixed=no-ref";
        }
        Store<EntityStore> effectiveStore = store != null ? store : ref.getStore();
        Vector3d mobPos = resolveWorldPosition(ref, commandBuffer);
        int distanceLevel = resolveDistanceLevel(effectiveStore, mobPos);
        ViewportCandidate candidate = resolveViewportCandidate(ref, effectiveStore, commandBuffer);
        int playerLevel = candidate == null ? -1 : resolveReferencePlayerLevel(effectiveStore, candidate.playerRef());
        return String.format(Locale.ROOT, "distance=%d player=%d", distanceLevel, playerLevel);
    }

    public LevelRange getPlayerBasedLevelRange(int playerLevel) {
        int level = Math.max(1, playerLevel);
        int minDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Min_Difference", -3, null);
        int maxDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Max_Difference", 3, null);
        int offset = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0, null);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }
        int min = clampToConfiguredRange(level + minDiff + offset, null);
        int max = clampToConfiguredRange(level + maxDiff + offset, null);
        return new LevelRange(Math.min(min, max), Math.max(min, max));
    }

    public List<String> getMobOverrideAugmentIds(Ref<EntityStore> ref,
                                                  CommandBuffer<EntityStore> commandBuffer) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        return getMobOverrideAugmentIds(ref, store, commandBuffer);
    }

    public List<String> getMobOverrideAugmentIds(Ref<EntityStore> ref,
                                                  Store<EntityStore> store,
                                                  CommandBuffer<EntityStore> commandBuffer) {
        Store<EntityStore> effectiveStore = store != null ? store : (ref != null ? ref.getStore() : null);
        if (ref == null || isEntityBlacklisted(ref, effectiveStore, commandBuffer)) {
            return List.of();
        }

        long entityKey = resolveTrackingKey(ref, effectiveStore, commandBuffer);
        List<String> cachedAugments = entityAssignedAugmentSnapshots.get(entityKey);
        if (cachedAugments != null) {
            return cachedAugments;
        }

        if (!getConfigBoolean("Mob_Augments.Enabled", true, effectiveStore)) {
            return List.of();
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        AugmentManager augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        if (augmentManager == null || augmentManager.getAugments().isEmpty()) {
            return List.of();
        }

        int level = Math.max(1, resolveMobLevel(ref, commandBuffer));
        long seed = computeAugmentSeed(ref, effectiveStore, level);
        SplittableRandom random = new SplittableRandom(seed);

        List<HighTierRollProfile> highTierRollProfiles = resolveHighTierRollProfiles(level, effectiveStore, random);
        int highTierCount = highTierRollProfiles.size();
        int commonCount = Math.max(0, resolveCommonAugmentCountFromPerLevel(level, effectiveStore));
        if (highTierCount <= 0 && commonCount <= 0) {
            return List.of();
        }

        Set<String> blacklistedIds = parseAugmentBlacklist(effectiveStore);
        MobAugmentTierPools tierPools = resolveMobAugmentTierPools(augmentManager, blacklistedIds);
        List<String> elite = tierPools.elite();
        List<String> legendary = tierPools.legendary();
        List<String> mythic = tierPools.mythic();
        List<String> fallbackPool = tierPools.fallback();

        if (highTierCount > 0 && elite.isEmpty() && legendary.isEmpty() && mythic.isEmpty()) {
            return List.of();
        }

        Set<String> chosen = new LinkedHashSet<>();

        if (highTierCount > 0) {
            for (HighTierRollProfile rollProfile : highTierRollProfiles) {
                int attempts = 0;
                while (attempts < 12) {
                    int before = chosen.size();
                    PassiveTier tier = rollTier(random,
                            rollProfile.eliteWeight(),
                            rollProfile.legendaryWeight(),
                            rollProfile.mythicWeight());
                    String picked = pickTier(tier, elite, legendary, mythic, fallbackPool, random);
                    if (picked != null) {
                        chosen.add(picked);
                    }
                    if (chosen.size() > before) {
                        break;
                    }
                    attempts++;
                }
            }
        }

        List<String> selected = new ArrayList<>(chosen);
        if (commonCount > 0 && (blacklistedIds == null || !blacklistedIds.contains(CommonAugment.ID))) {
            selected.addAll(rollMobCommonStatOffers(augmentManager, commonCount, random));
        }

        if (selected.isEmpty()) {
            return List.of();
        }

        List<String> assignedAugments = List.copyOf(selected);
        entityAssignedAugmentSnapshots.put(entityKey, assignedAugments);
        return assignedAugments;
    }

    private List<HighTierRollProfile> resolveHighTierRollProfiles(int level,
            Store<EntityStore> store,
            SplittableRandom random) {
        int baseHighTierCount = getConfigInt("Mob_Augments.Base_High_Tier.Count", -1, store);
        int extraHighTierCount = getConfigInt("Mob_Augments.Extra_High_Tier.Count", -1, store);
        boolean hasStructuredConfig = baseHighTierCount >= 0 || extraHighTierCount >= 0;

        List<HighTierRollProfile> rolls = new ArrayList<>();

        if (hasStructuredConfig) {
            if (baseHighTierCount > 0) {
                double baseChance = clampProbability(getConfigDouble("Mob_Augments.Base_High_Tier.Chance", 1.0D, store));
                if (random.nextDouble() < baseChance) {
                    HighTierRollProfile baseProfile = new HighTierRollProfile(
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Base_High_Tier.Weights.Elite", 1.0D, store)),
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Base_High_Tier.Weights.Legendary", 0.0D, store)),
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Base_High_Tier.Weights.Mythic", 0.0D, store)));
                    for (int i = 0; i < baseHighTierCount; i++) {
                        rolls.add(baseProfile);
                    }
                }
            }

            if (extraHighTierCount > 0) {
                double extraChance = clampProbability(getConfigDouble("Mob_Augments.Extra_High_Tier.Chance", 0.0D, store));
                if (random.nextDouble() < extraChance) {
                    HighTierRollProfile extraProfile = new HighTierRollProfile(
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Extra_High_Tier.Weights.Elite", 0.8D, store)),
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Extra_High_Tier.Weights.Legendary", 0.15D, store)),
                            Math.max(0.0D, getConfigDouble("Mob_Augments.Extra_High_Tier.Weights.Mythic", 0.05D, store)));
                    for (int i = 0; i < extraHighTierCount; i++) {
                        rolls.add(extraProfile);
                    }
                }
            }

            return rolls;
        }

        int baseCount = getConfigInt("Mob_Leveling.Mob_Augments.Base_Count", 0, store);
        int perLevels = Math.max(1, getConfigInt("Mob_Leveling.Mob_Augments.Per_Levels", 15, store));
        int highTierCount = Math.max(0, baseCount + (level / perLevels));
        if (highTierCount <= 0) {
            return rolls;
        }

        HighTierRollProfile legacyProfile = new HighTierRollProfile(
                Math.max(0.0D, getConfigDouble("Mob_Leveling.Mob_Augments.Weights.ELITE", 1.0D, store)),
                Math.max(0.0D, getConfigDouble("Mob_Leveling.Mob_Augments.Weights.LEGENDARY", 0.35D, store)),
                Math.max(0.0D, getConfigDouble("Mob_Leveling.Mob_Augments.Weights.MYTHIC", 0.1D, store)));
        for (int i = 0; i < highTierCount; i++) {
            rolls.add(legacyProfile);
        }
        return rolls;
    }

    private double clampProbability(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public String describeDistanceCenter(Store<EntityStore> store) {
        return describeDistanceCenter(store, null);
    }

    public String describeDistanceCenter(Store<EntityStore> store, Object worldHint) {
        String raw = getConfigString("Mob_Leveling.Level_Source.Distance_Level.Center_Coordinates", "0,0", store);
        return "center=" + raw;
    }

    public double getPartyXpShareRange(Store<EntityStore> store) {
        return Math.max(0.0D, getConfigDouble("party_xp_share.range", 25.0D, store));
    }

    public String resolveWorldIdentifier(Store<EntityStore> store) {
        List<String> candidates = resolveWorldIdentifierCandidates(store, false);
        return selectPreferredWorldIdentifier(candidates);
    }

    public boolean isDefaultWorldFallback(Store<EntityStore> store) {
        if (store == null) {
            return false;
        }
        List<String> worldIds = resolveWorldIdentifierCandidates(store, true);
        if (worldIds.isEmpty()) {
            return false;
        }

        String matchedKey = resolveBestWorldOverrideKey(store, worldIds);
        return matchedKey == null && shouldApplyDefaultWorldOverride(store, worldIds);
    }

    public String resolveMatchedWorldOverrideKey(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        return resolveBestWorldOverrideKey(store, resolveWorldIdentifierCandidates(store, true));
    }

    public String describeWorldResolutionContext(Store<EntityStore> store) {
        if (store == null) {
            return "store=<null>";
        }

        Object world = invokeNoArg(store, "getWorld");
        Object worldConfig = invokeNoArg(world, "getWorldConfig");
        List<String> candidates = resolveWorldIdentifierCandidates(store, true);
        String selected = selectPreferredWorldIdentifier(candidates);
        String matchedKey = resolveBestWorldOverrideKey(store, candidates);
        boolean fallbackToDefault = matchedKey == null && shouldApplyDefaultWorldOverride(store, candidates);

        String defaultWorldName = null;
        try {
            Object defaultWorld = Universe.get() != null ? Universe.get().getDefaultWorld() : null;
            if (defaultWorld != null) {
                Object name = invokeNoArg(defaultWorld, "getName");
                defaultWorldName = name == null ? null : name.toString();
            }
        } catch (Throwable ignored) {
        }

        return String.format(
                Locale.ROOT,
                "storeProbes=%s worldProbes=%s worldConfigProbes=%s candidates=%s selected=%s matchedKey=%s fallbackToDefault=%s defaultWorld=%s",
                buildProbeDump(store, "getId", "getWorldId", "getIdentifier", "getKey", "getName"),
                buildProbeDump(world, "getName", "getId", "getWorldId", "getIdentifier", "getKey"),
                buildProbeDump(worldConfig, "getName", "getId", "getWorldId", "getIdentifier", "getKey", "getDisplayName"),
                summarizeCandidates(candidates),
                safeDebugValue(selected),
                safeDebugValue(matchedKey),
                fallbackToDefault,
                safeDebugValue(defaultWorldName));
    }

    public int getPlayerLevel(PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return 1;
        }
        return getPlayerLevel(playerRef.getUuid());
    }

    public boolean isMobLevelingEnabled() {
        return getConfigBoolean("Mob_Leveling.Enabled", true, null);
    }

    public boolean allowPassiveMobLeveling() {
        return getConfigBoolean("Mob_Leveling.allow_passive_mob_leveling", false, null);
    }

    public boolean isWorldXpBlacklisted(Store<EntityStore> store) {
        List<String> worldIds = resolveWorldIdentifierCandidates(store, false);
        if (worldIds.isEmpty()) {
            return false;
        }

        long storeKey = toStoreKey(store);
        Boolean cached = worldXpBlacklistByStore.get(storeKey);
        if (cached != null) {
            return cached;
        }

        boolean blacklisted = isWorldXpBlacklisted(worldIds);
        worldXpBlacklistByStore.put(storeKey, blacklisted);
        return blacklisted;
    }

    private boolean isWorldXpBlacklisted(List<String> worldIds) {
        if (worldIds.isEmpty()) {
            return false;
        }

        List<String> rules = new ArrayList<>();
        appendStringRules(rules, worldsConfigManager.get("XP_Blacklisted_Worlds", null, false));
        appendStringRules(rules, worldsConfigManager.get("XP_Blacklisted_Words", null, false));
        if (rules.isEmpty()) {
            return false;
        }

        for (String worldId : worldIds) {
            if (worldId == null || worldId.isBlank()) {
                continue;
            }
            String normalized = worldId.toLowerCase(Locale.ROOT);
            for (String rule : rules) {
                if (rule == null || rule.isBlank()) {
                    continue;
                }
                String candidate = rule.trim().toLowerCase(Locale.ROOT);
                if (candidate.contains("*")) {
                    if (matchesWildcard(normalized, candidate)) {
                        return true;
                    }
                } else if (normalized.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isMobTypeBlacklisted(String mobType, Store<EntityStore> store) {
        if (mobType == null || mobType.isBlank()) {
            return false;
        }

        String normalizedType = normalizeMobType(mobType);
        Object raw = resolveConfigValue("Blacklist_Mob_Types", null, store);
        if (raw == UNSET_CONFIG_VALUE || raw == null) {
            raw = configManager.get("Mob_Leveling.Blacklist_Mob_Types", null, false);
        }
        if (raw == null) {
            return false;
        }

        List<String> rules = new ArrayList<>();
        appendStringRules(rules, raw);
        if (rules.isEmpty()) {
            return false;
        }

        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String normalizedRule = normalizeMobType(rule);
            if (normalizedRule.contains("*")) {
                if (matchesWildcard(normalizedType, normalizedRule)) {
                    return true;
                }
            } else if (normalizedType.contains(normalizedRule)) {
                return true;
            }
        }

        return false;
    }

    public boolean isEntityBlacklisted(Ref<EntityStore> ref,
                                       Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return false;
        }
        if (ArmyOfTheDeadPassive.isManagedSummon(ref, store, commandBuffer)) {
            return true;
        }

        NPCEntity npc = resolveComponent(ref, store, commandBuffer, NPCEntity.getComponentType());
        if (npc == null) {
            return true;
        }

        try {
            String mobType = npc.getNPCTypeId();
            return mobType != null && isMobTypeBlacklisted(mobType, store);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public double getMobHealthMultiplierForLevel(int level) {
        return getMobHealthMultiplierForLevel(level, null);
    }

    public double getMobHealthMultiplierForLevel(Ref<EntityStore> ref,
                                                 CommandBuffer<EntityStore> commandBuffer,
                                                 int level) {
        return getMobHealthMultiplierForLevel(level, ref != null ? ref.getStore() : null);
    }

    public double getMobHealthMultiplierForLevel(Ref<EntityStore> ref,
                                                 Store<EntityStore> store,
                                                 CommandBuffer<EntityStore> commandBuffer,
                                                 int level) {
        return getMobHealthMultiplierForLevel(level, store != null ? store : (ref != null ? ref.getStore() : null));
    }

    public float computeMobHealthAdditive(int level, float baseMax) {
        if (!Float.isFinite(baseMax) || baseMax <= 0.0f) {
            return 0.0f;
        }
        float target = (float) (baseMax * getMobHealthMultiplierForLevel(level));
        return target - baseMax;
    }

    public MobHealthScalingResult computeMobHealthScaling(int level,
                                                          float baseMax,
                                                          float previousMax,
                                                          float previousValue) {
        return computeMobHealthScaling(level, baseMax, previousMax, previousValue, null);
    }

    public MobHealthScalingResult computeMobHealthScaling(Ref<EntityStore> ref,
                                                          Store<EntityStore> store,
                                                          CommandBuffer<EntityStore> commandBuffer,
                                                          int level,
                                                          float baseMax,
                                                          float previousMax,
                                                          float previousValue) {
        return computeMobHealthScaling(level,
                baseMax,
                previousMax,
                previousValue,
                store != null ? store : (ref != null ? ref.getStore() : null));
    }

    public record MobHealthScalingResult(float targetMax, float additive, float newValue) {
    }

    public double getMobDamageMultiplierForLevel(int level) {
        return getMobDamageMultiplierForLevel(level, null);
    }

    public double getMobDamageMultiplierForLevel(Ref<EntityStore> ref,
                                                 CommandBuffer<EntityStore> commandBuffer,
                                                 int level) {
        return getMobDamageMultiplierForLevel(level, ref != null ? ref.getStore() : null);
    }

    public double getMobDamageMultiplierForLevels(int mobLevel, int playerLevel) {
        double base = getMobDamageMultiplierForLevel(mobLevel);
        double diff = getMobDamageMaxDifferenceMultiplierForLevels(mobLevel, playerLevel);
        return Math.max(0.0001D, base * diff);
    }

    public double getMobDamageMultiplierForLevels(Ref<EntityStore> ref,
                                                  CommandBuffer<EntityStore> commandBuffer,
                                                  int mobLevel,
                                                  int playerLevel) {
        Store<EntityStore> store = ref != null ? ref.getStore() : null;
        double base = getMobDamageMultiplierForLevel(mobLevel, store);
        int levelDiff = Math.max(1, mobLevel) - Math.max(1, playerLevel);
        double diff = getMobDamageMaxDifferenceMultiplierForLevelDifference(store, levelDiff);
        return Math.max(0.0001D, base * diff);
    }

    public double getMobDamageMaxDifferenceMultiplierForLevels(int mobLevel, int playerLevel) {
        int levelDiff = Math.max(1, mobLevel) - Math.max(1, playerLevel);
        return getMobDamageMaxDifferenceMultiplierForLevelDifference(null, levelDiff);
    }

    public boolean isMobDamageScalingEnabled() {
        return isMobDamageScalingEnabled(null);
    }

    public boolean isMobDamageScalingEnabled(Store<EntityStore> store) {
        if (getConfigBoolean("Mob_Leveling.Scaling.Damage.Enabled", false, store)) {
            return true;
        }
        return store == null && hasGlobalOrDefaultWorldOverrideBoolean("Scaling.Damage.Enabled");
    }

    public boolean isMobHealthScalingEnabled() {
        return isMobHealthScalingEnabled(null);
    }

    public boolean isMobHealthScalingEnabled(Store<EntityStore> store) {
        if (getConfigBoolean("Mob_Leveling.Scaling.Health.Enabled", false, store)) {
            return true;
        }
        return store == null && hasGlobalOrDefaultWorldOverrideBoolean("Scaling.Health.Enabled");
    }

    public boolean isMobDefenseScalingEnabled() {
        return isMobDefenseScalingEnabled(null);
    }

    public boolean isMobDefenseScalingEnabled(Store<EntityStore> store) {
        if (getConfigBoolean("Mob_Leveling.Scaling.Defense.Enabled", false, store)) {
            return true;
        }
        return store == null && hasGlobalOrDefaultWorldOverrideBoolean("Scaling.Defense.Enabled");
    }

    public double getMobDefenseReductionForLevels(int mobLevel, int playerLevel) {
        return getMobDefenseReductionForLevelDifference(Math.max(1, mobLevel) - Math.max(1, playerLevel));
    }

    public double getMobDefenseReductionForLevels(Ref<EntityStore> ref,
                                                  CommandBuffer<EntityStore> commandBuffer,
                                                  int mobLevel,
                                                  int playerLevel) {
        return getMobDefenseReductionForLevels(mobLevel, playerLevel);
    }

    public double getPlayerCombatDefenseReductionForLevels(Ref<EntityStore> ref,
                                                           CommandBuffer<EntityStore> commandBuffer,
                                                           int attackerLevel,
                                                           int playerLevel) {
        int levelDiff = Math.max(1, playerLevel) - Math.max(1, attackerLevel);
        int maxDiff = Math.max(1, getConfigInt("Player_Combat_Scaling.Player_Level_Scaling_Difference.Range", 10,
                ref != null ? ref.getStore() : null));
        double negative = clampReduction(getConfigDouble(
                "Player_Combat_Scaling.Defense_Max_Difference.At_Negative_Max_Difference", 0.0D,
                ref != null ? ref.getStore() : null));
        double positive = clampReduction(getConfigDouble(
                "Player_Combat_Scaling.Defense_Max_Difference.At_Positive_Max_Difference", 0.0D,
                ref != null ? ref.getStore() : null));
        if (levelDiff <= -maxDiff) {
            return negative;
        }
        if (levelDiff >= maxDiff) {
            return positive;
        }
        double ratio = (levelDiff + maxDiff) / (double) (maxDiff * 2);
        return lerp(negative, positive, ratio);
    }

    public double getMobDefenseReductionForLevelDifference(int levelDifference) {
        return getMobDefenseReductionForLevelDifference(null, levelDifference);
    }

    public double getMobDefenseReductionForLevelDifference(Store<EntityStore> store, int levelDifference) {
        if (!isMobDefenseScalingEnabled(store)) {
            return 0.0D;
        }

        int maxDiff = Math.max(1, getConfigInt("Mob_Leveling.Scaling.Level_Scaling_Difference.Range", 10, store));
        double negative = clampReduction(getConfigDouble("Mob_Leveling.Scaling.Defense.At_Negative_Max_Difference", 0.0D,
                store));
        double positive = clampReduction(getConfigDouble("Mob_Leveling.Scaling.Defense.At_Positive_Max_Difference", 0.75D,
                store));
        if (levelDifference <= -maxDiff) {
            return negative;
        }
        if (levelDifference >= maxDiff) {
            return positive;
        }
        double ratio = (levelDifference + maxDiff) / (double) (maxDiff * 2);
        return lerp(negative, positive, ratio);
    }

    public record LevelRange(int min, int max) {
    }

    public boolean shouldShowMobLevelUi() {
        return shouldRenderMobNameplate();
    }

    public boolean shouldIncludeLevelInNameplate() {
        return shouldShowMobNameplateLevel();
    }

    public boolean shouldRenderMobNameplate() {
        return getConfigBoolean("Mob_Leveling.Nameplate.Enabled", true, null);
    }

    public boolean shouldShowMobNameplateLevel() {
        return getConfigBoolean("Mob_Leveling.Nameplate.Show_Level", true, null);
    }

    public boolean shouldShowMobNameplateName() {
        return getConfigBoolean("Mob_Leveling.Nameplate.Show_Name", true, null);
    }

    public boolean shouldShowMobNameplateHealth() {
        return getConfigBoolean("Mob_Leveling.Nameplate.Show_Health", true, null);
    }

    public boolean registerAreaLevelOverride(String id,
                                             String worldId,
                                             double centerX,
                                             double centerZ,
                                             double radius,
                                             int minLevel,
                                             int maxLevel) {
        if (id == null || id.isBlank() || radius <= 0.0D || minLevel <= 0 || maxLevel <= 0) {
            return false;
        }
        areaOverrides.put(id.trim(),
                new AreaOverride(id.trim(), worldId, centerX, centerZ, radius * radius, minLevel, maxLevel));
        return true;
    }

    public boolean registerWorldLevelOverride(String id, String worldId, int minLevel, int maxLevel) {
        return registerAreaLevelOverride(id, worldId, 0.0D, 0.0D, Double.MAX_VALUE / 4.0D, minLevel, maxLevel);
    }

    public boolean removeAreaLevelOverride(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return areaOverrides.remove(id.trim()) != null;
    }

    public void clearAreaLevelOverrides() {
        areaOverrides.clear();
    }

    // No persistent per-entity overrides in stateless mode.
    public void setEntityLevelOverride(int entityIndex, int level) {
        // no-op
    }

    public void setEntityLevelOverride(Store<EntityStore> store, int entityIndex, int level) {
        // no-op
    }

    public Integer getEntityLevelOverride(Store<EntityStore> store, int entityIndex) {
        return null;
    }

    public boolean setEntityLevelOverrideIfChanged(Store<EntityStore> store, int entityIndex, int level) {
        return false;
    }

    public void clearEntityLevelOverride(int entityIndex) {
        // no-op
    }

    public void clearEntityLevelOverride(Store<EntityStore> store, int entityIndex) {
        // no-op
    }

    public void clearAllEntityLevelOverrides() {
        entityHealthCompositionSnapshots.clear();
        entityResolvedLevelSnapshots.clear();
        entityAssignedAugmentSnapshots.clear();
        trueBaseHealthCache.clear();
        tieredInstanceLocks.clear();
        worldIdentifierCandidatesByStore.clear();
        worldIdentifierMissRetryAtStore.clear();
        worldOverrideKeyByStore.clear();
        worldXpBlacklistByStore.clear();
    }

    public void clearTierLockForStore(Store<EntityStore> store) {
        if (store == null) {
            return;
        }
        long storeKey = toStoreKey(store);
        tieredInstanceLocks.remove(storeKey);
        worldIdentifierCandidatesByStore.remove(storeKey);
        worldIdentifierMissRetryAtStore.remove(storeKey);
        worldOverrideKeyByStore.remove(storeKey);
        worldXpBlacklistByStore.remove(storeKey);
    }

    public void shutdownRuntimeState() {
        clearAllEntityLevelOverrides();
        areaOverrides.clear();
        cachedMobAugmentTierPools = null;
        mobLevelDebugLogTimes.clear();
        missingLevelSourceModeWarned = false;
    }

    private int resolveLevelByConfiguredSource(Ref<EntityStore> ref,
                                               Store<EntityStore> store,
                                               CommandBuffer<EntityStore> commandBuffer,
                                               ViewportCandidate candidate) {
        LevelSourceMode mode = getLevelSourceMode(store);
        Vector3d mobPos = resolveWorldPosition(ref, commandBuffer);
        int entityId = ref.getIndex();

        return switch (mode) {
            case PLAYER -> resolvePlayerSourceLevel(store, candidate != null ? candidate.playerRef() : null, entityId);
            case DISTANCE -> resolveDistanceLevel(store, mobPos);
            case MIXED -> {
                int player = resolvePlayerSourceLevel(store, candidate != null ? candidate.playerRef() : null, entityId);
                int distance = resolveDistanceLevel(store, mobPos);
                double playerWeight = clamp01(getConfigDouble("Mob_Leveling.Level_Source.Mixed.Player_Weight", 0.5D,
                        store));
                long seed = toEntityKey(store, entityId) ^ (long) player;
                // Mix high bits into low bits so the store identity-hash in the upper 32
                // bits doesn't create a constant per-world bias in the modulo result.
                // Uses the MurmurHash3 64-bit finalizer for uniform distribution.
                seed ^= (seed >>> 33);
                seed *= 0xFF51AFD7ED558CCDL;
                seed ^= (seed >>> 33);
                double roll = Math.floorMod(seed, 10000L) / 10000.0D;
                yield roll <= playerWeight ? player : distance;
            }
            case FIXED -> getFixedLevel(store, entityId, mobPos);
            case TIERS -> {
                PlayerRef sourcePlayer = candidate != null ? candidate.playerRef() : null;
                yield resolveTieredLevel(store, sourcePlayer, entityId, mobPos);
            }
        };
    }

    private int resolveTieredLevel(Store<EntityStore> store,
                                   PlayerRef sourcePlayer,
                                   Integer entityId,
                                   Vector3d mobPosition) {
        LevelRange baseRange = parseTieredBaseLevelRange(store);
        int levelsPerTier = Math.max(1, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 20, store));
        int tierOffset = resolveTierOffsetForPlayer(store, sourcePlayer, baseRange, levelsPerTier);

        int shiftedMin = baseRange.min() + (tierOffset * levelsPerTier);
        int shiftedMax = baseRange.max() + (tierOffset * levelsPerTier);
        LevelRange shiftedRange = normalizeLevelRange(shiftedMin, shiftedMax, store);

        if (shiftedRange.min() == shiftedRange.max()) {
            return shiftedRange.min();
        }

        long seed = (entityId != null ? entityId.longValue() : hashPosition(mobPosition)) ^ 0xD1B54A32D192ED03L;
        int span = (shiftedRange.max() - shiftedRange.min()) + 1;
        int roll = (int) Math.floorMod(seed, span);
        return shiftedRange.min() + roll;
    }

    private int resolveTierOffsetForPlayer(Store<EntityStore> store,
                                           PlayerRef sourcePlayer,
                                           LevelRange baseRange,
                                           int levelsPerTier) {
        TieredInstanceLock lock = tieredInstanceLocks.get(toStoreKey(store));
        if (lock != null) {
            return lock.tierOffset();
        }

        return resolveDynamicTierOffsetForPlayer(store, sourcePlayer, baseRange, levelsPerTier);
    }

    private int resolveDynamicTierOffsetForPlayer(Store<EntityStore> store,
                                                  PlayerRef sourcePlayer,
                                                  LevelRange baseRange,
                                                  int levelsPerTier) {
        boolean adaptationEnabled = getConfigBoolean("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Enabled",
                true,
                store);
        if (!adaptationEnabled || sourcePlayer == null || !sourcePlayer.isValid()) {
            return 0;
        }

        int playerLevel = Math.max(1, resolveReferencePlayerLevel(store, sourcePlayer));
        int allowance = Math.max(0,
                getConfigInt("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Range_Allowance", 0, store));
        TierAdaptationMode mode = getTierAdaptationMode(store);

        int tierOffset;
        switch (mode) {
            case BELOW -> {
                tierOffset = resolveDownwardTierOffset(playerLevel, baseRange.min(), allowance, levelsPerTier);
            }
            case BOTH -> {
                int upward = resolveUpwardTierOffset(playerLevel, baseRange.max(), allowance, levelsPerTier);
                int downward = resolveDownwardTierOffset(playerLevel, baseRange.min(), allowance, levelsPerTier);
                tierOffset = (upward > 0) ? upward : downward;
            }
            case ABOVE -> {
                tierOffset = resolveUpwardTierOffset(playerLevel, baseRange.max(), allowance, levelsPerTier);
            }
            default -> tierOffset = 0;
        }

        Integer totalTiers = parsePositiveInt(resolveConfigValue("Mob_Leveling.Level_Source.Tiers.Total_Tiers", null,
                store));
        if (totalTiers != null) {
            int maxOffset = Math.max(0, totalTiers - 1);
            tierOffset = Math.max(-maxOffset, Math.min(maxOffset, tierOffset));
        }

        return tierOffset;
    }

    private Integer resolveMobOverrideFixedLevel(Ref<EntityStore> ref,
                                                 Store<EntityStore> store,
                                                 CommandBuffer<EntityStore> commandBuffer,
                                                 PlayerRef sourcePlayer) {
        if (ref == null || store == null) {
            return null;
        }

        NPCEntity npc = resolveComponent(ref, store, commandBuffer, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }

        String mobType;
        try {
            mobType = npc.getNPCTypeId();
        } catch (Throwable ignored) {
            return null;
        }
        if (mobType == null || mobType.isBlank()) {
            return null;
        }

        Object mobOverridesRaw = resolveConfigValue("Mob_Overrides", null, store);
        if (!(mobOverridesRaw instanceof Map<?, ?> mobOverrides) || mobOverrides.isEmpty()) {
            return null;
        }

        LevelSourceMode mode = getLevelSourceMode(store);
        int levelsPerTier = Math.max(1, getConfigInt("Mob_Leveling.Level_Source.Tiers.Levels_Per_Tier", 20, store));
        LevelRange baseRange = mode == LevelSourceMode.TIERS ? parseTieredBaseLevelRange(store) : null;
        int tierOffset = (mode == LevelSourceMode.TIERS && baseRange != null)
                ? resolveTierOffsetForPlayer(store, sourcePlayer, baseRange, levelsPerTier)
                : 0;

        Vector3d mobPos = resolveWorldPosition(ref, commandBuffer);
        for (Map.Entry<?, ?> overrideEntry : mobOverrides.entrySet()) {
            String overrideId = overrideEntry.getKey() == null ? null : overrideEntry.getKey().toString();
            if (!(overrideEntry.getValue() instanceof Map<?, ?> overrideMap)) {
                continue;
            }

            if (!mobMatchesOverrideRule(mobType, overrideId, overrideMap.get("Names"))) {
                continue;
            }

            LevelRange configuredRange = parseLevelRangeValue(overrideMap.get("Level"), store);
            if (configuredRange == null) {
                Integer fixed = parsePositiveInt(overrideMap.get("Level"));
                if (fixed != null) {
                    configuredRange = normalizeLevelRange(fixed, fixed, store);
                }
            }
            if (configuredRange == null) {
                return null;
            }

            LevelRange effectiveRange = configuredRange;
            if (mode == LevelSourceMode.TIERS && tierOffset != 0) {
                int shiftedMin = configuredRange.min() + (tierOffset * levelsPerTier);
                int shiftedMax = configuredRange.max() + (tierOffset * levelsPerTier);
                effectiveRange = normalizeLevelRange(shiftedMin, shiftedMax, store);
            }

            return rollLevelInRange(effectiveRange, ref.getIndex(), mobPos, 0xC2B2AE3D27D4EB4FL);
        }

        return null;
    }

    private boolean mobMatchesOverrideRule(String mobType, String overrideId, Object namesRaw) {
        String normalizedType = normalizeMobType(mobType);

        if (overrideId != null && !overrideId.isBlank()) {
            String normalizedId = normalizeMobType(overrideId);
            if (matchesNormalizedMobRule(normalizedType, normalizedId)) {
                return true;
            }
        }

        List<String> names = new ArrayList<>();
        appendStringRules(names, namesRaw);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (matchesNormalizedMobRule(normalizedType, normalizeMobType(name))) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesNormalizedMobRule(String normalizedMobType, String normalizedRule) {
        if (normalizedMobType == null || normalizedRule == null || normalizedRule.isBlank()) {
            return false;
        }
        if (normalizedRule.contains("*")) {
            return matchesWildcard(normalizedMobType, normalizedRule);
        }
        return normalizedMobType.equals(normalizedRule);
    }

    private int rollLevelInRange(LevelRange range, Integer entityId, Vector3d mobPosition, long salt) {
        if (range == null) {
            return 1;
        }
        if (range.min() == range.max()) {
            return range.min();
        }

        long seed = (entityId != null ? entityId.longValue() : hashPosition(mobPosition)) ^ salt;
        int span = (range.max() - range.min()) + 1;
        int roll = (int) Math.floorMod(seed, span);
        return range.min() + roll;
    }

    private int resolveUpwardTierOffset(int playerLevel, int baseMax, int allowance, int levelsPerTier) {
        int trigger = baseMax + allowance;
        if (playerLevel <= trigger) {
            return 0;
        }
        int delta = playerLevel - trigger;
        return ((delta - 1) / levelsPerTier) + 1;
    }

    private int resolveDownwardTierOffset(int playerLevel, int baseMin, int allowance, int levelsPerTier) {
        int trigger = baseMin - allowance;
        if (playerLevel >= trigger) {
            return 0;
        }
        int delta = trigger - playerLevel;
        return -(((delta - 1) / levelsPerTier) + 1);
    }

    private TierAdaptationMode getTierAdaptationMode(Store<EntityStore> store) {
        String raw = getConfigString("Mob_Leveling.Level_Source.Tiers.Player_Adaptation.Allowance_Mode", "ABOVE",
                store);
        if (raw == null || raw.isBlank()) {
            return TierAdaptationMode.ABOVE;
        }
        try {
            return TierAdaptationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TierAdaptationMode.ABOVE;
        }
    }

    private int resolvePlayerSourceLevel(Store<EntityStore> store, PlayerRef sourcePlayer, int entityId) {
        int baseLevel = resolveReferencePlayerLevel(store, sourcePlayer);
        int offset = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Offset", 0, store);
        int minDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Min_Difference", -3, store);
        int maxDiff = getConfigInt("Mob_Leveling.Level_Source.Player_Based.Max_Difference", 3, store);
        if (minDiff > maxDiff) {
            int tmp = minDiff;
            minDiff = maxDiff;
            maxDiff = tmp;
        }
        int spread = Math.max(0, maxDiff - minDiff);
        int diff = spread == 0 ? minDiff : minDiff + Math.floorMod(toEntityKey(store, entityId), spread + 1);
        return clampToConfiguredRange(baseLevel + offset + diff, store);
    }

    private int resolveReferencePlayerLevel(Store<EntityStore> store, PlayerRef sourcePlayer) {
        if (sourcePlayer == null || !sourcePlayer.isValid()) {
            return 1;
        }

        UUID sourceUuid = sourcePlayer.getUuid();
        int fallback = getPlayerLevel(sourceUuid);
        if (!getConfigBoolean("Mob_Leveling.Level_Source.Party_System.Enabled", false, store)) {
            return Math.max(1, fallback);
        }

        PartyManager partyManager = resolvePartyManager();
        if (partyManager == null || !partyManager.isAvailable() || !partyManager.isInParty(sourceUuid)) {
            return Math.max(1, fallback);
        }

        Set<UUID> members = partyManager.getOnlinePartyMembers(sourceUuid);
        if (members == null || members.isEmpty()) {
            members = partyManager.getPartyMembers(sourceUuid);
        }
        if (members == null || members.isEmpty()) {
            return Math.max(1, fallback);
        }

        List<Integer> levels = new ArrayList<>();
        for (UUID member : members) {
            levels.add(Math.max(1, getPlayerLevel(member)));
        }
        if (levels.isEmpty()) {
            return Math.max(1, fallback);
        }

        PartyLevelCalculation calc = getPartyLevelCalculationMode(store);
        if (calc == PartyLevelCalculation.MEDIAN) {
            Collections.sort(levels);
            int size = levels.size();
            int mid = size / 2;
            if (size % 2 == 1) {
                return levels.get(mid);
            }
            return (int) Math.round((levels.get(mid - 1) + levels.get(mid)) / 2.0D);
        }

        double sum = 0.0D;
        for (int lvl : levels) {
            sum += lvl;
        }
        return (int) Math.round(sum / levels.size());
    }

    private ViewportCandidate resolveViewportCandidate(Ref<EntityStore> ref,
                                                       Store<EntityStore> store,
                                                       CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || store == null) {
            return null;
        }

        Vector3d mobPos = resolveWorldPosition(ref, commandBuffer);
        if (mobPos == null) {
            return null;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }

        int configuredWorldViewChunks = Math.max(1, resolveWorldViewOrSimulationDistanceChunks(store));

        ViewportCandidate best = null;
        int playersSeen = 0;
        int invalidPlayerRef = 0;
        int invalidEntityRef = 0;
        int worldMismatch = 0;
        int missingPlayerPos = 0;
        int outsideViewport = 0;
        for (PlayerRef playerRef : universe.getPlayers()) {
            playersSeen++;
            if (playerRef == null || !playerRef.isValid()) {
                invalidPlayerRef++;
                continue;
            }

            Ref<EntityStore> playerEntityRef = playerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                invalidEntityRef++;
                continue;
            }

            Store<EntityStore> playerStore = playerEntityRef.getStore();
            if (!isSameWorld(store, playerStore)) {
                worldMismatch++;
                continue;
            }

            Vector3d playerPos = resolveWorldPosition(playerEntityRef, null);
            if (playerPos == null) {
                missingPlayerPos++;
                continue;
            }

            int playerViewChunks = Math.max(1, resolvePlayerViewDistanceChunks(playerStore, playerEntityRef));
            int effectiveChunks = Math.max(configuredWorldViewChunks, playerViewChunks);
            if (!isWithinChunkViewport(mobPos, playerPos, effectiveChunks)) {
                outsideViewport++;
                continue;
            }

            double distSq = horizontalDistanceSquared(mobPos, playerPos);
            if (best == null || distSq < best.distanceSq()) {
                best = new ViewportCandidate(playerRef, playerPos, distSq, effectiveChunks);
            }
        }

        if (best == null && shouldLogMobLevelDebug(ref, store)) {
            LOGGER.atWarning().log(
                    "[MOB_LEVEL_DEBUG] viewport reject summary mob=%d playersSeen=%d invalidPlayerRef=%d invalidEntityRef=%d worldMismatch=%d missingPlayerPos=%d outsideViewport=%d configuredWorldViewChunks=%d",
                    ref.getIndex(),
                    playersSeen,
                    invalidPlayerRef,
                    invalidEntityRef,
                    worldMismatch,
                    missingPlayerPos,
                    outsideViewport,
                    configuredWorldViewChunks);
        }

        return best;
    }

    private int resolveWorldViewOrSimulationDistanceChunks(Store<EntityStore> store) {
        int fromStore = maxPositive(
                invokeIntNoArg(store, "getViewDistance"),
                invokeIntNoArg(store, "getSimulationDistance"),
                invokeIntNoArg(store, "getChunkViewDistance"),
                invokeIntNoArg(store, "getChunkSimulationDistance"));
        if (fromStore > 0) {
            return fromStore;
        }

        Object world = invokeNoArg(store, "getWorld");
        int fromWorld = maxPositive(
                invokeIntNoArg(world, "getViewDistance"),
                invokeIntNoArg(world, "getSimulationDistance"),
                invokeIntNoArg(world, "getChunkViewDistance"),
                invokeIntNoArg(world, "getChunkSimulationDistance"));
        return fromWorld > 0 ? fromWorld : 6;
    }

    private int resolvePlayerViewDistanceChunks(Store<EntityStore> playerStore, Ref<EntityStore> playerRef) {
        if (playerStore == null || playerRef == null) {
            return 1;
        }

        Player playerComp = playerStore.getComponent(playerRef, Player.getComponentType());
        if (playerComp != null) {
            try {
                int radius = playerComp.getViewRadius();
                if (radius > 0) {
                    return radius;
                }
            } catch (Throwable ignored) {
            }
        }

        return 1;
    }

    private boolean isWithinChunkViewport(Vector3d mobPos, Vector3d playerPos, int radiusChunks) {
        int mobChunkX = blockToChunk(mobPos.getX());
        int mobChunkZ = blockToChunk(mobPos.getZ());
        int playerChunkX = blockToChunk(playerPos.getX());
        int playerChunkZ = blockToChunk(playerPos.getZ());
        int dx = Math.abs(mobChunkX - playerChunkX);
        int dz = Math.abs(mobChunkZ - playerChunkZ);
        return dx <= radiusChunks && dz <= radiusChunks;
    }

    private int blockToChunk(double blockCoordinate) {
        return ((int) Math.floor(blockCoordinate)) >> CHUNK_BIT_SHIFT;
    }

    private int getFixedLevel(Store<EntityStore> store, Integer entityId, Vector3d mobPosition) {
        LevelRange range = parseFixedLevelRange(store);
        if (range.min() == range.max()) {
            return range.min();
        }
        long seed = (entityId != null ? entityId.longValue() : hashPosition(mobPosition)) ^ 0x9E3779B97F4A7C15L;
        int span = (range.max() - range.min()) + 1;
        int roll = (int) Math.floorMod(seed, span);
        return range.min() + roll;
    }

    private LevelRange parseTieredBaseLevelRange(Store<EntityStore> store) {
        Object levelRaw = resolveConfigValue("Mob_Leveling.Level_Source.Base_Level.Level", null, store);
        Object minRaw = resolveConfigValue("Mob_Leveling.Level_Source.Base_Level.Min", null, store);
        Object maxRaw = resolveConfigValue("Mob_Leveling.Level_Source.Base_Level.Max", null, store);
        LevelRange parsed = parseLevelRange(levelRaw, minRaw, maxRaw, store);
        if (parsed != null) {
            return parsed;
        }
        return parseFixedLevelRange(store);
    }

    private LevelRange parseFixedLevelRange(Store<EntityStore> store) {
        Object levelRaw = resolveConfigValue("Mob_Leveling.Level_Source.Fixed_Level.Level", null, store);
        Object minRaw = resolveConfigValue("Mob_Leveling.Level_Source.Fixed_Level.Min", null, store);
        Object maxRaw = resolveConfigValue("Mob_Leveling.Level_Source.Fixed_Level.Max", null, store);

        LevelRange parsed = parseLevelRange(levelRaw, minRaw, maxRaw, store);
        if (parsed != null) {
            return parsed;
        }

        int fallback = clampToConfiguredRange(10, store);
        return new LevelRange(fallback, fallback);
    }

    private LevelRange parseLevelRange(Object levelRaw, Object minRaw, Object maxRaw, Store<EntityStore> store) {
        Integer min = parsePositiveInt(minRaw);
        Integer max = parsePositiveInt(maxRaw);

        if (min != null || max != null) {
            int lo = min != null ? min : max;
            int hi = max != null ? max : min;
            return normalizeLevelRange(lo, hi, store);
        }

        LevelRange parsedFromLevel = parseLevelRangeValue(levelRaw, store);
        if (parsedFromLevel != null) {
            return parsedFromLevel;
        }

        return null;
    }

    private LevelRange parseLevelRangeValue(Object raw, Store<EntityStore> store) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value <= 0) {
                return null;
            }
            return normalizeLevelRange(value, value, store);
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        if (text.contains("-")) {
            String[] split = text.split("-", 2);
            if (split.length == 2) {
                try {
                    int a = Integer.parseInt(split[0].trim());
                    int b = Integer.parseInt(split[1].trim());
                    if (a <= 0 || b <= 0) {
                        return null;
                    }
                    return normalizeLevelRange(a, b, store);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }

        try {
            int value = Integer.parseInt(text);
            if (value <= 0) {
                return null;
            }
            return normalizeLevelRange(value, value, store);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LevelRange normalizeLevelRange(int first, int second, Store<EntityStore> store) {
        int lo = clampToConfiguredRange(Math.min(first, second), store);
        int hi = clampToConfiguredRange(Math.max(first, second), store);
        return new LevelRange(Math.min(lo, hi), Math.max(lo, hi));
    }

    private int resolveConfiguredBossBaseLevel(Store<EntityStore> store, int fallback) {
        Object mobOverridesRaw = resolveConfigValue("Mob_Overrides", null, store);
        if (!(mobOverridesRaw instanceof Map<?, ?> mobOverrides) || mobOverrides.isEmpty()) {
            return clampToConfiguredRange(fallback, store);
        }

        int best = Integer.MIN_VALUE;
        for (Object value : mobOverrides.values()) {
            if (!(value instanceof Map<?, ?> entry)) {
                continue;
            }

            LevelRange levelRange = parseLevelRangeValue(entry.get("Level"), store);
            if (levelRange == null) {
                Integer fixed = parsePositiveInt(entry.get("Level"));
                if (fixed != null) {
                    best = Math.max(best, clampToConfiguredRange(fixed, store));
                }
                continue;
            }

            best = Math.max(best, levelRange.max());
        }

        if (best == Integer.MIN_VALUE) {
            return clampToConfiguredRange(fallback, store);
        }
        return clampToConfiguredRange(best, store);
    }

    private int resolveDistanceLevel(Store<EntityStore> store, Vector3d position) {
        if (position == null) {
            return getFixedLevel(store, null, null);
        }

        double centerX = 0.0D;
        double centerZ = 0.0D;
        String center = getConfigString("Mob_Leveling.Level_Source.Distance_Level.Center_Coordinates", "0,0", store);
        if (center != null && center.equalsIgnoreCase("SPAWN")) {
            Vector3d spawn = resolveWorldSpawn(store);
            if (spawn != null) {
                centerX = spawn.getX();
                centerZ = spawn.getZ();
            }
        } else if (center != null) {
            String[] split = center.split(",");
            if (split.length >= 2) {
                try {
                    centerX = Double.parseDouble(split[0].trim());
                    centerZ = Double.parseDouble(split[1].trim());
                } catch (Exception ignored) {
                }
            }
        }

        double distance = Math.sqrt(Math.max(0.0D,
                (position.getX() - centerX) * (position.getX() - centerX)
                        + (position.getZ() - centerZ) * (position.getZ() - centerZ)));

        double blocksPerLevel = Math.max(1.0D,
                getConfigDouble("Mob_Leveling.Level_Source.Distance_Level.Blocks_Per_Level", 100.0D, store));
        int startLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Start_Level", 1, store);
        int minLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Min_Level", 1, store);
        int maxLevel = getConfigInt("Mob_Leveling.Level_Source.Distance_Level.Max_Level", 200, store);
        int level = startLevel + (int) Math.floor(distance / blocksPerLevel);
        level = Math.max(Math.min(minLevel, maxLevel), Math.min(Math.max(minLevel, maxLevel), level));
        return clampToConfiguredRange(level, store);
    }

    private LevelSourceMode getLevelSourceMode(Store<EntityStore> store) {
        String raw = firstNonBlank(
                getConfigString("Mob_Leveling.Level_Source.Mode", null, store),
                getConfigString("Mob_Leveling.Level_Source.mode", null, store),
                getConfigString("Mob_Leveling.Level_Source.Source", null, store),
                getConfigString("Mob_Leveling.Level_Source.Type", null, store),
                getConfigString("Mob_Leveling.Level_Source.Level_Mode", null, store));
        if (raw == null || raw.isBlank()) {
            if (!missingLevelSourceModeWarned) {
                missingLevelSourceModeWarned = true;
                LOGGER.atWarning().log(
                        "[MOB_LEVEL_DEBUG] Missing Mob_Leveling.Level_Source.Mode; defaulting to FIXED. Valid values: PLAYER, MIXED, DISTANCE, FIXED, TIERS.");
            }
            return LevelSourceMode.FIXED;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("TIERED".equals(normalized)) {
            normalized = "TIERS";
        }
        try {
            return LevelSourceMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            LOGGER.atWarning().log(
                    "[MOB_LEVEL_DEBUG] Unknown Mob_Leveling.Level_Source.Mode '%s'; defaulting to FIXED. Valid values: PLAYER, MIXED, DISTANCE, FIXED, TIERS.",
                    raw);
            return LevelSourceMode.FIXED;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean shouldLogMobLevelDebug(Ref<EntityStore> ref, Store<EntityStore> store) {
        int entityId = ref != null ? ref.getIndex() : -1;
        long key = toEntityKey(store != null ? store : (ref != null ? ref.getStore() : null), entityId);
        if (key < 0L) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long previous = mobLevelDebugLogTimes.put(key, now);
        return previous == null || now - previous >= MOB_LEVEL_DEBUG_COOLDOWN_MS;
    }

    private PartyLevelCalculation getPartyLevelCalculationMode(Store<EntityStore> store) {
        String raw = getConfigString("Mob_Leveling.Level_Source.Party_System.Level_Calculation", "AVERAGE", store);
        if (raw == null) {
            return PartyLevelCalculation.AVERAGE;
        }
        return "MEDIAN".equalsIgnoreCase(raw.trim())
                ? PartyLevelCalculation.MEDIAN
                : PartyLevelCalculation.AVERAGE;
    }

    private int getPlayerLevel(UUID uuid) {
        if (uuid == null || playerDataManager == null) {
            return 1;
        }
        PlayerData data = playerDataManager.get(uuid);
        return data == null ? 1 : Math.max(1, data.getLevel());
    }

    private PartyManager resolvePartyManager() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        return plugin != null ? plugin.getPartyManager() : null;
    }

    private <T extends Component<EntityStore>> T resolveComponent(Ref<EntityStore> ref,
                                                                  Store<EntityStore> store,
                                                                  CommandBuffer<EntityStore> commandBuffer,
                                                                  com.hypixel.hytale.component.ComponentType<EntityStore, T> type) {
        if (commandBuffer != null && ref != null) {
            T fromBuffer = commandBuffer.getComponent(ref, type);
            if (fromBuffer != null) {
                return fromBuffer;
            }
        }
        if (store != null && ref != null) {
            return store.getComponent(ref, type);
        }
        return null;
    }

    private Vector3d resolveWorldPosition(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return null;
        }
        TransformComponent transform = null;
        if (commandBuffer != null) {
            transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        }
        if (transform == null && ref.getStore() != null) {
            transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
        }
        return transform != null ? transform.getPosition() : null;
    }

    private boolean isSameWorld(Store<EntityStore> a, Store<EntityStore> b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }

        Object worldA = invokeNoArg(a, "getWorld");
        Object worldB = invokeNoArg(b, "getWorld");
        if (worldA != null && worldB != null) {
            if (worldA == worldB || worldA.equals(worldB)) {
                return true;
            }
        }

        String wa = resolveWorldIdentifier(a);
        String wb = resolveWorldIdentifier(b);
        return wa != null && wb != null && wa.equalsIgnoreCase(wb);
    }

    private int clampToConfiguredRange(int level, Store<EntityStore> store) {
        int min = getConfigInt("Mob_Leveling.Level_Range.Min", 1, store);
        Object maxRaw = configManager.get("Mob_Leveling.Level_Range.Max", 200, false);
        if (maxRaw != null && "ENDLESS".equalsIgnoreCase(maxRaw.toString().trim())) {
            return Math.max(min, level);
        }

        int max = getConfigInt("Mob_Leveling.Level_Range.Max", 200, store);
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return Math.max(min, Math.min(max, level));
    }

    private MobHealthScalingResult computeMobHealthScaling(int level,
                                                           float baseMax,
                                                           float previousMax,
                                                           float previousValue,
                                                           Store<EntityStore> store) {
        float safeBase = Math.max(1.0f, baseMax);
        float target = (float) Math.max(1.0D, safeBase * getMobHealthMultiplierForLevel(level, store));
        float additive = target - safeBase;
        float ratio = previousMax > 0.0f ? (previousValue / previousMax) : 1.0f;
        float newValue = Math.max(0.0f, Math.min(target, ratio * target));
        if (previousValue <= 0.0f) {
            newValue = 0.0f;
        }
        return new MobHealthScalingResult(target, additive, newValue);
    }

    private double getMobHealthMultiplierForLevel(int level, Store<EntityStore> store) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Health.Base_Multiplier", 1.0D, store);
        double per = getConfigDouble("Mob_Leveling.Scaling.Health.Per_Level", 0.05D, store);
        int safeLevel = Math.max(1, level);
        return Math.max(0.0001D, base * (1.0D + per * (safeLevel - 1)));
    }

    private double getMobDamageMultiplierForLevel(int level, Store<EntityStore> store) {
        double base = getConfigDouble("Mob_Leveling.Scaling.Damage.Base_Multiplier", 1.0D, store);
        double per = getConfigDouble("Mob_Leveling.Scaling.Damage.Per_Level", 0.03D, store);
        int safeLevel = Math.max(1, level);
        return Math.max(0.0001D, base * (1.0D + per * (safeLevel - 1)));
    }

    private double getMobDamageMaxDifferenceMultiplierForLevelDifference(Store<EntityStore> store, int levelDifference) {
        int maxDiff = Math.max(1, getConfigInt("Mob_Leveling.Scaling.Level_Scaling_Difference.Range", 10, store));
        double atNeg = clampNonNegativeMultiplier(getConfigDouble(
                "Mob_Leveling.Scaling.Damage.Max_Difference.At_Negative_Max_Difference", 1.0D, store));
        double atPos = clampNonNegativeMultiplier(getConfigDouble(
                "Mob_Leveling.Scaling.Damage.Max_Difference.At_Positive_Max_Difference", 1.0D, store));

        if (levelDifference <= -maxDiff) {
            return atNeg;
        }
        if (levelDifference >= maxDiff) {
            return atPos;
        }

        double ratio = (levelDifference + maxDiff) / (double) (maxDiff * 2);
        return lerp(atNeg, atPos, ratio);
    }

    private Set<String> parseAugmentBlacklist(Store<EntityStore> store) {
        Object raw = resolveConfigValue("Mob_Augments.Blacklisted_Augments", null, store);
        if (raw == null) {
            raw = configManager.get("Mob_Leveling.Mob_Augments.Blacklisted_Augments", null, false);
        }
        if (raw == null) {
            return Set.of();
        }
        List<String> list = new ArrayList<>();
        appendStringRules(list, raw);
        if (list.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String id : list) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private int resolveCommonAugmentCountFromPerLevel(int level, Store<EntityStore> store) {
        Object raw = resolveConfigValue("Mob_Augments.Common.Per_Level", null, store);
        if (raw == null) {
            return 0;
        }

        if (raw instanceof Number number) {
            double perLevel = Math.max(0.0D, number.doubleValue());
            return (int) Math.max(0, Math.round(level * perLevel));
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return 0;
        }

        if (text.contains("-")) {
            String[] split = text.split("-", 2);
            if (split.length == 2) {
                try {
                    double min = Double.parseDouble(split[0].trim());
                    double max = Double.parseDouble(split[1].trim());
                    if (min > max) {
                        double tmp = min;
                        min = max;
                        max = tmp;
                    }
                    double averagePerLevel = Math.max(0.0D, (min + max) / 2.0D);
                    return (int) Math.max(0, Math.round(level * averagePerLevel));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }

        try {
            double single = Math.max(0.0D, Double.parseDouble(text));
            return (int) Math.max(0, Math.round(level * single));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> rollMobCommonStatOffers(AugmentManager augmentManager,
            int commonCount,
            SplittableRandom random) {
        if (augmentManager == null || commonCount <= 0) {
            return List.of();
        }

        AugmentDefinition commonAugmentDefinition = augmentManager.getAugment(CommonAugment.ID);
        if (commonAugmentDefinition == null || commonAugmentDefinition.getTier() != PassiveTier.COMMON) {
            return List.of();
        }

        Map<String, Object> buffs = AugmentValueReader.getMap(commonAugmentDefinition.getPassives(), "buffs");
        if (buffs.isEmpty()) {
            return List.of();
        }

        List<String> statKeys = new ArrayList<>();
        for (String key : buffs.keySet()) {
            if (key != null && !key.isBlank()) {
                String normalized = key.trim().toLowerCase(Locale.ROOT);
                if (MobAugmentDiagnostics.isAllowedMobCommonStatKey(normalized)) {
                    statKeys.add(normalized);
                }
            }
        }
        if (statKeys.isEmpty()) {
            return List.of();
        }

        List<String> offers = new ArrayList<>(commonCount);
        for (int i = 0; i < commonCount; i++) {
            String statKey = statKeys.get(random.nextInt(statKeys.size()));
            Map<String, Object> section = AugmentValueReader.getMap(buffs, statKey);
            double value = rollCommonRange(section, random);
            offers.add(CommonAugment.buildStatOfferId(statKey, value));
        }
        return offers;
    }

    private double rollCommonRange(Map<String, Object> section, SplittableRandom random) {
        double base = Math.max(0.0D, AugmentValueReader.getDouble(section, "value", 0.0D));
        double min = Math.max(0.0D, AugmentValueReader.getDouble(section, "min_value", base));
        double max = Math.max(0.0D, AugmentValueReader.getDouble(section, "max_value", base));

        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }

        if (Math.abs(max - min) < 0.0001D) {
            return roundToTwoDecimals(min);
        }

        if (isWholeNumber(min) && isWholeNumber(max)) {
            long minInt = Math.round(min);
            long maxInt = Math.round(max);
            if (maxInt <= minInt) {
                return minInt;
            }
            return minInt + random.nextLong((maxInt - minInt) + 1L);
        }

        return roundToTwoDecimals(random.nextDouble(min, max));
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private boolean isWholeNumber(double value) {
        return Math.abs(value - Math.rint(value)) < 0.0001D;
    }

    private boolean hasGlobalOrDefaultWorldOverrideBoolean(String worldPath) {
        if (worldPath == null || worldPath.isBlank()) {
            return false;
        }

        String defaultPath = "World_Overrides.default." + worldPath;
        if (worldsConfigManager.hasPath(defaultPath) && toBoolean(worldsConfigManager.get(defaultPath, null, false))) {
            return true;
        }

        String globalPath = "World_Overrides.Global." + worldPath;
        return worldsConfigManager.hasPath(globalPath) && toBoolean(worldsConfigManager.get(globalPath, null, false));
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        return false;
    }

    private long computeAugmentSeed(Ref<EntityStore> ref, Store<EntityStore> store, int level) {
        long trackingKey = resolveTrackingKey(ref, store, null);
        return trackingKey ^ ((long) level * 0x9E3779B97F4A7C15L);
    }

    private PassiveTier rollTier(SplittableRandom random, double elite, double legendary, double mythic) {
        double total = elite + legendary + mythic;
        if (total <= 0.0D) {
            return PassiveTier.ELITE;
        }
        double roll = random.nextDouble(total);
        if (roll < elite) {
            return PassiveTier.ELITE;
        }
        roll -= elite;
        if (roll < legendary) {
            return PassiveTier.LEGENDARY;
        }
        return PassiveTier.MYTHIC;
    }

    private String pickTier(PassiveTier tier,
                            List<String> elite,
                            List<String> legendary,
                            List<String> mythic,
                            List<String> fallback,
                            SplittableRandom random) {
        List<String> pool;
        if (tier == PassiveTier.MYTHIC) {
            pool = mythic;
        } else if (tier == PassiveTier.LEGENDARY) {
            pool = legendary;
        } else {
            pool = elite;
        }
        if (pool == null || pool.isEmpty()) {
            if (fallback.isEmpty()) {
                return null;
            }
            return fallback.get(random.nextInt(fallback.size()));
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private MobAugmentTierPools resolveMobAugmentTierPools(AugmentManager augmentManager, Set<String> blacklistedIds) {
        if (augmentManager == null) {
            return new MobAugmentTierPools(0, 0, List.of(), List.of(), List.of(), List.of());
        }

        int sourceCount = augmentManager.getAugments().size();
        int blacklistHash = blacklistedIds == null ? 0 : blacklistedIds.hashCode();
        MobAugmentTierPools cached = cachedMobAugmentTierPools;
        if (cached != null && cached.sourceCount() == sourceCount && cached.blacklistHash() == blacklistHash) {
            return cached;
        }

        List<String> elite = new ArrayList<>();
        List<String> legendary = new ArrayList<>();
        List<String> mythic = new ArrayList<>();

        for (AugmentDefinition def : augmentManager.getAugments().values()) {
            if (def == null || !def.isMobCompatible() || def.getId() == null || def.getId().isBlank()) {
                continue;
            }
            String id = def.getId().trim();
            if (blacklistedIds != null && blacklistedIds.contains(id.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (def.getTier() == PassiveTier.ELITE) {
                elite.add(id);
            } else if (def.getTier() == PassiveTier.LEGENDARY) {
                legendary.add(id);
            } else if (def.getTier() == PassiveTier.MYTHIC) {
                mythic.add(id);
            }
        }

        List<String> fallback = new ArrayList<>(elite.size() + legendary.size() + mythic.size());
        fallback.addAll(elite);
        fallback.addAll(legendary);
        fallback.addAll(mythic);

        MobAugmentTierPools rebuilt = new MobAugmentTierPools(
                sourceCount,
                blacklistHash,
                List.copyOf(elite),
                List.copyOf(legendary),
                List.copyOf(mythic),
                List.copyOf(fallback));
        cachedMobAugmentTierPools = rebuilt;
        return rebuilt;
    }

    private Vector3d resolveWorldSpawn(Store<EntityStore> store) {
        Object world = invokeNoArg(store, "getWorld");
        Object spawn = invokeNoArg(world, "getSpawnPoint");
        if (spawn instanceof Vector3d vector3d) {
            return vector3d;
        }
        Object pos = invokeNoArg(spawn, "getPosition");
        return pos instanceof Vector3d vector3d ? vector3d : null;
    }

    private int getConfigInt(String path, int defaultValue, Store<EntityStore> store) {
        Object raw = resolveConfigValue(path, defaultValue, store);
        if (raw == null) {
            return defaultValue;
        }
        try {
            if (raw instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(raw.toString().trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double getConfigDouble(String path, double defaultValue, Store<EntityStore> store) {
        Object raw = resolveConfigValue(path, defaultValue, store);
        if (raw == null) {
            return defaultValue;
        }
        try {
            if (raw instanceof Number number) {
                return number.doubleValue();
            }
            return Double.parseDouble(raw.toString().trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String getConfigString(String path, String defaultValue, Store<EntityStore> store) {
        Object raw = resolveConfigValue(path, defaultValue, store);
        return raw == null ? defaultValue : raw.toString();
    }

    private boolean getConfigBoolean(String path, boolean defaultValue, Store<EntityStore> store) {
        Object raw = resolveConfigValue(path, defaultValue, store);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private Object resolveConfigValue(String path, Object defaultValue, Store<EntityStore> store) {
        Object worldOverride = resolveWorldOverrideValue(path, store);
        if (worldOverride != UNSET_CONFIG_VALUE) {
            return worldOverride;
        }
        return configManager.get(path, defaultValue, false);
    }

    private Object resolveWorldOverrideValue(String path, Store<EntityStore> store) {
        if (path == null || path.isBlank()) {
            return UNSET_CONFIG_VALUE;
        }
        if (store == null) {
            return UNSET_CONFIG_VALUE;
        }

        String worldPath = normalizeWorldOverridePath(path);
        List<String> worldIds = resolveWorldIdentifierCandidates(store, false);
        String matchedKey = resolveBestWorldOverrideKey(store, worldIds);
        if (matchedKey != null) {
            String matchedPath = "World_Overrides." + matchedKey + "." + worldPath;
            if (worldsConfigManager.hasPath(matchedPath)) {
                return worldsConfigManager.get(matchedPath, null, false);
            }
        }

        // Treat `default` as a fallback profile only for worlds that are not
        // XP-blacklisted and do not have an explicit non-default match.
        if ((matchedKey == null || "default".equalsIgnoreCase(matchedKey))
                && shouldApplyDefaultWorldOverride(store, worldIds)) {
            String defaultPath = "World_Overrides.default." + worldPath;
            if (worldsConfigManager.hasPath(defaultPath)) {
                return worldsConfigManager.get(defaultPath, null, false);
            }
        }

        String globalPath = "World_Overrides.Global." + worldPath;
        if (worldsConfigManager.hasPath(globalPath)) {
            return worldsConfigManager.get(globalPath, null, false);
        }

        return UNSET_CONFIG_VALUE;
    }

    private boolean shouldApplyDefaultWorldOverride(Store<EntityStore> store, List<String> worldIds) {
        if (store == null) {
            return false;
        }
        if (worldIds == null || worldIds.isEmpty()) {
            return false;
        }
        return !isWorldXpBlacklisted(store);
    }

    private String normalizeWorldOverridePath(String path) {
        String trimmed = path.trim();
        String prefix = "Mob_Leveling.";
        if (trimmed.startsWith(prefix)) {
            return trimmed.substring(prefix.length());
        }
        return trimmed;
    }

    private String resolveBestWorldOverrideKey(Store<EntityStore> store, List<String> worldIds) {
        if (store != null) {
            long storeKey = toStoreKey(store);
            String cached = worldOverrideKeyByStore.get(storeKey);
            if (cached != null) {
                return cached;
            }

            if (worldIds == null || worldIds.isEmpty()) {
                return null;
            }

            String resolved = resolveBestWorldOverrideKey(worldIds);
            if (resolved != null && !resolved.isBlank()) {
                worldOverrideKeyByStore.put(storeKey, resolved);
            }
            return resolved;
        }

        return resolveBestWorldOverrideKey(worldIds);
    }

    private String resolveBestWorldOverrideKey(List<String> worldIds) {
        if (worldIds == null || worldIds.isEmpty()) {
            return null;
        }

        String bestKey = null;
        int bestScore = Integer.MIN_VALUE;
        for (String worldId : worldIds) {
            String candidate = resolveBestWorldOverrideKey(worldId);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            int score = candidate.replace("*", "").length();
            if ("default".equalsIgnoreCase(candidate)) {
                score -= 10_000;
            }
            if (score > bestScore) {
                bestScore = score;
                bestKey = candidate;
            }
        }
        return bestKey;
    }

    private List<String> resolveWorldIdentifierCandidates(Store<EntityStore> store, boolean includeUniverseLookups) {
        if (store == null) {
            return List.of();
        }

        long storeKey = toStoreKey(store);
        List<String> cached = worldIdentifierCandidatesByStore.get(storeKey);
        if (cached != null) {
            return cached;
        }

        // Prefer the authoritative Universe store->world mapping first.
        LinkedHashSet<String> universeFirst = new LinkedHashSet<>();
        addWorldIdentifierFromUniverseStoreLookup(universeFirst, store);
        if (!universeFirst.isEmpty()) {
            List<String> resolved = List.copyOf(universeFirst);
            worldIdentifierCandidatesByStore.put(storeKey, resolved);
            worldIdentifierMissRetryAtStore.remove(storeKey);
            return resolved;
        }

        if (!includeUniverseLookups) {
            long now = System.currentTimeMillis();
            Long retryAt = worldIdentifierMissRetryAtStore.get(storeKey);
            if (retryAt != null && now < retryAt) {
                return List.of();
            }
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            collectWorldIdentifierCandidate(out, store, "getId");
            collectWorldIdentifierCandidate(out, store, "getWorldId");
            collectWorldIdentifierCandidate(out, store, "getIdentifier");
            collectWorldIdentifierCandidate(out, store, "getKey");
            collectWorldIdentifierCandidate(out, store, "getName");

            Object world = invokeNoArg(store, "getWorld");
            collectWorldIdentifierCandidate(out, world, "getId");
            collectWorldIdentifierCandidate(out, world, "getWorldId");
            collectWorldIdentifierCandidate(out, world, "getIdentifier");
            collectWorldIdentifierCandidate(out, world, "getKey");
            collectWorldIdentifierCandidate(out, world, "getName");

            Object worldConfig = invokeNoArg(world, "getWorldConfig");
            collectWorldIdentifierCandidate(out, worldConfig, "getId");
            collectWorldIdentifierCandidate(out, worldConfig, "getWorldId");
            collectWorldIdentifierCandidate(out, worldConfig, "getIdentifier");
            collectWorldIdentifierCandidate(out, worldConfig, "getKey");
            collectWorldIdentifierCandidate(out, worldConfig, "getName");
            collectWorldIdentifierCandidate(out, worldConfig, "getDisplayName");

            // Keep Universe identity enrichment enabled for all paths to avoid
            // store/world timing races returning "unknown".
            addWorldIdentifierFromUniverseStoreLookup(out, store);
            addWorldIdentifierFromUniverseLookup(out, world);

            // Runtime objects sometimes wrap IDs inside toString payloads (e.g. id=instance-foo).
            addWorldIdentifierCandidate(out, String.valueOf(store));
            addWorldIdentifierCandidate(out, String.valueOf(world));
            addWorldIdentifierCandidate(out, String.valueOf(worldConfig));
        } catch (Exception ignored) {
        }

        List<String> resolved = out.isEmpty() ? List.of() : List.copyOf(out);
        if (!resolved.isEmpty()) {
            worldIdentifierCandidatesByStore.put(storeKey, resolved);
            worldIdentifierMissRetryAtStore.remove(storeKey);
        } else if (!includeUniverseLookups) {
            worldIdentifierMissRetryAtStore.put(storeKey, System.currentTimeMillis() + 1000L);
        }
        return resolved;
    }

    private String selectPreferredWorldIdentifier(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }

            int score = 0;
            String normalized = candidate.trim();
            if (isLikelyWorldIdentifier(normalized)) {
                score += 100;
            }
            if ("default".equalsIgnoreCase(normalized)) {
                score += 50;
            }
            if (normalized.contains("-") || normalized.contains("_") || normalized.contains(":")) {
                score += 20;
            }
            score -= normalized.length() / 4;

            if (score > bestScore) {
                bestScore = score;
                best = normalized;
            }
        }

        return best != null ? best : candidates.get(0);
    }

    private String summarizeCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "[]";
        }

        int limit = Math.min(8, candidates.size());
        List<String> summarized = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            summarized.add(safeDebugValue(candidates.get(i)));
        }
        if (candidates.size() > limit) {
            summarized.add("...+" + (candidates.size() - limit));
        }
        return summarized.toString();
    }

    private String buildProbeDump(Object target, String... methodNames) {
        if (target == null) {
            return "{class=<null>}";
        }

        StringBuilder out = new StringBuilder();
        out.append("{class=").append(target.getClass().getSimpleName());
        if (methodNames != null) {
            for (String methodName : methodNames) {
                if (methodName == null || methodName.isBlank()) {
                    continue;
                }
                Object raw = invokeNoArg(target, methodName);
                out.append(", ").append(methodName).append("=").append(safeDebugValue(raw));
            }
        }
        out.append("}");
        return out.toString();
    }

    private String safeDebugValue(Object raw) {
        if (raw == null) {
            return "<null>";
        }
        String value = raw.toString();
        if (value == null || value.isBlank()) {
            return "<blank>";
        }
        String trimmed = value.trim();
        if (trimmed.length() > 120) {
            return trimmed.substring(0, 117) + "...";
        }
        return trimmed;
    }

    private void collectWorldIdentifierCandidate(Set<String> out, Object target, String methodName) {
        if (out == null || target == null || methodName == null || methodName.isBlank()) {
            return;
        }
        Object raw = invokeNoArg(target, methodName);
        if (raw == null) {
            return;
        }
        addWorldIdentifierCandidate(out, raw.toString());
    }

    private void addWorldIdentifierCandidate(Set<String> out, String rawValue) {
        if (out == null || rawValue == null) {
            return;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return;
        }

        addIfLikelyWorldIdentifier(out, value);

        String lower = value.toLowerCase(Locale.ROOT);
        String[] markers = { "id=", "worldid=", "identifier=", "key=", "name=" };
        for (String marker : markers) {
            int from = 0;
            while (from < lower.length()) {
                int idx = lower.indexOf(marker, from);
                if (idx < 0) {
                    break;
                }
                int start = idx + marker.length();
                while (start < value.length()) {
                    char c = value.charAt(start);
                    if (Character.isWhitespace(c) || c == '\'' || c == '"') {
                        start++;
                        continue;
                    }
                    break;
                }

                int end = start;
                while (end < value.length() && isWorldIdentifierChar(value.charAt(end))) {
                    end++;
                }

                if (end > start) {
                    addIfLikelyWorldIdentifier(out, value.substring(start, end));
                }
                from = idx + marker.length();
            }
        }

        String[] tokens = value.split("[\\s\\[\\]{}(),;]+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String cleaned = token.trim();
            if ((cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1)
                    || (cleaned.startsWith("'") && cleaned.endsWith("'") && cleaned.length() > 1)) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
            if (cleaned.length() < 3) {
                continue;
            }
            if (!(cleaned.contains("-") || cleaned.contains("_") || cleaned.contains(":"))) {
                continue;
            }
            addIfLikelyWorldIdentifier(out, cleaned);
        }
    }

    private void addIfLikelyWorldIdentifier(Set<String> out, String candidate) {
        if (out == null || candidate == null) {
            return;
        }
        String normalized = candidate.trim();
        if (!isLikelyWorldIdentifier(normalized)) {
            return;
        }
        out.add(normalized);
    }

    private void addWorldIdentifierFromUniverseLookup(Set<String> out, Object world) {
        if (out == null || world == null) {
            return;
        }

        try {
            var universe = Universe.get();
            if (universe == null) {
                return;
            }

            Map<String, ?> worlds = universe.getWorlds();
            if (worlds != null && !worlds.isEmpty()) {
                for (Map.Entry<String, ?> entry : worlds.entrySet()) {
                    if (entry == null) {
                        continue;
                    }
                    if (isSameWorldObject(world, entry.getValue())) {
                        addIfLikelyWorldIdentifier(out, entry.getKey());
                    }
                }
            }

            Object defaultWorld = universe.getDefaultWorld();
            if (isSameWorldObject(world, defaultWorld)) {
                addIfLikelyWorldIdentifier(out, "default");
            }
        } catch (Throwable ignored) {
        }
    }

    private void addWorldIdentifierFromUniverseStoreLookup(Set<String> out, Store<EntityStore> store) {
        if (out == null || store == null) {
            return;
        }

        try {
            var universe = Universe.get();
            if (universe == null) {
                return;
            }

            Map<String, ?> worlds = universe.getWorlds();
            if (worlds == null || worlds.isEmpty()) {
                return;
            }

            Object defaultWorld = universe.getDefaultWorld();
            for (Map.Entry<String, ?> entry : worlds.entrySet()) {
                if (entry == null) {
                    continue;
                }

                Object world = entry.getValue();
                if (world == null) {
                    continue;
                }

                Object worldEntityStore = invokeNoArg(world, "getEntityStore");
                Object worldStore = invokeNoArg(worldEntityStore, "getStore");
                boolean matchedDirectStore = worldEntityStore == store;
                boolean matchedNestedStore = worldStore == store;
                if (!matchedDirectStore && !matchedNestedStore) {
                    continue;
                }

                addIfLikelyWorldIdentifier(out, entry.getKey());
                Object worldName = invokeNoArg(world, "getName");
                addIfLikelyWorldIdentifier(out, worldName == null ? null : worldName.toString());

                Object worldConfig = invokeNoArg(world, "getWorldConfig");
                if (worldConfig != null) {
                    collectWorldIdentifierCandidate(out, worldConfig, "getName");
                    collectWorldIdentifierCandidate(out, worldConfig, "getDisplayName");
                }

                if (isSameWorldObject(world, defaultWorld)) {
                    addIfLikelyWorldIdentifier(out, "default");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isSameWorldObject(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return a == b || a.equals(b);
    }

    private boolean isLikelyWorldIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if ("null".equalsIgnoreCase(normalized) || "none".equalsIgnoreCase(normalized)) {
            return false;
        }
        if (value.length() > 128) {
            return false;
        }
        if (value.contains("{") || value.contains("}")) {
            return false;
        }
        if (value.contains("class ")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean isWorldIdentifierChar(char c) {
        return Character.isLetterOrDigit(c)
                || c == '_'
                || c == '-'
                || c == ':'
                || c == '.'
                || c == '/';
    }

    private String resolveBestWorldOverrideKey(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return null;
        }

        Object raw = worldsConfigManager.get("World_Overrides", null, false);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }

        String normalizedWorld = worldId.trim().toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = -1;

        for (Object keyRaw : map.keySet()) {
            if (keyRaw == null) {
                continue;
            }
            String key = keyRaw.toString().trim();
            if (key.isEmpty()) {
                continue;
            }
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if ("global".equals(normalizedKey)) {
                continue;
            }

            boolean matches;
            if ("default".equals(normalizedKey)) {
                matches = normalizedWorld.equals(normalizedKey);
            } else if (normalizedKey.contains("*")) {
                String core = normalizedKey.replace("*", "");
                matches = matchesWildcard(normalizedWorld, normalizedKey)
                        || (!core.isEmpty() && normalizedWorld.contains(core));
            } else {
                matches = normalizedWorld.equals(normalizedKey)
                        || normalizedWorld.contains(normalizedKey);
            }
            if (!matches) {
                continue;
            }

            int score = normalizedKey.replace("*", "").length();
            if (score > bestScore) {
                best = key;
                bestScore = score;
            }
        }

        return best;
    }

    private Integer parsePositiveInt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : null;
        }
        try {
            int value = Integer.parseInt(raw.toString().trim());
            return value > 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long hashPosition(Vector3d pos) {
        if (pos == null) {
            return 0L;
        }
        long x = Double.doubleToLongBits(pos.getX());
        long z = Double.doubleToLongBits(pos.getZ());
        return (x * 31L) ^ (z * 17L);
    }

    private long toEntityKey(Store<EntityStore> store, int entityId) {
        long storePart = store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
        return (storePart << 32) | Integer.toUnsignedLong(entityId);
    }

    private long toStoreKey(Store<EntityStore> store) {
        return store == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(store));
    }

    public long resolveTrackingKey(Ref<EntityStore> ref,
                                   Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null) {
            return -1L;
        }

        Store<EntityStore> effectiveStore = store != null ? store : ref.getStore();
        UUIDComponent uuidComponent = null;
        if (commandBuffer != null) {
            uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        }
        if (uuidComponent == null && effectiveStore != null) {
            uuidComponent = effectiveStore.getComponent(ref, UUIDComponent.getComponentType());
        }

        if (uuidComponent != null) {
            try {
                UUID uuid = uuidComponent.getUuid();
                if (uuid != null) {
                    long storePart = effectiveStore == null ? 0L : Integer.toUnsignedLong(System.identityHashCode(effectiveStore));
                    long uuidPart = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 1);
                    return uuidPart ^ (storePart << 32);
                }
            } catch (Throwable ignored) {
            }
        }

        return toEntityKey(effectiveStore, ref.getIndex());
    }

    private double horizontalDistanceSquared(Vector3d a, Vector3d b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return (dx * dx) + (dz * dz);
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private double clampReduction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(-1.0D, Math.min(1.0D, value));
    }

    private double clampNonNegativeMultiplier(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 1.0D;
        }
        return Math.max(0.0D, value);
    }

    private double lerp(double a, double b, double t) {
        double clamped = Math.max(0.0D, Math.min(1.0D, t));
        return a + ((b - a) * clamped);
    }

    private static String normalizeMobType(String raw) {
        return raw.trim()
                .replace('-', '_')
                .replace('.', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static void appendStringRules(List<String> out, Object raw) {
        if (raw == null || out == null) {
            return;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                appendStringRules(out, item);
            }
            return;
        }
        if (raw instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                appendStringRules(out, value);
            }
            return;
        }
        String text = raw.toString();
        if (text.contains(",")) {
            for (String split : text.split(",")) {
                String token = split.trim();
                if (!token.isEmpty()) {
                    out.add(token);
                }
            }
        } else {
            String token = text.trim();
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
    }

    private static boolean matchesWildcard(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }

        int ti = 0;
        int pi = 0;
        int star = -1;
        int mark = 0;

        while (ti < text.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == text.charAt(ti))) {
                ti++;
                pi++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                star = pi++;
                mark = ti;
            } else if (star != -1) {
                pi = star + 1;
                ti = ++mark;
            } else {
                return false;
            }
        }

        while (pi < pattern.length() && pattern.charAt(pi) == '*') {
            pi++;
        }
        return pi == pattern.length();
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int invokeIntNoArg(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    private int maxPositive(int... values) {
        int best = -1;
        for (int value : values) {
            if (value > best) {
                best = value;
            }
        }
        return best;
    }

    private record AreaOverride(String id,
                                String worldId,
                                double centerX,
                                double centerZ,
                                double radiusSq,
                                int minLevel,
                                int maxLevel) {
    }
}
