package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Simple in-memory party system.
 *
 * Parties are ephemeral (not persisted across restarts) and are
 * identified by their leader's UUID. XP gains are split evenly between
 * all current members of a party.
 */
public class PartyManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;


    private final Map<UUID, Party> membershipIndex = new HashMap<>();
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public PartyManager(@Nonnull PlayerDataManager playerDataManager,
                        @Nonnull LevelingManager levelingManager) {
        this.playerDataManager = playerDataManager;
        this.levelingManager = levelingManager;
        LOGGER.atInfo().log("PartyManager initialized.");
    }

    /**
     * Create a new party with the given leader.
     *
     * @return true if a new party was created, false if the player is already in a party.
     */
    public synchronized boolean createParty(@Nonnull UUID leaderUuid) {
        if (membershipIndex.containsKey(leaderUuid)) {
            LOGGER.atWarning().log("Player %s is already in a party; cannot create a new one.", leaderUuid);
            return false;
        }

        Party party = new Party(leaderUuid);
        party.members.add(leaderUuid);
        membershipIndex.put(leaderUuid, party);

        LOGGER.atInfo().log("Created new party with leader %s", leaderUuid);
        return true;
    }

    /**
     * Join the party led by leaderUuid.
     *
     * @return true if joined successfully, false if leader has no party.
     */
    public synchronized boolean joinParty(@Nonnull UUID leaderUuid, @Nonnull UUID memberUuid) {
        Party leaderParty = membershipIndex.get(leaderUuid);
        if (leaderParty == null || !leaderParty.leader.equals(leaderUuid)) {
            LOGGER.atWarning().log("joinParty: leader %s has no party.", leaderUuid);
            return false;
        }

        // If already in a different party, leave it first.
        Party current = membershipIndex.get(memberUuid);
        if (current != null && current != leaderParty) {
            internalLeave(memberUuid, current);
        }

        leaderParty.members.add(memberUuid);
        membershipIndex.put(memberUuid, leaderParty);
        LOGGER.atInfo().log("Player %s joined party led by %s", memberUuid, leaderUuid);
        return true;
    }

    /**
     * Leave the current party.
     */
    public synchronized boolean leaveParty(@Nonnull UUID memberUuid) {
        Party party = membershipIndex.get(memberUuid);
        if (party == null) {
            LOGGER.atWarning().log("leaveParty: player %s is not in a party.", memberUuid);
            return false;
        }

        if (party.leader.equals(memberUuid)) {
            // Leader leaving disbands the party.
            disbandParty(memberUuid);
            return true;
        }

        internalLeave(memberUuid, party);
        LOGGER.atInfo().log("Player %s left party led by %s", memberUuid, party.leader);
        return true;
    }

    /**
     * Disband the party if the given player is the leader.
     */
    public synchronized boolean disbandParty(@Nonnull UUID leaderUuid) {
        Party party = membershipIndex.get(leaderUuid);
        if (party == null || !party.leader.equals(leaderUuid)) {
            LOGGER.atWarning().log("disbandParty: player %s is not a party leader.", leaderUuid);
            return false;
        }

        for (UUID member : party.members) {
            membershipIndex.remove(member);
        }
        // Remove any pending invites issued by this leader.
        pendingInvites.entrySet().removeIf(entry -> leaderUuid.equals(entry.getValue()));
        LOGGER.atInfo().log("Party led by %s disbanded (%d members).", leaderUuid, party.members.size());
        return true;
    }

    /**
     * Remove a player from any party they are in (e.g. on disconnect).
     */
    public synchronized void removePlayer(@Nonnull UUID uuid) {
        Party party = membershipIndex.get(uuid);
        if (party == null) {
            return;
        }
        if (party.leader.equals(uuid)) {
            disbandParty(uuid);
        } else {
            internalLeave(uuid, party);
            LOGGER.atInfo().log("Player %s removed from party led by %s (disconnect).", uuid, party.leader);
        }

        // Clear any pending invite for or from this player.
        pendingInvites.remove(uuid);
        pendingInvites.entrySet().removeIf(entry -> uuid.equals(entry.getValue()));
    }

    /**
     * Get the leader UUID for the party that this player is in, or null.
     */
    public synchronized UUID getPartyLeader(@Nonnull UUID memberUuid) {
        Party party = membershipIndex.get(memberUuid);
        return party != null ? party.leader : null;
    }

    /**
     * Get an immutable snapshot of member UUIDs for the party this player is in.
     */
    public synchronized Set<UUID> getPartyMembers(@Nonnull UUID memberUuid) {
        Party party = membershipIndex.get(memberUuid);
        if (party == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(party.members));
    }

    /**
     * True if the player is in any party.
     */
    public synchronized boolean isInParty(@Nonnull UUID memberUuid) {
        return membershipIndex.containsKey(memberUuid);
    }

    /**
     * Issue an invitation from the leader to the target player.
     */
    public synchronized boolean invitePlayer(@Nonnull UUID leaderUuid, @Nonnull UUID targetUuid) {
        Party party = membershipIndex.get(leaderUuid);
        if (party == null || !party.leader.equals(leaderUuid)) {
            LOGGER.atWarning().log("invitePlayer: %s is not a party leader.", leaderUuid);
            return false;
        }

        if (leaderUuid.equals(targetUuid)) {
            return false;
        }

        // Target already in any party: do not allow inviting.
        if (membershipIndex.containsKey(targetUuid)) {
            LOGGER.atWarning().log("invitePlayer: target %s is already in a party.", targetUuid);
            return false;
        }

        pendingInvites.put(targetUuid, leaderUuid);
        LOGGER.atInfo().log("Player %s invited %s to their party.", leaderUuid, targetUuid);
        return true;
    }

    /**
     * Check if the target has a valid invite from the given leader.
     */
    public synchronized boolean hasInvite(@Nonnull UUID leaderUuid, @Nonnull UUID targetUuid) {
        return leaderUuid.equals(pendingInvites.get(targetUuid));
    }

    /**
     * Get the leader UUID that invited the target player, if any.
     */
    public synchronized UUID getPendingInviteLeader(@Nonnull UUID targetUuid) {
        return pendingInvites.get(targetUuid);
    }

    /**
     * Consume (remove) an invite after it is accepted.
     */
    public synchronized void consumeInvite(@Nonnull UUID targetUuid) {
        pendingInvites.remove(targetUuid);
    }

    /**
     * Handle an XP gain event and split XP between party members if present.
     */
    public synchronized void handleXpGain(@Nonnull UUID sourcePlayerUuid, double totalXp) {
        if (totalXp <= 0.0) {
            return;
        }

        Party party = membershipIndex.get(sourcePlayerUuid);
        if (party == null || party.members.isEmpty()) {
            // No party: grant full XP to the source player.
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        int size = party.members.size();
        if (size <= 0) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        double share = totalXp / (double) size;
        for (UUID member : party.members) {
            // Only grant to players with loaded PlayerData to match the
            // rest of the plugin's behavior.
            PlayerData data = playerDataManager.get(member);
            if (data != null) {
                levelingManager.addXp(member, share);
            }
        }

        LOGGER.atInfo().log("Distributed %f XP from %s among %d party members (%.3f each).",
                totalXp, sourcePlayerUuid, size, share);
    }

    private void internalLeave(@Nonnull UUID memberUuid, @Nonnull Party party) {
        party.members.remove(memberUuid);
        membershipIndex.remove(memberUuid);
    }

    private static final class Party {
        private final UUID leader;
        private final Set<UUID> members = new HashSet<>();

        private Party(UUID leader) {
            this.leader = leader;
        }
    }
}
