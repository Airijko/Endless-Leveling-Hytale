package com.airijko.endlessleveling.commands.party;

import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class PartyInviteCommand extends AbstractPartySubCommand {

    private final RequiredArg<String> targetArg =
            this.withRequiredArg("player", "Player name to invite", ArgTypes.STRING);

    public PartyInviteCommand() {
        super("invite", "Invite a player to your party");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        String targetName = targetArg.get(context);
        if (targetName == null || targetName.isEmpty()) {
            senderRef.sendMessage(Message.raw("Usage: /party invite <player>").color("#ff0000"));
            return;
        }

        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            senderRef.sendMessage(Message.raw("Unknown player: " + targetName).color("#ff0000"));
            return;
        }

        UUID senderUuid = senderRef.getUuid();
        UUID targetUuid = targetData.getUuid();
        if (targetUuid.equals(senderUuid)) {
            senderRef.sendMessage(Message.raw("You cannot invite yourself.").color("#ff0000"));
            return;
        }

        if (partyManager.isInParty(targetUuid)) {
            senderRef.sendMessage(Message.raw("That player is already in a party.").color("#ff0000"));
            return;
        }

        if (partyManager.invitePlayer(senderUuid, targetUuid)) {
            senderRef.sendMessage(Message.raw("You invited " + resolveName(targetUuid) + " to your party.").color("#00ff00"));

            PlayerRef targetRef = Universe.get().getPlayer(targetUuid);
            if (targetRef != null) {
                targetRef.sendMessage(Message.raw(resolveName(senderUuid) + " invited you to their party. Use /party join to accept.").color("#4fd7f7"));
            }
        } else {
            senderRef.sendMessage(Message.raw("Could not send party invite. Make sure you are the party leader.").color("#ff0000"));
        }
    }
}
