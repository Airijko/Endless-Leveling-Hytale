package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.Transaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies the EndlessLeveling luck passive whenever players gain loot inside
 * their inventory.
 */
public class LuckDoubleDropSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final PassiveManager passiveManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final Set<UUID> suppressedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, Long> recentOreBreaks = new ConcurrentHashMap<>();

    public LuckDoubleDropSystem(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull PassiveManager passiveManager,
            @Nonnull ArchetypePassiveManager archetypePassiveManager) {
        this.playerDataManager = playerDataManager;
        this.passiveManager = passiveManager;
        this.archetypePassiveManager = archetypePassiveManager;
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
        if (suppressedPlayers.contains(uuid)) {
            return;
        }

        Transaction transaction = event.getTransaction();
        if (!(transaction instanceof ItemStackTransaction stackTransaction)) {
            return;
        }

        ActionType action = stackTransaction.getAction();
        if (action != ActionType.ADD) {
            return;
        }

        ItemStack sourceStack = resolveSourceStack(stackTransaction);
        if (sourceStack == null || ItemStack.isEmpty(sourceStack)) {
            return;
        }

        int addedAmount = resolveAddedAmount(stackTransaction);
        if (addedAmount <= 0) {
            return;
        }

        PlayerData playerData = playerDataManager.get(uuid);
        if (playerData == null) {
            return;
        }

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : ArchetypePassiveSnapshot.empty();

        double luckValue = snapshot.getValue(ArchetypePassiveType.LUCK);
        if (luckValue <= 0.0D) {
            return;
        }

        boolean oreDrop = isOreStack(sourceStack);
        boolean mobDrop = !oreDrop && passiveManager.hasMobDropStack(uuid);
        if (oreDrop) {
            Long t = recentOreBreaks.get(uuid);
            if (t == null || System.currentTimeMillis() - t > 3000L) {
                oreDrop = false;
            } else {
                recentOreBreaks.remove(uuid);
            }
        }
        if (!oreDrop && !mobDrop) {
            return;
        }

        if (!shouldTrigger(playerRef, luckValue, oreDrop, mobDrop)) {
            return;
        }

        if (mobDrop) {
            passiveManager.consumeMobDropStack(uuid);
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
            boolean oreDrop,
            boolean mobDrop) {
        double clamped = Math.min(100.0D, Math.max(0.0D, value));
        double roll = ThreadLocalRandom.current().nextDouble(100.0D);
        boolean success = roll < clamped;
        if (success) {
            LOGGER.atFiner().log(
                    "Luck double-drop triggered for %s (chance=%.2f%% roll=%.2f | ore=%s mob=%s)",
                    playerRef.getUsername(), clamped, roll, oreDrop, mobDrop);
        } else {
            LOGGER.atFiner().log(
                    "Luck double-drop failed for %s (chance=%.2f%% roll=%.2f | ore=%s mob=%s)",
                    playerRef.getUsername(), clamped, roll, oreDrop, mobDrop);
        }
        return success;
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

    private String formatDropName(@Nonnull ItemStack stack) {
        String itemId = stack.getItemId();
        return itemId == null || itemId.isBlank() ? "loot" : itemId;
    }
}
