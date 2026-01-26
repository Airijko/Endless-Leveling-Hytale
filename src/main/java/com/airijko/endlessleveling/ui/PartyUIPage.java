package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endless_Leveling_Hytale;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

public class PartyUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PartyManager partyManager;
    private final PlayerDataManager playerDataManager;

    public PartyUIPage(@Nonnull PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        this.partyManager = Endless_Leveling_Hytale.getInstance().getPartyManager();
        this.playerDataManager = Endless_Leveling_Hytale.getInstance().getPlayerDataManager();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Party/PartyPage.ui");
        NavUIHelper.bindNavEvents(events);

        // Party-specific buttons
        events.addEventBinding(Activating, "#CreatePartyButton", of("Action", "party:create"), false);
        events.addEventBinding(Activating, "#LeavePartyButton", of("Action", "party:leave"), false);
        events.addEventBinding(Activating, "#DisbandPartyButton", of("Action", "party:disband"), false);

        updatePartyStatus(ui);

        // Build initial member list
        buildMemberList(ui);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isEmpty()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }

            if (data.action.startsWith("party:")) {
                handlePartyAction(data.action);
                rebuild();
            }
        }
    }

    private void handlePartyAction(@Nonnull String action) {
        String payload = action.substring("party:".length());
        String sub = payload.toLowerCase();
        PlayerRef ref = playerRef;
        switch (sub) {
            case "create" -> {
                if (partyManager.isInParty(ref.getUuid())) {
                    ref.sendMessage(Message.raw("You are already in a party.").color("#ff9900"));
                } else if (partyManager.createParty(ref.getUuid())) {
                    ref.sendMessage(Message.raw("You created a new party.").color("#00ff00"));
                } else {
                    ref.sendMessage(Message.raw("Could not create party.").color("#ff0000"));
                }
            }
            case "leave" -> {
                if (partyManager.leaveParty(ref.getUuid())) {
                    ref.sendMessage(Message.raw("You left your party.").color("#ff9900"));
                } else {
                    ref.sendMessage(Message.raw("You are not currently in a party.").color("#ff0000"));
                }
            }
            case "disband" -> {
                if (partyManager.disbandParty(ref.getUuid())) {
                    ref.sendMessage(Message.raw("Your party has been disbanded.").color("#ff9900"));
                } else {
                    ref.sendMessage(Message.raw("You are not the leader of a party.").color("#ff0000"));
                }
            }
            default -> {
                if (sub.startsWith("invite:")) {
                    String uuidPart = payload.substring("invite:".length());
                    handleInviteAction(uuidPart);
                } else {
                    LOGGER.atWarning().log("Unknown party action: %s", action);
                }
            }
        }
    }

    private void handleInviteAction(@Nonnull String uuidPart) {
        PlayerRef sender = playerRef;
        java.util.UUID targetUuid;
        try {
            targetUuid = java.util.UUID.fromString(uuidPart);
        } catch (IllegalArgumentException ex) {
            LOGGER.atWarning().withCause(ex).log("Invalid UUID in party invite action: %s", uuidPart);
            sender.sendMessage(Message.raw("Could not send party invite.").color("#ff0000"));
            return;
        }

        if (targetUuid.equals(sender.getUuid())) {
            sender.sendMessage(Message.raw("You cannot invite yourself.").color("#ff0000"));
            return;
        }

        // Must be party leader to invite
        java.util.UUID leaderUuid = partyManager.getPartyLeader(sender.getUuid());
        if (leaderUuid == null || !leaderUuid.equals(sender.getUuid())) {
            sender.sendMessage(Message.raw("You must be the party leader to invite players.").color("#ff0000"));
            return;
        }

        if (partyManager.isInParty(targetUuid)) {
            sender.sendMessage(Message.raw("That player is already in a party.").color("#ff0000"));
            return;
        }

        if (partyManager.invitePlayer(sender.getUuid(), targetUuid)) {
            sender.sendMessage(Message.raw("You invited " + resolveName(targetUuid) + " to your party.").color("#00ff00"));

            PlayerRef targetRef = Universe.get().getPlayer(targetUuid);
            if (targetRef != null) {
                targetRef.sendMessage(Message.raw(resolveName(sender.getUuid()) + " invited you to their party. Use /skills party join "
                        + resolveName(sender.getUuid()) + " to accept.").color("#4fd7f7"));
            }
        } else {
            sender.sendMessage(Message.raw("Could not send party invite. Make sure you are the party leader.").color("#ff0000"));
        }
    }

    private void updatePartyStatus(@Nonnull UICommandBuilder ui) {
        PlayerRef ref = playerRef;

        String title = "Party";
        ui.set("#PartyTitleLabel.Text", title);

        var leaderUuid = partyManager.getPartyLeader(ref.getUuid());
        if (leaderUuid == null) {
            ui.set("#PartyStatus.Text", "You are not in a party.");
            return;
        }

        String leaderName = resolveName(leaderUuid);
        ui.set("#PartyStatus.Text", "Leader: " + leaderName);
    }

    private void buildMemberList(@Nonnull UICommandBuilder ui) {
        ui.clear("#MemberCards");

        java.util.UUID leaderUuid = partyManager.getPartyLeader(playerRef.getUuid());
        if (leaderUuid == null) {
            return;
        }

        java.util.Set<java.util.UUID> memberIds = partyManager.getPartyMembers(playerRef.getUuid());
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }

        int index = 0;
        for (java.util.UUID memberId : memberIds) {
            ui.append("#MemberCards", "Pages/Party/MembersRow.ui");
            String base = "#MemberCards[" + index + "]";

            String displayName;
            PlayerRef memberRef = Universe.get().getPlayer(memberId);
            if (memberRef != null && memberRef.getUsername() != null && !memberRef.getUsername().isEmpty()) {
                displayName = memberRef.getUsername();
            } else {
                displayName = resolveName(memberId);
            }

            ui.set(base + " #MemberPlayerName.Text", displayName);
            index++;
        }
    }

    private String resolveName(@Nonnull java.util.UUID uuid) {
        PlayerData data = playerDataManager.get(uuid);
        if (data != null && data.getPlayerName() != null) {
            return data.getPlayerName();
        }
        PlayerRef ref = Universe.get().getPlayer(uuid);
        if (ref != null && ref.getUsername() != null) {
            return ref.getUsername();
        }
        return uuid.toString();
    }
}

