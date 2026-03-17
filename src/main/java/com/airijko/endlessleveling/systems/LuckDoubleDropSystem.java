package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies the EndlessLeveling luck passive whenever players gain loot inside
 * their inventory.
 */
public class LuckDoubleDropSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    /**
     * Metadata key written onto mob-dropped ItemStacks to bind them to the killer.
     */
    static final String MOB_KILL_TAG_KEY = "el_mob_killer";
    /** Codec used to encode/decode the killer UUID string in item metadata. */
    static final Codec<String> KILL_TAG_CODEC = new StringCodec();

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    /**
     * Last seen live Player entity per UUID, used for immediate deferred replay.
     */
    private final ConcurrentMap<UUID, Player> livePlayersByUuid = new ConcurrentHashMap<>();
    private final Set<UUID> suppressedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, Long> recentOreBreaks = new ConcurrentHashMap<>();
    /**
     * Tracks item IDs released from any player's inventory. This blocks staged-item
     * exploits where players (or their allies) drop loot before a kill and pick it
     * up during the kill window.
     */
    private final ConcurrentMap<String, Long> globallyDroppedItems = new ConcurrentHashMap<>();
    /**
     * Tracks recent container/chest breaks per player for strict anti-container
     * gating.
     */
    private final ConcurrentMap<UUID, Long> recentContainerBreaks = new ConcurrentHashMap<>();
    /** Pending mob loot claims keyed by killer UUID then killed mob UUID. */
    private final ConcurrentMap<UUID, ConcurrentMap<UUID, MobLootClaim>> playerMobLootClaims = new ConcurrentHashMap<>();
    /**
     * Fallback allowance keyed by killer UUID then item ID. This handles cases
     * where
     * pickup transactions strip item metadata, while still binding spawned mob loot
     * to
     * the killer.
     */
    private final ConcurrentMap<UUID, SpawnedMobLootAllowance> spawnedMobLootAllowances = new ConcurrentHashMap<>();
    /**
     * Pickup events that arrived before spawned-loot allowance registration.
     * Replayed on the next inventory mutation once allowance is available.
     */
    private final ConcurrentMap<UUID, ConcurrentMap<String, DeferredMobPickup>> deferredMobPickups = new ConcurrentHashMap<>();
    /**
     * Maps recently killed source-ref index -> kill entry for drop-event matching.
     */
    private final ConcurrentMap<Integer, KillEntry> recentKillsByRefIndex = new ConcurrentHashMap<>();
    /**
     * Maps recently killed source-ref index -> death marker used to bind spawned
     * item
     * entities to a killer.
     */
    private final ConcurrentMap<Integer, MobDeathMarker> recentMobDeathMarkers = new ConcurrentHashMap<>();
    /**
     * Maps recently killed mob UUID → kill entry so MobDropTaggingSystem can tag
     * their drops.
     */
    private final ConcurrentMap<UUID, KillEntry> recentKillsByMob = new ConcurrentHashMap<>();
    /** How long to remember a released item ID for staged-item detection. */
    private static final long PLAYER_DROP_TRACK_TTL_MS = 30_000L;
    /** Lifetime for death-registered mob loot claims. */
    private static final long MOB_LOOT_CLAIM_TTL_MS = 20_000L;
    /**
     * Lifetime for killer-bound spawned-loot allowances used as metadata fallback.
     */
    private static final long SPAWNED_MOB_ALLOWANCE_TTL_MS = 8_000L;
    /** How long a blocked pickup stays eligible for deferred replay. */
    private static final long DEFERRED_MOB_PICKUP_TTL_MS = 3_000L;
    /** How long spawned item entities can be matched to a death marker. */
    private static final long MOB_DEATH_MARKER_TTL_MS = 800L;
    /** Max squared distance between mob death and spawned loot entity. */
    private static final double MOB_DEATH_MATCH_RADIUS_SQ = 64.0D;
    /** Max number of item entities a single death marker can tag. */
    private static final int MOB_DEATH_MATCH_ITEM_BUDGET = 12;
    /**
     * Strict guard window after breaking a container where mob-drop luck is
     * blocked.
     */
    private static final long CONTAINER_BREAK_GUARD_MS = 12_000L;

    public LuckDoubleDropSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull PassiveManager passiveManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
    }

    /**
     * Record that a player recently broke an ore block. The inventory change
     * handler will only treat drops as "ore" when a recent break has been
     * recorded for the player (prevents non-mining inventory additions).
     */
    public void markRecentOreBreak(@Nonnull UUID uuid) {
        recentOreBreaks.put(uuid, System.currentTimeMillis());
    }

    /**
     * Record that a player broke a loot container (chest/barrel/etc.). Mob-drop
     * luck is temporarily blocked to prevent container loot from piggy-backing on a
     * kill window.
     */
    public void markRecentContainerBreak(@Nonnull UUID uuid) {
        recentContainerBreaks.put(uuid, System.currentTimeMillis());
    }

    /**
     * Registers mob loot from a death event. Claims are bound to both killer and
     * killed mob and can only be consumed by the killer on pickup.
     */
    public void registerMobKillLoot(@Nonnull UUID killerUuid,
            Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            ItemStack[] mobDrops) {
        long now = System.currentTimeMillis();
        long expiresAt = now + MOB_LOOT_CLAIM_TTL_MS;

        if (EntityRefUtil.isUsable(targetRef)) {
            recentKillsByRefIndex.put(targetRef.getIndex(), new KillEntry(killerUuid, expiresAt));
            Vector3d deathPos = resolveEntityPosition(targetRef, store, commandBuffer);
            if (deathPos != null) {
                recentMobDeathMarkers.put(targetRef.getIndex(), new MobDeathMarker(
                        killerUuid,
                        now + MOB_DEATH_MARKER_TTL_MS,
                        deathPos.getX(),
                        deathPos.getY(),
                        deathPos.getZ(),
                        new AtomicInteger(MOB_DEATH_MATCH_ITEM_BUDGET)));
            }
        }

        UUID mobUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (mobUuid == null) {
            LOGGER.atFine().log(
                    "[MOB_LUCK] registerMobKillLoot: could not resolve mob UUID for killer=%s; registered by ref index and opening window",
                    killerUuid);
            pruneExpiredKillEntries(now);
            passiveManager.openMobDropWindow(killerUuid);
            return;
        }
        // Register the kill so MobDropTaggingSystem can stamp drops from this mob.
        recentKillsByMob.put(mobUuid, new KillEntry(killerUuid, expiresAt));
        Map<String, Integer> lootByItem = buildMobLootMap(mobDrops);
        if (lootByItem.isEmpty()) {
            // DeathComponent does not expose loot table drops (always empty in Hytale);
            // open the window and rely on time-window + staged-item guards only.
            LOGGER.atFine().log(
                    "[MOB_LUCK] registerMobKillLoot: DeathComponent had no drops for killer=%s mob=%s; opening window without claim",
                    killerUuid, mobUuid);
            pruneExpiredKillEntries(now);
            pruneExpiredMobLootClaims(killerUuid, now);
            passiveManager.openMobDropWindow(killerUuid);
            return;
        }
        playerMobLootClaims.computeIfAbsent(killerUuid, key -> new ConcurrentHashMap<>())
                .put(mobUuid, new MobLootClaim(new ConcurrentHashMap<>(lootByItem), expiresAt));
        LOGGER.atFine().log("[MOB_LUCK] registerMobKillLoot: registered claim killer=%s mob=%s items=%s ttl=%ds",
                killerUuid, mobUuid, lootByItem, MOB_LOOT_CLAIM_TTL_MS / 1000);
        pruneExpiredKillEntries(now);
        pruneExpiredMobLootClaims(killerUuid, now);
        passiveManager.openMobDropWindow(killerUuid);
    }

    /**
     * Resolves the killer UUID for a drop event source entity. First matches by
     * source-ref index, then falls back to mob UUID matching.
     */
    public UUID getKillerForDropSource(Ref<EntityStore> sourceRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        long now = System.currentTimeMillis();

        if (EntityRefUtil.isUsable(sourceRef)) {
            int sourceIndex = sourceRef.getIndex();
            KillEntry byRef = recentKillsByRefIndex.get(sourceIndex);
            if (byRef != null) {
                if (now <= byRef.expiresAt()) {
                    return byRef.killerUuid();
                }
                recentKillsByRefIndex.remove(sourceIndex);
            }
        }

        UUID sourceUuid = resolveEntityUuid(sourceRef, store, commandBuffer);
        if (sourceUuid == null) {
            return null;
        }
        KillEntry byUuid = recentKillsByMob.get(sourceUuid);
        if (byUuid == null) {
            return null;
        }
        if (now > byUuid.expiresAt()) {
            recentKillsByMob.remove(sourceUuid);
            return null;
        }
        return byUuid.killerUuid();
    }

    /**
     * Resolves killer UUID for a newly spawned item entity by matching its position
     * to a recent mob death marker.
     */
    public UUID resolveKillerForSpawnedItem(Ref<EntityStore> itemRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        Vector3d itemPos = resolveEntityPosition(itemRef, store, commandBuffer);
        if (itemPos == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        MobDeathMarker best = null;
        Integer bestKey = null;
        double bestDistSq = Double.MAX_VALUE;

        for (var entry : recentMobDeathMarkers.entrySet()) {
            Integer key = entry.getKey();
            MobDeathMarker marker = entry.getValue();
            if (marker == null || marker.expiresAt() <= now || marker.remainingTags().get() <= 0) {
                recentMobDeathMarkers.remove(key);
                continue;
            }

            double dx = itemPos.getX() - marker.x();
            double dy = itemPos.getY() - marker.y();
            double dz = itemPos.getZ() - marker.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > MOB_DEATH_MATCH_RADIUS_SQ) {
                continue;
            }
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = marker;
                bestKey = key;
            }
        }

        if (best == null || bestKey == null) {
            return null;
        }

        if (!tryConsumeMarkerBudget(best)) {
            recentMobDeathMarkers.remove(bestKey);
            return null;
        }

        if (best.remainingTags().get() <= 0) {
            recentMobDeathMarkers.remove(bestKey);
        }

        return best.killerUuid();
    }

    /**
     * Registers a spawned item as killer-bound mob loot. Used as fallback when
     * pickup
     * transactions lose item metadata tags.
     */
    public void registerSpawnedMobLoot(@Nonnull UUID killerUuid, @Nonnull ItemStack itemStack) {
        if (!hasMobKillTag(killerUuid, itemStack)) {
            LOGGER.atFine().log(
                    "[MOB_LUCK] registerSpawnedMobLoot: skipped allowance for untagged or mismatched item killer=%s item=%s",
                    killerUuid, itemStack.getItemId());
            return;
        }

        String itemId = normalizeItemId(itemStack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        int quantity = Math.max(1, itemStack.getQuantity());
        long now = System.currentTimeMillis();
        long expiresAt = now + SPAWNED_MOB_ALLOWANCE_TTL_MS;

        spawnedMobLootAllowances.compute(killerUuid, (uuid, current) -> {
            SpawnedMobLootAllowance allowance = current;
            if (allowance == null || allowance.expiresAt <= now) {
                allowance = new SpawnedMobLootAllowance(new ConcurrentHashMap<>(), expiresAt);
            } else {
                allowance.expiresAt = Math.max(allowance.expiresAt, expiresAt);
            }
            allowance.remainingByItem.merge(itemId, quantity, Integer::sum);
            return allowance;
        });
        LOGGER.atFine().log("[MOB_LUCK] registerSpawnedMobLoot: killer=%s item=%s qty=%d", killerUuid, itemId,
                quantity);

        // Allowance can arrive after pickup handling in the same kill event.
        // If we have a live player entity, try replaying deferred pickups now.
        tryProcessDeferredReplaysForKiller(killerUuid);
    }

    /**
     * Applies mob-drop luck immediately when a spawned world item is matched to a
     * recent mob death. This makes mob luck provenance deterministic and prevents
     * non-mob inventory additions (manual drops, chest drops, player death drops)
     * from consuming kill windows.
     */
    public void applyMobLuckToSpawnedDrop(@Nonnull UUID killerUuid, @Nonnull ItemComponent itemComponent) {
        ItemStack currentStack = itemComponent.getItemStack();
        if (currentStack == null || ItemStack.isEmpty(currentStack)) {
            return;
        }

        // Always strip provenance metadata before the item reaches inventory so
        // normal stack merging is preserved.
        ItemStack sanitizedBase = currentStack.withMetadata(MOB_KILL_TAG_KEY, KILL_TAG_CODEC, null);

        PlayerData playerData = playerDataManager.get(killerUuid);
        if (playerData == null) {
            itemComponent.setItemStack(sanitizedBase);
            return;
        }

        double luckValue = passiveManager.getLuckValue(playerData);
        if (luckValue <= 0.0D) {
            itemComponent.setItemStack(sanitizedBase);
            return;
        }

        if (!passiveManager.hasMobDropStack(killerUuid) || !passiveManager.consumeMobDropStack(killerUuid)) {
            itemComponent.setItemStack(sanitizedBase);
            LOGGER.atFine().log(
                    "[MOB_LUCK] applyMobLuckToSpawnedDrop: killer=%s item=%s no mob-drop stack available; leaving base quantity",
                    killerUuid, sanitizedBase.getItemId());
            return;
        }

        int baseAmount = Math.max(1, sanitizedBase.getQuantity());
        double clamped = Math.min(100.0D, Math.max(0.0D, luckValue));
        double roll = ThreadLocalRandom.current().nextDouble(100.0D);
        boolean success = roll < clamped;
        String playerName = playerData.getPlayerName() == null || playerData.getPlayerName().isBlank()
                ? killerUuid.toString()
                : playerData.getPlayerName();

        if (!success) {
            itemComponent.setItemStack(sanitizedBase);
            LOGGER.atFiner().log(
                    "Luck double-drop failed for %s (chance=%.2f%% roll=%.2f | type=%s)",
                    playerName, clamped, roll, DropType.MOB);
            return;
        }

        ItemStack boosted = sanitizedBase.withQuantity(baseAmount * 2);
        itemComponent.setItemStack(boosted);
        passiveManager.notifyLuckDoubleDrop(playerData, formatDropName(sanitizedBase), baseAmount, baseAmount);
        LOGGER.atFiner().log(
                "Luck double-drop triggered for %s (chance=%.2f%% roll=%.2f | type=%s)",
                playerName, clamped, roll, DropType.MOB);
        LOGGER.atFiner().log("Luck double-drop applied to %s for %s: +%d",
                playerName, sanitizedBase.getItemId(), baseAmount);
    }

    /**
     * Returns the killer UUID for a recently killed mob, or {@code null} if the mob
     * UUID is unknown or the kill entry has expired. Used by
     * {@link MobDropTaggingSystem} to tag dropped items.
     */
    public UUID getKillerForMob(UUID mobUuid) {
        if (mobUuid == null) {
            return null;
        }
        KillEntry entry = recentKillsByMob.get(mobUuid);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            recentKillsByMob.remove(mobUuid);
            return null;
        }
        return entry.killerUuid();
    }

    /**
     * Explicitly marks a player-thrown stack as not eligible for mob-luck matching.
     */
    public void markExplicitPlayerDrop(@Nonnull ItemStack stack) {
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        globallyDroppedItems.put(itemId, System.currentTimeMillis());
        LOGGER.atFiner().log(
                "[MOB_LUCK] markExplicitPlayerDrop: tracked spawned player-thrown item=%s qty=%d",
                itemId, Math.max(1, stack.getQuantity()));
    }

    /**
     * Records items explicitly thrown onto the ground by a player before the item
     * entity is spawned.
     */
    public void onExplicitItemDrop(@Nonnull DropItemEvent.Drop event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || ItemStack.isEmpty(stack)) {
            return;
        }
        markExplicitPlayerDrop(stack);
    }

    /**
     * Invoked when a living entity's inventory changes. Only players are eligible
     * for luck procs.
     */
    public void onInventoryChange(@Nonnull LivingEntityInventoryChangeEvent event) {
        LivingEntity livingEntity = event.getEntity();
        if (!(livingEntity instanceof Player player)) {
            return;
        }

        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        UUID uuid = playerRef.getUuid();
        livePlayersByUuid.put(uuid, player);
        if (suppressedPlayers.contains(uuid)) {
            return;
        }

        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof ItemStackTransaction stackTransaction)) {
            return;
        }

        // Track items the player releases from their inventory so we can detect
        // pre-staged items later. Must run before the addedAmount guard below.
        trackRemovedItems(uuid, stackTransaction);

        ItemStack sourceStack = resolveSourceStack(stackTransaction);
        if (sourceStack == null || ItemStack.isEmpty(sourceStack)) {
            return;
        }

        int addedAmount = resolveAddedAmount(stackTransaction);
        if (addedAmount <= 0) {
            return;
        }

        // Some inventory mutations (e.g. ground loot pickup) report non-ADD actions;
        // rely on
        // the computed positive delta instead so we still trigger for mob drops.

        PlayerData playerData = playerDataManager.get(uuid);
        if (playerData == null) {
            return;
        }

        double luckValue = passiveManager.getLuckValue(playerData);
        if (luckValue <= 0.0D) {
            LOGGER.atFiner().log("[MOB_LUCK] onInventoryChange: player=%s luck=%.2f; passive not active, skipping",
                    uuid, luckValue);
            return;
        }

        processDeferredMobPickupReplays(player, playerRef, playerData, luckValue);

        Optional<DropType> dropType = resolveDropType(uuid, sourceStack, addedAmount);
        if (dropType.isEmpty()) {
            return;
        }

        if (!shouldTrigger(playerRef, luckValue, dropType.get())) {
            return;
        }

        int bonusAmount = addedAmount;
        if (bonusAmount <= 0) {
            return;
        }

        if (grantBonus(player, playerRef, sourceStack, bonusAmount)) {
            passiveManager.notifyLuckDoubleDrop(playerData, formatDropName(sourceStack), addedAmount, bonusAmount);
        }
    }

    private boolean grantBonus(@Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull ItemStack templateStack,
            int bonusAmount) {
        CombinedItemContainer container = player.getInventory().getCombinedHotbarFirst();
        if (container == null) {
            return false;
        }

        ItemStack bonusStack = templateStack.withQuantity(bonusAmount);
        // Strip the mob-kill provenance tag so bonus items can stack with existing
        // inventory slots (which have the tag stripped on pickup).
        bonusStack = bonusStack.withMetadata(MOB_KILL_TAG_KEY, KILL_TAG_CODEC, null);
        UUID uuid = playerRef.getUuid();
        suppressedPlayers.add(uuid);
        try {
            ItemStackTransaction transaction = container.addItemStack(bonusStack);
            ItemStack remainder = transaction.getRemainder();
            if (remainder != null && !ItemStack.isEmpty(remainder)) {
                LOGGER.atFine().log("Luck bonus overflow for %s on %s (%d remainder)",
                        playerRef.getUsername(), templateStack.getItemId(), remainder.getQuantity());
            }
            LOGGER.atFiner().log("Luck double-drop applied to %s for %s: +%d",
                    playerRef.getUsername(), templateStack.getItemId(), bonusAmount);
            return true;
        } catch (Exception exception) {
            LOGGER.atWarning().withCause(exception).log("Failed to award luck bonus to %s", playerRef.getUsername());
            return false;
        } finally {
            suppressedPlayers.remove(uuid);
        }
    }

    private ItemStack resolveSourceStack(@Nonnull ItemStackTransaction transaction) {
        ItemStack query = transaction.getQuery();
        if (query != null && !ItemStack.isEmpty(query)) {
            return query;
        }
        List<ItemStackSlotTransaction> slotTransactions = transaction.getSlotTransactions();
        for (ItemStackSlotTransaction slotTransaction : slotTransactions) {
            ItemStack slotAfter = slotTransaction.getSlotAfter();
            if (slotAfter != null && !ItemStack.isEmpty(slotAfter)) {
                return slotAfter;
            }
        }
        return null;
    }

    private int resolveAddedAmount(@Nonnull ItemStackTransaction transaction) {
        ItemStack query = transaction.getQuery();
        if (query != null && !ItemStack.isEmpty(query)) {
            int amount = query.getQuantity();
            ItemStack remainder = transaction.getRemainder();
            if (remainder != null && !ItemStack.isEmpty(remainder)) {
                amount -= remainder.getQuantity();
            }
            if (amount > 0) {
                return amount;
            }
        }

        int amount = 0;
        for (ItemStackSlotTransaction slotTransaction : transaction.getSlotTransactions()) {
            ItemStack before = slotTransaction.getSlotBefore();
            ItemStack after = slotTransaction.getSlotAfter();
            int beforeQty = before == null ? 0 : before.getQuantity();
            int afterQty = after == null ? 0 : after.getQuantity();
            int delta = afterQty - beforeQty;
            if (delta > 0) {
                amount += delta;
            }
        }
        return amount;
    }

    private boolean shouldTrigger(@Nonnull PlayerRef playerRef,
            double value,
            @Nonnull DropType dropType) {
        double clamped = Math.min(100.0D, Math.max(0.0D, value));
        double roll = ThreadLocalRandom.current().nextDouble(100.0D);
        boolean success = roll < clamped;
        if (success) {
            LOGGER.atFiner().log(
                    "Luck double-drop triggered for %s (chance=%.2f%% roll=%.2f | type=%s)",
                    playerRef.getUsername(), clamped, roll, dropType);
        } else {
            LOGGER.atFiner().log(
                    "Luck double-drop failed for %s (chance=%.2f%% roll=%.2f | type=%s)",
                    playerRef.getUsername(), clamped, roll, dropType);
        }
        return success;
    }

    private Optional<DropType> resolveDropType(@Nonnull UUID uuid, @Nonnull ItemStack stack, int addedAmount) {
        if (isRecentOreDrop(uuid, stack)) {
            return Optional.of(DropType.ORE);
        }

        // Ores are only eligible from mining markers, never from mob fallback.
        if (isOreStack(stack)) {
            return Optional.empty();
        }

        // Mob-drop luck is resolved at world-item spawn time in
        // MobDropTaggingSystem#onEntityAdded via applyMobLuckToSpawnedDrop().
        // Inventory events should only drive ore luck.
        return Optional.empty();
    }

    private void enqueueDeferredMobPickup(@Nonnull UUID playerUuid, @Nonnull ItemStack stack, int addedAmount) {
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        int quantity = Math.max(1, addedAmount);
        ItemStack template = stack.withQuantity(1);
        long expiresAt = System.currentTimeMillis() + DEFERRED_MOB_PICKUP_TTL_MS;

        deferredMobPickups.compute(playerUuid, (uuid, pendingByItem) -> {
            ConcurrentMap<String, DeferredMobPickup> map = pendingByItem;
            if (map == null) {
                map = new ConcurrentHashMap<>();
            }
            map.merge(itemId, new DeferredMobPickup(template, quantity, expiresAt), DeferredMobPickup::merge);
            return map;
        });
    }

    private void processDeferredMobPickupReplays(@Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull PlayerData playerData,
            double luckValue) {
        UUID uuid = playerRef.getUuid();
        ConcurrentMap<String, DeferredMobPickup> pendingByItem = deferredMobPickups.get(uuid);
        if (pendingByItem == null || pendingByItem.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (var entry : pendingByItem.entrySet()) {
            String itemId = entry.getKey();
            DeferredMobPickup pending = entry.getValue();
            if (pending == null || pending.expiresAt <= now) {
                pendingByItem.remove(itemId);
                continue;
            }

            ItemStack template = pending.templateStack;
            if (template == null || ItemStack.isEmpty(template)) {
                pendingByItem.remove(itemId);
                continue;
            }

            int quantity = Math.max(1, pending.quantity);
            if (!consumeSpawnedMobLootAllowance(uuid, template, quantity)) {
                continue;
            }

            if (!passiveManager.hasMobDropStack(uuid) || !passiveManager.consumeMobDropStack(uuid)) {
                LOGGER.atFine().log(
                        "[MOB_LUCK] deferredMobPickup: player=%s item=%s allowance ready but no mob-drop stack available; skipping replay",
                        uuid, itemId);
                pendingByItem.remove(itemId);
                continue;
            }

            LOGGER.atFine().log("[MOB_LUCK] deferredMobPickup: replaying luck roll player=%s item=%s qty=%d",
                    uuid, itemId, quantity);

            if (shouldTrigger(playerRef, luckValue, DropType.MOB)
                    && grantBonus(player, playerRef, template, quantity)) {
                passiveManager.notifyLuckDoubleDrop(playerData, formatDropName(template), quantity, quantity);
            }
            pendingByItem.remove(itemId);
        }

        if (pendingByItem.isEmpty()) {
            deferredMobPickups.remove(uuid);
        }
    }

    private void tryProcessDeferredReplaysForKiller(@Nonnull UUID killerUuid) {
        Player player = livePlayersByUuid.get(killerUuid);
        if (player == null) {
            return;
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            livePlayersByUuid.remove(killerUuid);
            return;
        }
        PlayerData playerData = playerDataManager.get(killerUuid);
        if (playerData == null) {
            return;
        }
        double luckValue = passiveManager.getLuckValue(playerData);
        if (luckValue <= 0.0D) {
            return;
        }
        processDeferredMobPickupReplays(player, playerRef, playerData, luckValue);
    }

    private boolean isRecentOreDrop(@Nonnull UUID uuid, @Nonnull ItemStack stack) {
        if (!isOreStack(stack)) {
            return false;
        }
        Long t = recentOreBreaks.get(uuid);
        if (t == null || System.currentTimeMillis() - t > 3000L) {
            return false;
        }
        recentOreBreaks.remove(uuid);
        return true;
    }

    /**
     * Used by MobDropTaggingSystem to avoid stamping allowances on freshly
     * player-dropped items that happen to spawn near a mob death marker.
     */
    public boolean isLikelyPlayerDropped(@Nonnull ItemStack stack) {
        return isRecentlyDroppedByAnyPlayer(stack);
    }

    private boolean isOreStack(@Nonnull ItemStack stack) {
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String[] tokens = itemId.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        for (String token : tokens) {
            if (token.equals("ORE")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records item IDs leaving any player's inventory for staged-item detection.
     */
    private void trackRemovedItems(@Nonnull UUID uuid, @Nonnull ItemStackTransaction transaction) {
        long now = System.currentTimeMillis();

        ActionType txAction = transaction.getAction();
        if (txAction != null && (txAction.isRemove() || txAction.isDestroy())) {
            ItemStack query = transaction.getQuery();
            String txItemId = normalizeItemId(query != null ? query.getItemId() : null);
            if (txItemId != null) {
                globallyDroppedItems.put(txItemId, now);
                LOGGER.atFiner().log(
                        "[MOB_LUCK] trackRemovedItems: tracked as player-dropped item=%s (transaction action=%s)",
                        txItemId, txAction);
            }
        }

        for (ItemStackSlotTransaction slot : transaction.getSlotTransactions()) {
            ItemStack before = slot.getSlotBefore();
            if (before == null || ItemStack.isEmpty(before)) {
                continue;
            }
            String itemId = normalizeItemId(before.getItemId());
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            ItemStack after = slot.getSlotAfter();
            int beforeQty = before.getQuantity();
            int afterQty = after == null ? 0 : after.getQuantity();
            ActionType slotAction = slot.getAction();
            boolean removeLike = slotAction != null && (slotAction.isRemove() || slotAction.isDestroy());
            if (removeLike || afterQty < beforeQty) {
                // Items left a player's inventory in this slot.
                globallyDroppedItems.put(itemId, now);
                LOGGER.atFiner().log(
                        "[MOB_LUCK] trackRemovedItems: tracked as player-dropped item=%s (slot action=%s before=%d after=%d)",
                        itemId, slotAction, beforeQty, afterQty);
            }
        }
    }

    private boolean isRecentlyDroppedByAnyPlayer(@Nonnull ItemStack stack) {
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Long releaseTime = globallyDroppedItems.get(itemId);
        if (releaseTime == null) {
            LOGGER.atFiner().log(
                    "[MOB_LUCK] isRecentlyDroppedByAnyPlayer: item=%s NOT in drop-tracking (not dropped by any player)",
                    itemId);
            return false;
        }
        long now = System.currentTimeMillis();
        long ageMs = now - releaseTime;
        if (ageMs > PLAYER_DROP_TRACK_TTL_MS) {
            globallyDroppedItems.remove(itemId);
            LOGGER.atFiner().log(
                    "[MOB_LUCK] isRecentlyDroppedByAnyPlayer: item=%s expired from tracking (ageMs=%d > ttl=%d)",
                    itemId, ageMs, PLAYER_DROP_TRACK_TTL_MS);
            return false;
        }
        LOGGER.atFiner().log(
                "[MOB_LUCK] isRecentlyDroppedByAnyPlayer: item=%s FOUND in tracking (ageMs=%d <= ttl=%d)",
                itemId, ageMs, PLAYER_DROP_TRACK_TTL_MS);
        return true;
    }

    /**
     * Returns {@code true} when the item ID was removed from any player's
     * inventory before the current mob kill, indicating it may have been
     * pre-staged on the ground to exploit the luck double-drop window.
     */
    private boolean isItemStagedByAnyPlayer(@Nonnull ItemStack stack, long killTimestamp) {
        if (killTimestamp <= 0L) {
            return false;
        }
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Long releaseTime = globallyDroppedItems.get(itemId);
        if (releaseTime == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - releaseTime > PLAYER_DROP_TRACK_TTL_MS) {
            globallyDroppedItems.remove(itemId);
            return false;
        }
        // Block if the item was removed from inventory before (or exactly at) the
        // mob-kill timestamp.
        return releaseTime <= killTimestamp;
    }

    private boolean isContainerBreakGuardActive(@Nonnull UUID uuid) {
        Long breakTime = recentContainerBreaks.get(uuid);
        if (breakTime == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - breakTime > CONTAINER_BREAK_GUARD_MS) {
            recentContainerBreaks.remove(uuid);
            return false;
        }
        return true;
    }

    private String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        return itemId.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Integer> buildMobLootMap(ItemStack[] mobDrops) {
        Map<String, Integer> lootByItem = new ConcurrentHashMap<>();
        if (mobDrops == null) {
            return lootByItem;
        }
        for (ItemStack drop : mobDrops) {
            if (drop == null || ItemStack.isEmpty(drop)) {
                continue;
            }
            String itemId = normalizeItemId(drop.getItemId());
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            int quantity = Math.max(1, drop.getQuantity());
            lootByItem.merge(itemId, quantity, Integer::sum);
        }
        return lootByItem;
    }

    private boolean consumeMobLootClaim(@Nonnull UUID playerUuid, @Nonnull ItemStack stack, int addedAmount) {
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        ConcurrentMap<UUID, MobLootClaim> byMob = playerMobLootClaims.get(playerUuid);
        if (byMob == null || byMob.isEmpty()) {
            // No claim was registered — DeathComponent had no drops, so fall through
            // to window-only gating (hasMobDropStack / consumeMobDropStack).
            LOGGER.atFine().log(
                    "[MOB_LUCK] consumeMobLootClaim: no claims for player=%s item=%s; allowing via window-only gate",
                    playerUuid, itemId);
            return true;
        }

        int remaining = Math.max(1, addedAmount);
        long now = System.currentTimeMillis();
        for (var entry : byMob.entrySet()) {
            UUID mobUuid = entry.getKey();
            MobLootClaim claim = entry.getValue();
            if (claim == null || claim.expiresAt <= now) {
                byMob.remove(mobUuid);
                continue;
            }

            remaining = claim.consume(itemId, remaining);
            if (claim.isEmpty()) {
                byMob.remove(mobUuid);
            }
            if (remaining <= 0) {
                break;
            }
        }

        if (byMob.isEmpty()) {
            playerMobLootClaims.remove(playerUuid);
        }
        boolean satisfied = remaining <= 0;
        LOGGER.atFine().log(
                "[MOB_LUCK] consumeMobLootClaim: player=%s item=%s addedAmount=%d remaining=%d satisfied=%s",
                playerUuid, itemId, Math.max(1, addedAmount), remaining, satisfied);
        return satisfied;
    }

    private boolean consumeSpawnedMobLootAllowance(@Nonnull UUID playerUuid, @Nonnull ItemStack stack,
            int addedAmount) {
        String itemId = normalizeItemId(stack.getItemId());
        if (itemId == null || itemId.isBlank()) {
            return false;
        }

        SpawnedMobLootAllowance allowance = spawnedMobLootAllowances.get(playerUuid);
        if (allowance == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (allowance.expiresAt <= now) {
            spawnedMobLootAllowances.remove(playerUuid);
            return false;
        }

        int required = Math.max(1, addedAmount);
        Integer available = allowance.remainingByItem.get(itemId);
        if (available == null || available <= 0) {
            return false;
        }

        int consumed = Math.min(available, required);
        int remainingItem = available - consumed;
        if (remainingItem > 0) {
            allowance.remainingByItem.put(itemId, remainingItem);
        } else {
            allowance.remainingByItem.remove(itemId);
        }

        if (allowance.remainingByItem.isEmpty()) {
            spawnedMobLootAllowances.remove(playerUuid);
        }

        boolean satisfied = consumed >= required;
        LOGGER.atFine().log(
                "[MOB_LUCK] consumeSpawnedMobLootAllowance: player=%s item=%s required=%d consumed=%d satisfied=%s",
                playerUuid, itemId, required, consumed, satisfied);
        return satisfied;
    }

    private void pruneExpiredMobLootClaims(@Nonnull UUID killerUuid, long now) {
        ConcurrentMap<UUID, MobLootClaim> byMob = playerMobLootClaims.get(killerUuid);
        if (byMob == null || byMob.isEmpty()) {
            return;
        }
        for (var entry : byMob.entrySet()) {
            MobLootClaim claim = entry.getValue();
            if (claim == null || claim.expiresAt <= now || claim.isEmpty()) {
                byMob.remove(entry.getKey());
            }
        }
        if (byMob.isEmpty()) {
            playerMobLootClaims.remove(killerUuid);
        }
    }

    private void pruneExpiredKillEntries(long now) {
        for (var entry : recentKillsByRefIndex.entrySet()) {
            KillEntry killEntry = entry.getValue();
            if (killEntry == null || killEntry.expiresAt() <= now) {
                recentKillsByRefIndex.remove(entry.getKey());
            }
        }
        for (var entry : recentKillsByMob.entrySet()) {
            KillEntry killEntry = entry.getValue();
            if (killEntry == null || killEntry.expiresAt() <= now) {
                recentKillsByMob.remove(entry.getKey());
            }
        }
        pruneExpiredDeathMarkers(now);
    }

    private void pruneExpiredDeathMarkers(long now) {
        for (var entry : recentMobDeathMarkers.entrySet()) {
            MobDeathMarker marker = entry.getValue();
            if (marker == null || marker.expiresAt() <= now || marker.remainingTags().get() <= 0) {
                recentMobDeathMarkers.remove(entry.getKey());
            }
        }
        for (var entry : spawnedMobLootAllowances.entrySet()) {
            SpawnedMobLootAllowance allowance = entry.getValue();
            if (allowance == null || allowance.expiresAt <= now || allowance.remainingByItem.isEmpty()) {
                spawnedMobLootAllowances.remove(entry.getKey());
            }
        }
        for (var entry : deferredMobPickups.entrySet()) {
            UUID playerUuid = entry.getKey();
            ConcurrentMap<String, DeferredMobPickup> pendingByItem = entry.getValue();
            if (pendingByItem == null || pendingByItem.isEmpty()) {
                deferredMobPickups.remove(playerUuid);
                continue;
            }
            for (var itemEntry : pendingByItem.entrySet()) {
                DeferredMobPickup pending = itemEntry.getValue();
                if (pending == null || pending.expiresAt <= now) {
                    pendingByItem.remove(itemEntry.getKey());
                }
            }
            if (pendingByItem.isEmpty()) {
                deferredMobPickups.remove(playerUuid);
            }
        }
    }

    private UUID resolveEntityUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return null;
        }
        UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer, ref,
                UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            uuidComponent = EntityRefUtil.tryGetComponent(store, ref, UUIDComponent.getComponentType());
        }
        return uuidComponent == null ? null : uuidComponent.getUuid();
    }

    private Vector3d resolveEntityPosition(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return null;
        }
        TransformComponent transform = EntityRefUtil.tryGetComponent(commandBuffer, ref,
                TransformComponent.getComponentType());
        if (transform == null) {
            transform = EntityRefUtil.tryGetComponent(store, ref, TransformComponent.getComponentType());
        }
        return transform == null ? null : transform.getPosition();
    }

    private boolean tryConsumeMarkerBudget(MobDeathMarker marker) {
        AtomicInteger remainingTags = marker.remainingTags();
        while (true) {
            int current = remainingTags.get();
            if (current <= 0) {
                return false;
            }
            if (remainingTags.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private String formatDropName(@Nonnull ItemStack stack) {
        String itemId = stack.getItemId();
        return itemId == null || itemId.isBlank() ? "loot" : itemId;
    }

    private enum DropType {
        ORE,
        MOB
    }

    private static final class MobLootClaim {
        private final ConcurrentMap<String, Integer> remainingByItem;
        private final long expiresAt;

        private MobLootClaim(@Nonnull ConcurrentMap<String, Integer> remainingByItem, long expiresAt) {
            this.remainingByItem = remainingByItem;
            this.expiresAt = expiresAt;
        }

        private int consume(@Nonnull String itemId, int requestedAmount) {
            if (requestedAmount <= 0) {
                return 0;
            }
            Integer available = remainingByItem.get(itemId);
            if (available == null || available <= 0) {
                return requestedAmount;
            }
            int consumed = Math.min(available, requestedAmount);
            int remainingItem = available - consumed;
            if (remainingItem > 0) {
                remainingByItem.put(itemId, remainingItem);
            } else {
                remainingByItem.remove(itemId);
            }
            return requestedAmount - consumed;
        }

        private boolean isEmpty() {
            return remainingByItem.isEmpty();
        }
    }

    private static final class SpawnedMobLootAllowance {
        private final ConcurrentMap<String, Integer> remainingByItem;
        private volatile long expiresAt;

        private SpawnedMobLootAllowance(@Nonnull ConcurrentMap<String, Integer> remainingByItem, long expiresAt) {
            this.remainingByItem = remainingByItem;
            this.expiresAt = expiresAt;
        }
    }

    private static final class DeferredMobPickup {
        private final ItemStack templateStack;
        private final int quantity;
        private final long expiresAt;

        private DeferredMobPickup(@Nonnull ItemStack templateStack, int quantity, long expiresAt) {
            this.templateStack = templateStack;
            this.quantity = Math.max(1, quantity);
            this.expiresAt = expiresAt;
        }

        private DeferredMobPickup merge(@Nonnull DeferredMobPickup other) {
            int mergedQty = Math.max(1, this.quantity) + Math.max(1, other.quantity);
            long mergedExpiry = Math.max(this.expiresAt, other.expiresAt);
            return new DeferredMobPickup(this.templateStack, mergedQty, mergedExpiry);
        }
    }

    /**
     * Returns {@code true} if the item carries the mob-kill metadata tag for the
     * given player. Only items stamped by {@link MobDropTaggingSystem} at drop
     * time will pass this check.
     */
    private boolean hasMobKillTag(@Nonnull UUID playerUuid, @Nonnull ItemStack stack) {
        String killerTag = stack.getFromMetadataOrNull(MOB_KILL_TAG_KEY, KILL_TAG_CODEC);
        if (killerTag == null) {
            return false;
        }
        return playerUuid.toString().equals(killerTag);
    }

    /** Binds a killed mob to its killer for use by {@link MobDropTaggingSystem}. */
    private record KillEntry(UUID killerUuid, long expiresAt) {
    }

    /**
     * Spatial+time marker for associating spawned loot items to a recent mob kill.
     */
    private record MobDeathMarker(UUID killerUuid, long expiresAt, double x, double y, double z,
            AtomicInteger remainingTags) {
    }

}
