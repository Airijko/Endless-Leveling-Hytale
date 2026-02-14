package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party system that persists to disk using a JSON snapshot. Parties are
 * identified by their leader's UUID. XP gains are split evenly between
 * all online members of a party.
 */
public class PartyManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Pattern PARTY_PATTERN = Pattern.compile(
            "\\{\\s*\"leader\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"members\"\\s*:\\s*\\[(.*?)\\]\\s*\\}", Pattern.DOTALL);
    private static final Pattern MEMBER_PATTERN = Pattern.compile("\"([^\"]+)\"");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final File partyDataFile;

    private final Map<UUID, Party> membershipIndex = new HashMap<>();
    private final Map<UUID, UUID> pendingInvites = new HashMap<>();

    public PartyManager(@Nonnull PlayerDataManager playerDataManager,
            @Nonnull LevelingManager levelingManager,
            @Nonnull PluginFilesManager filesManager) {
        this.playerDataManager = playerDataManager;
        this.levelingManager = levelingManager;
        this.partyDataFile = filesManager.getPartyDataFile();
        loadPersistedParties();
        LOGGER.atInfo().log("PartyManager initialized.");
    }

    /**
     * Create a new party with the given leader.
     *
     * @return true if a new party was created, false if the player is already in a
     *         party.
     */
    public synchronized boolean createParty(@Nonnull UUID leaderUuid) {
        if (membershipIndex.containsKey(leaderUuid)) {
            LOGGER.atWarning().log("Player %s is already in a party; cannot create a new one.", leaderUuid);
            return false;
        }

        Party party = new Party(leaderUuid);
        party.members.add(leaderUuid);
        membershipIndex.put(leaderUuid, party);
        persistParties();

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
        persistParties();
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
            notifyLeaderCannotLeave(memberUuid);
            LOGGER.atWarning().log("Leader %s attempted to leave their party; instructing to disband instead.",
                    memberUuid);
            return false;
        }

        internalLeave(memberUuid, party);
        persistParties();
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
        persistParties();
        LOGGER.atInfo().log("Party led by %s disbanded (%d members).", leaderUuid, party.members.size());
        return true;
    }

    /**
     * Handle a player disconnecting from the server. Parties remain intact,
     * but we remove any pending invites involving the player and snapshot
     * the current party state.
     */
    public synchronized void handlePlayerDisconnect(@Nonnull UUID uuid) {
        pendingInvites.remove(uuid);
        pendingInvites.entrySet().removeIf(entry -> uuid.equals(entry.getValue()));
        persistParties();
        LOGGER.atFine().log("Handled disconnect bookkeeping for %s", uuid);
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

        List<UUID> activeMembers = new ArrayList<>();
        for (UUID member : party.members) {
            PlayerData data = playerDataManager.get(member);
            if (data != null) {
                activeMembers.add(member);
            }
        }

        if (activeMembers.isEmpty()) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        double share = totalXp / (double) activeMembers.size();
        for (UUID member : activeMembers) {
            levelingManager.addXp(member, share);
        }

        LOGGER.atInfo().log("Distributed %f XP from %s among %d active party members (%.3f each).",
                totalXp, sourcePlayerUuid, activeMembers.size(), share);
    }

    /**
     * XP sharing with a range limit. Only party members in the same world and
     * within maxDistance of the source player's position receive a share. If no
     * members are in range, grants full XP to the source.
     */
    public synchronized void handleXpGainInRange(@Nonnull UUID sourcePlayerUuid, double totalXp,
            double maxDistance) {
        if (totalXp <= 0.0) {
            return;
        }

        PlayerRef sourceRef = Universe.get().getPlayer(sourcePlayerUuid);
        if (sourceRef == null || !sourceRef.isValid()) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        Ref<EntityStore> sourceEntity = sourceRef.getReference();
        Store<EntityStore> sourceStore = sourceEntity != null ? sourceEntity.getStore() : null;
        Vector3d sourcePos = resolvePosition(sourceEntity, sourceStore);

        Party party = membershipIndex.get(sourcePlayerUuid);
        if (party == null || party.members.isEmpty() || sourcePos == null) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        double radius = Math.max(0.0D, maxDistance);
        double radiusSq = radius <= 0.0D ? 0.0D : radius * radius;

        List<UUID> nearbyMembers = new ArrayList<>();
        for (UUID member : party.members) {
            PlayerRef memberRef = Universe.get().getPlayer(member);
            if (memberRef == null || !memberRef.isValid()) {
                continue;
            }
            Ref<EntityStore> memberEntity = memberRef.getReference();
            if (memberEntity == null) {
                continue;
            }
            if (sourceStore != null && memberEntity.getStore() != sourceStore) {
                continue;
            }
            Vector3d pos = resolvePosition(memberEntity, memberEntity.getStore());
            if (pos == null) {
                continue;
            }
            if (radiusSq > 0.0D) {
                double dx = pos.getX() - sourcePos.getX();
                double dy = pos.getY() - sourcePos.getY();
                double dz = pos.getZ() - sourcePos.getZ();
                double distSq = (dx * dx) + (dy * dy) + (dz * dz);
                if (distSq > radiusSq) {
                    continue;
                }
            }
            nearbyMembers.add(member);
        }

        if (nearbyMembers.isEmpty()) {
            levelingManager.addXp(sourcePlayerUuid, totalXp);
            return;
        }

        double share = totalXp / (double) nearbyMembers.size();
        for (UUID member : nearbyMembers) {
            levelingManager.addXp(member, share);
        }

        LOGGER.atInfo().log(
                "Distributed %f XP in-range from %s among %d party members (%.3f each, radius=%.1f).",
                totalXp, sourcePlayerUuid, nearbyMembers.size(), share, maxDistance);
    }

    private Vector3d resolvePosition(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return null;
        }
        try {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            return transform != null ? transform.getPosition() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void internalLeave(@Nonnull UUID memberUuid, @Nonnull Party party) {
        party.members.remove(memberUuid);
        membershipIndex.remove(memberUuid);
    }

    private void notifyLeaderCannotLeave(UUID leaderUuid) {
        PlayerRef playerRef = Universe.get().getPlayer(leaderUuid);
        if (playerRef == null) {
            LOGGER.atFine().log("Leader %s is offline; cannot send leave warning message.", leaderUuid);
            return;
        }

        Message warning = Message
                .raw("You cannot leave the party while you are the leader. Use /party disband or disband button instead.")
                .color("#ff6666");
        playerRef.sendMessage(warning);
    }

    public synchronized void saveAllParties() {
        persistParties();
    }

    private void loadPersistedParties() {
        if (partyDataFile == null || !partyDataFile.exists()) {
            LOGGER.atFine().log("No existing party data file found; starting fresh.");
            return;
        }

        try {
            String content = Files.readString(partyDataFile.toPath(), StandardCharsets.UTF_8);
            if (content == null || content.isBlank()) {
                LOGGER.atFine().log("Party data file %s is empty.", partyDataFile.getName());
                return;
            }

            List<PersistedParty> snapshots = parsePartiesJson(content);
            if (snapshots.isEmpty()) {
                LOGGER.atFine().log("Party data file %s contained no parties.", partyDataFile.getName());
                return;
            }

            membershipIndex.clear();
            for (PersistedParty snapshot : snapshots) {
                Party party = new Party(snapshot.leader());
                party.members.addAll(snapshot.members());
                for (UUID member : party.members) {
                    membershipIndex.put(member, party);
                }
            }
            LOGGER.atInfo().log("Loaded %d parties from %s", snapshots.size(), partyDataFile.getName());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to load parties from %s: %s", partyDataFile.getName(), e.getMessage());
        }
    }

    private void persistParties() {
        if (partyDataFile == null) {
            return;
        }

        List<Party> parties = collectActiveParties();
        String json = serializeParties(parties);
        try {
            Files.writeString(partyDataFile.toPath(), json, StandardCharsets.UTF_8);
            LOGGER.atFine().log("Persisted %d parties to %s", parties.size(), partyDataFile.getName());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to persist parties to %s: %s", partyDataFile.getName(), e.getMessage());
        }
    }

    private List<Party> collectActiveParties() {
        Map<UUID, Party> partiesByLeader = new HashMap<>();
        for (Map.Entry<UUID, Party> entry : membershipIndex.entrySet()) {
            Party party = entry.getValue();
            if (party != null && party.leader.equals(entry.getKey())) {
                partiesByLeader.put(party.leader, party);
            }
        }

        List<UUID> sortedLeaders = new ArrayList<>(partiesByLeader.keySet());
        sortedLeaders.sort(UUID::compareTo);

        List<Party> sortedParties = new ArrayList<>(sortedLeaders.size());
        for (UUID leader : sortedLeaders) {
            sortedParties.add(partiesByLeader.get(leader));
        }
        return sortedParties;
    }

    private String serializeParties(List<Party> parties) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"parties\": [");
        if (!parties.isEmpty()) {
            builder.append("\n");
        }

        for (int i = 0; i < parties.size(); i++) {
            Party party = parties.get(i);
            builder.append("    {\n");
            builder.append("      \"leader\": \"").append(party.leader).append("\",\n");
            builder.append("      \"members\": [");
            List<UUID> members = new ArrayList<>(party.members);
            members.sort(UUID::compareTo);
            for (int j = 0; j < members.size(); j++) {
                builder.append("\"").append(members.get(j)).append("\"");
                if (j < members.size() - 1) {
                    builder.append(", ");
                }
            }
            builder.append("]\n");
            builder.append("    }");
            if (i < parties.size() - 1) {
                builder.append(",\n");
            } else {
                builder.append("\n");
            }
        }

        if (parties.isEmpty()) {
            builder.append("]\n");
        } else {
            builder.append("  ]\n");
        }
        builder.append("}\n");
        return builder.toString();
    }

    private List<PersistedParty> parsePartiesJson(String json) {
        List<PersistedParty> result = new ArrayList<>();
        Matcher matcher = PARTY_PATTERN.matcher(json);
        while (matcher.find()) {
            try {
                UUID leader = UUID.fromString(matcher.group(1));
                String membersBlock = matcher.group(2);
                Set<UUID> members = new LinkedHashSet<>();
                Matcher memberMatcher = MEMBER_PATTERN.matcher(membersBlock);
                while (memberMatcher.find()) {
                    String raw = memberMatcher.group(1);
                    try {
                        members.add(UUID.fromString(raw));
                    } catch (IllegalArgumentException invalidMember) {
                        LOGGER.atWarning().log("Ignoring invalid party member UUID %s in %s", raw,
                                partyDataFile.getName());
                    }
                }
                members.add(leader);
                result.add(new PersistedParty(leader, members));
            } catch (IllegalArgumentException invalidLeader) {
                LOGGER.atWarning().log("Ignoring invalid party entry in %s due to UUID parse error.",
                        partyDataFile.getName());
            }
        }
        return result;
    }

    private record PersistedParty(UUID leader, Set<UUID> members) {
    }

    private static final class Party {
        private final UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();

        private Party(UUID leader) {
            this.leader = leader;
        }
    }
}
