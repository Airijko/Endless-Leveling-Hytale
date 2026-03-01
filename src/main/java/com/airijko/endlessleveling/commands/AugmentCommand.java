package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.subcommands.AugmentRefreshCommand;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class AugmentCommand extends AbstractPlayerCommand {

    public AugmentCommand() {
        super("augment", "Admin augment commands");
        this.addSubCommand(new AugmentRefreshCommand("refresh", "Reroll stored augment offers for a player"));
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
        senderRef.sendMessage(Message.raw("Usage: /el augment refresh [player]").color("#4fd7f7"));
    }
}
