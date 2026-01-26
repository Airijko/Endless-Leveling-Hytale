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

public class PartyCreateCommand extends AbstractPartySubCommand {

    public PartyCreateCommand() {
        super("create", "Create a new party");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        UUID senderUuid = senderRef.getUuid();
        if (partyManager.isInParty(senderUuid)) {
            senderRef.sendMessage(Message.raw("You are already in a party.").color("#ff9900"));
            return;
        }
        if (partyManager.createParty(senderUuid)) {
            senderRef.sendMessage(Message.raw("You created a new party. Use /party invite <player> to invite friends (alias: /eparty).").color("#00ff00"));
        } else {
            senderRef.sendMessage(Message.raw("Could not create party.").color("#ff0000"));
        }
    }
}
