package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.subcommands.ApplyModifiersCommand;
import com.airijko.endlessleveling.commands.subcommands.OpenPageSubCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetAllPlayersCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.SetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.StatTestCommand;
import com.airijko.endlessleveling.ui.SkillsUIPage;
import com.airijko.endlessleveling.ui.PartyUIPage;
import com.airijko.endlessleveling.ui.LeaderboardsUIPage;
import com.airijko.endlessleveling.ui.ProfileUIPage;
import com.airijko.endlessleveling.ui.SettingsUIPage;
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

        addGuiShortcut("party", "Open the EndlessLeveling Party page",
                playerRef -> new PartyUIPage(playerRef, CustomPageLifetime.CanDismiss));
        addGuiShortcut("leaderboards", "Open the EndlessLeveling Leaderboards page",
                playerRef -> new LeaderboardsUIPage(playerRef, CustomPageLifetime.CanDismiss));
        addGuiShortcut("settings", "Open the EndlessLeveling Settings page",
                playerRef -> new SettingsUIPage(playerRef, CustomPageLifetime.CanDismiss));
        addGuiShortcut("profile", "Open the EndlessLeveling Profile page",
                playerRef -> new ProfileUIPage(playerRef, CustomPageLifetime.CanDismiss));
        addGuiShortcut("attributes", "Open the EndlessLeveling Skills page",
                playerRef -> new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
    }

    @Override
    protected boolean canGeneratePermission() {
        return false; // let everyone open
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        Player player = commandContext.senderAs(Player.class);

        CompletableFuture.runAsync(() -> {
            player.getPageManager().openCustomPage(ref, store,
                    new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
        }, world);
    }

    private OpenPageSubCommand addGuiShortcut(String keyword,
            String description,
            OpenPageSubCommand.PageFactory factory) {
        OpenPageSubCommand command = new OpenPageSubCommand(keyword, description, factory);
        this.addSubCommand(command);
        return command;
    }
}