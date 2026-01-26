package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.party.PartyCreateCommand;
import com.airijko.endlessleveling.commands.party.PartyDisbandCommand;
import com.airijko.endlessleveling.commands.party.PartyInfoCommand;
import com.airijko.endlessleveling.commands.party.PartyInviteCommand;
import com.airijko.endlessleveling.commands.party.PartyJoinCommand;
import com.airijko.endlessleveling.commands.party.PartyLeaveCommand;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class PartyCommand extends AbstractPlayerCommand {

    public PartyCommand() {
        super("party", "Manage EndlessLeveling parties");
        this.addAliases("eparty");
        this.addSubCommand(new PartyCreateCommand());
        this.addSubCommand(new PartyInviteCommand());
        this.addSubCommand(new PartyJoinCommand());
        this.addSubCommand(new PartyLeaveCommand());
        this.addSubCommand(new PartyDisbandCommand());
        this.addSubCommand(new PartyInfoCommand());
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef senderRef,
                           @Nonnull World world) {
        senderRef.sendMessage(Message.raw("Usage: /party <create|invite|join|leave|disband|info>").color("#ff0000"));
    }
}
