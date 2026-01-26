package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.subcommands.ApplyModifiersCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetAllPlayersCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.SetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.StatTestCommand;
import com.airijko.endlessleveling.ui.SkillsUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class EndlessLevelingCommand extends AbstractPlayerCommand {

    public EndlessLevelingCommand(@Nonnull String name, @Nonnull String description) {
        super(name, description);
        this.addAliases("el", "endlessleveling", "skill");
        this.addSubCommand(new SetLevelCommand());
        this.addSubCommand(new ResetLevelCommand());
        this.addSubCommand(new ApplyModifiersCommand());
        this.addSubCommand(new StatTestCommand());
        this.addSubCommand(new ResetAllPlayersCommand());
    }

    @Override
    protected boolean canGeneratePermission() {
        return false; // let everyone open
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player player = commandContext.senderAs(Player.class);

        CompletableFuture.runAsync(() -> {
            player.getPageManager().openCustomPage(ref, store, new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
        }, world);
    }
}