package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.util.ChatMessageTemplate;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scans player data for augment slot mismatches between what the current
 * leveling config grants and what each player actually has (selected +
 * pending). Mismatches can occur when {@code leveling.yml} milestone rules
 * are changed after players have already earned (or exceeded) their
 * allocations.
 *
 * <p>
 * Unsynced players are reported to all online operators with the exact
 * commands needed to fix them:
 * <ul>
 * <li>{@code /el augments reset <name>} — reset one player</li>
 * <li>{@code /el augments resetallplayers} — bulk-fix everyone</li>
 * </ul>
 *
 * <p>
 * A full audit is run once on plugin startup (results go to the server
 * console). Subsequent per-player checks fire on each login.
 */
public class AugmentSyncValidator {

    /**
     * Number of offer-ID entries stored per pending bundle.
     * Must match {@code AugmentUnlockManager.DEFAULT_OFFER_COUNT}.
     */
    private static final int OFFER_BUNDLE_SIZE = 3;

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    public enum SyncStatus {
        /** Slot count matches current config. */
        IN_SYNC,
        /** Player has fewer slots than the current config grants. */
        TOO_FEW,
        /** Player has more slots than the current config grants. */
        TOO_MANY
    }

    /** Snapshot describing a single unsynced player. */
    public static final class UnsyncedEntry {
        public final String name;
        public final UUID uuid;
        public final SyncStatus status;
        /** Milestone count the current config grants for this player's level. */
        public final int expected;
        /** Milestone count actually present in the player's active profile. */
        public final int actual;

        public UnsyncedEntry(String name, UUID uuid, SyncStatus status, int expected, int actual) {
            this.name = name;
            this.uuid = uuid;
            this.status = status;
            this.expected = expected;
            this.actual = actual;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public AugmentSyncValidator(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull AugmentUnlockManager augmentUnlockManager) {
        this.playerDataManager = playerDataManager;
        this.augmentUnlockManager = augmentUnlockManager;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the sync status of {@code playerData}'s active profile against
     * the current config rules.
     *
     * <p>
     * <em>Note:</em> if called after {@link AugmentUnlockManager#ensureUnlocks}
     * has already run for this player, {@link SyncStatus#TOO_FEW} will only appear
     * when the augment offer pool was exhausted and missing slots could not be
     * automatically filled.
     */
    @Nonnull
    public SyncStatus checkPlayer(@Nonnull PlayerData playerData) {
        int eligible = augmentUnlockManager.getEligibleMilestoneCount(playerData, playerData.getLevel());
        int actual = countActualMilestones(playerData);
        if (actual < eligible)
            return SyncStatus.TOO_FEW;
        if (actual > eligible)
            return SyncStatus.TOO_MANY;
        return SyncStatus.IN_SYNC;
    }

    /**
     * Loads every player from disk (offline and online) via
     * {@link PlayerDataManager#getAllPlayersSortedByLevel()} and returns
     * {@link UnsyncedEntry} records for those whose active-profile milestone
     * count does not match the current config.
     */
    @Nonnull
    public List<UnsyncedEntry> checkAllPlayers() {
        List<PlayerData> all = playerDataManager.getAllPlayersSortedByLevel();
        List<UnsyncedEntry> unsynced = new ArrayList<>();
        for (PlayerData data : all) {
            SyncStatus status = checkPlayer(data);
            if (status == SyncStatus.IN_SYNC)
                continue;
            int eligible = augmentUnlockManager.getEligibleMilestoneCount(data, data.getLevel());
            int actual = countActualMilestones(data);
            unsynced.add(new UnsyncedEntry(data.getPlayerName(), data.getUuid(), status, eligible, actual));
        }
        return unsynced;
    }

    /**
     * Performs a full audit over all player data files (offline + online) and:
     * <ol>
     * <li>Logs any mismatches to the server console.</li>
     * <li>Sends a detailed report to every currently online operator.</li>
     * </ol>
     * Safe to call at plugin startup — when no players are connected yet,
     * findings are still captured in the server log.
     */
    public void auditAndNotify() {
        LOGGER.atInfo().log("AugmentSyncValidator: starting full player audit…");
        List<UnsyncedEntry> unsynced = checkAllPlayers();

        if (unsynced.isEmpty()) {
            LOGGER.atInfo().log("AugmentSyncValidator: all players are in sync with current augment config.");
            return;
        }

        LOGGER.atWarning().log("AugmentSyncValidator: %d player(s) have mismatched augment slot counts:",
                unsynced.size());
        for (UnsyncedEntry e : unsynced) {
            LOGGER.atWarning().log("  [%s] %s — expected %d slot(s), actual %d slot(s)",
                    e.status, e.name, e.expected, e.actual);
        }
        LOGGER.atWarning().log(
                "AugmentSyncValidator: to fix all players run:  /el augments resetallplayers");

        // Notify any operators who are already online (unlikely at startup, but
        // possible
        // if the plugin is reloaded while the server is running).
        for (PlayerRef op : Universe.get().getPlayers()) {
            if (!OperatorHelper.isOperator(op))
                continue;
            broadcastUnsyncedList(op, unsynced);
        }
    }

    /**
     * Checks a single player on login and immediately notifies all online
     * operators if that player's slot count is out of sync with the current
     * config.
     *
     * <p>
     * Call this <em>after</em> {@link AugmentUnlockManager#ensureUnlocks} so
     * that routine TOO_FEW cases (missing offers that ensureUnlocks just filled)
     * do not produce false-positive alerts.
     */
    public void auditOnLogin(@Nonnull PlayerData playerData) {
        SyncStatus status = checkPlayer(playerData);
        if (status == SyncStatus.IN_SYNC)
            return;

        int eligible = augmentUnlockManager.getEligibleMilestoneCount(playerData, playerData.getLevel());
        int actual = countActualMilestones(playerData);

        LOGGER.atWarning().log(
                "AugmentSyncValidator: %s joined with mismatched augment slots [%s: expected=%d actual=%d]",
                playerData.getPlayerName(), status, eligible, actual);

        UnsyncedEntry entry = new UnsyncedEntry(
                playerData.getPlayerName(), playerData.getUuid(), status, eligible, actual);

        for (PlayerRef op : Universe.get().getPlayers()) {
            if (!OperatorHelper.isOperator(op))
                continue;
            broadcastUnsyncedList(op, List.of(entry));
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the total number of milestone slots actually present in the
     * player's active profile: selected augments + pending offer bundles.
     */
    private int countActualMilestones(@Nonnull PlayerData playerData) {
        int selected = playerData.getSelectedAugmentsSnapshot().size();
        int pending = 0;
        for (PassiveTier tier : PassiveTier.values()) {
            List<String> tierOffers = playerData.getAugmentOffersForTier(tier.name());
            // Each bundle stores OFFER_BUNDLE_SIZE candidate IDs as a flat list.
            pending += tierOffers.size() / OFFER_BUNDLE_SIZE;
        }
        return selected + pending;
    }

    /** Sends a formatted sync-issue report to a single operator. */
    private void broadcastUnsyncedList(@Nonnull PlayerRef op, @Nonnull List<UnsyncedEntry> unsynced) {
        PlayerChatNotifier.send(op, ChatMessageTemplate.AUGMENT_SYNC_SUMMARY, unsynced.size());
        for (UnsyncedEntry e : unsynced) {
            String direction = e.status == SyncStatus.TOO_FEW ? "too few" : "too many";
            PlayerChatNotifier.send(op, ChatMessageTemplate.AUGMENT_SYNC_ENTRY,
                    e.name, direction, e.actual, e.expected);
        }
        PlayerChatNotifier.send(op, ChatMessageTemplate.AUGMENT_SYNC_FIX_ALL);
    }
}
