package com.airijko.endlessleveling.commands.party;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public class PartyLeaveCommand extends AbstractPartySubCommand {

    public PartyLeaveCommand() {
        super("leave", "Leave your current party");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        UUID senderUuid = senderRef.getUuid();
        if (partyManager.leaveParty(senderUuid)) {
            senderRef.sendMessage(Message.raw("You left your party.").color("#ff9900"));
        } else {
            senderRef.sendMessage(Message.raw("You are not currently in a party.").color("#ff0000"));
        }
    }
}
