package com.airijko.endlessleveling.commands.party;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;

public class PartyInfoCommand extends AbstractPartySubCommand {

    public PartyInfoCommand() {
        super("info", "Show details about your party");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        UUID leaderUuid = partyManager.getPartyLeader(senderRef.getUuid());
        if (leaderUuid == null) {
            senderRef.sendMessage(Message.raw("You are not in a party.").color("#ff0000"));
            return;
        }

        Set<UUID> members = partyManager.getPartyMembers(senderRef.getUuid());
        String leaderName = resolveName(leaderUuid);
        StringBuilder sb = new StringBuilder();
        sb.append("Party leader: ").append(leaderName).append("\nMembers: ");

        boolean first = true;
        for (UUID member : members) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(resolveName(member));
            first = false;
        }

        senderRef.sendMessage(Message.raw(sb.toString()).color("#4fd7f7"));
    }
}
