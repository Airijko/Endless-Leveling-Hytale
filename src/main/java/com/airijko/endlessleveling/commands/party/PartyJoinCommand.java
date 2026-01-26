package com.airijko.endlessleveling.commands.party;

import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class PartyJoinCommand extends AbstractPartySubCommand {

    public PartyJoinCommand() {
        super("join", "Join a party you were invited to");
        this.setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        String targetName = extractTargetName(context);
        UUID leaderUuid;

        if (targetName != null && !targetName.isEmpty()) {
            PlayerData leaderData = playerDataManager.getByName(targetName);
            if (leaderData == null) {
                PlayerRef online = Universe.get().getPlayer(targetName, NameMatching.DEFAULT);
                if (online != null) {
                    leaderUuid = online.getUuid();
                } else {
                    senderRef.sendMessage(Message.raw("Unknown player: " + targetName).color("#ff0000"));
                    return;
                }
            } else {
                leaderUuid = leaderData.getUuid();
            }
        } else {
            leaderUuid = partyManager.getPendingInviteLeader(senderRef.getUuid());
            if (leaderUuid == null) {
                senderRef.sendMessage(Message.raw("You do not have any pending party invites. Ask a leader to invite you first, or run /party join <leaderName> to target a specific party.").color("#ff0000"));
                return;
            }
        }

        if (!partyManager.isInParty(leaderUuid)) {
            senderRef.sendMessage(Message.raw("That player is not in a party.").color("#ff0000"));
            return;
        }

        if (!partyManager.hasInvite(leaderUuid, senderRef.getUuid())) {
            senderRef.sendMessage(Message.raw("You must be invited to join this party.").color("#ff0000"));
            return;
        }

        if (partyManager.joinParty(leaderUuid, senderRef.getUuid())) {
            partyManager.consumeInvite(senderRef.getUuid());
            senderRef.sendMessage(Message.raw("You joined " + resolveName(leaderUuid) + "'s party.").color("#00ff00"));

            PlayerRef leaderRef = Universe.get().getPlayer(leaderUuid);
            if (leaderRef != null) {
                leaderRef.sendMessage(Message.raw(resolveName(senderRef.getUuid()) + " joined your party.").color("#4fd7f7"));
            }
        } else {
            senderRef.sendMessage(Message.raw("Could not join that party.").color("#ff0000"));
        }
    }

    private String extractTargetName(CommandContext context) {
        String afterRoot = CommandUtil.stripCommandName(context.getInputString()).trim();
        if (afterRoot.isEmpty()) {
            return null;
        }

        String lower = afterRoot.toLowerCase();
        if (!lower.startsWith("join")) {
            return null;
        }

        if (afterRoot.length() == 4) { // exactly "join"
            return null;
        }

        String remainder = afterRoot.substring(4).trim();
        if (remainder.isEmpty()) {
            return null;
        }

        int spaceIdx = remainder.indexOf(' ');
        return (spaceIdx == -1) ? remainder : remainder.substring(0, spaceIdx);
    }
}
