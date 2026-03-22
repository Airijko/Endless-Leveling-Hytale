package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.augments.AugmentCommand;
import com.airijko.endlessleveling.commands.subcommands.ApplyModifiersCommand;
import com.airijko.endlessleveling.commands.subcommands.OpenPageSubCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetAllPlayersCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetPrestigeCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetAllCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetCooldownsCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetSkillPointsCommand;
import com.airijko.endlessleveling.commands.subcommands.ReloadCommand;
import com.airijko.endlessleveling.commands.subcommands.LanguageCommand;
import com.airijko.endlessleveling.commands.subcommands.AugmentTestCommand;
import com.airijko.endlessleveling.commands.subcommands.PrestigeCommand;
import com.airijko.endlessleveling.commands.subcommands.SetLevelCommand;
import com.airijko.endlessleveling.commands.subcommands.SetPrestigeCommand;
import com.airijko.endlessleveling.commands.subcommands.DebugCommand;
import com.airijko.endlessleveling.commands.subcommands.ToggleHudCommand;
import com.airijko.endlessleveling.ui.SkillsUIPage;
import com.airijko.endlessleveling.ui.LeaderboardsUIPage;
import com.airijko.endlessleveling.ui.ProfileUIPage;
import com.airijko.endlessleveling.ui.SettingsUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class EndlessLevelingCommand extends AbstractCommand {

        private static final String COMMAND_NAME = "lvl";
        private static final String COMMAND_DESCRIPTION = "Endless Leveling Menu";

        public EndlessLevelingCommand() {
                super(COMMAND_NAME, COMMAND_DESCRIPTION);
                this.addAliases("el", "sk", "endlessleveling", "eskills", "level");
                this.addSubCommand(new SetLevelCommand());
                this.addSubCommand(new SetPrestigeCommand());
                this.addSubCommand(new ResetLevelCommand());
                this.addSubCommand(new ResetPrestigeCommand());
                this.addSubCommand(new ResetAllCommand());
                this.addSubCommand(new ApplyModifiersCommand());
                this.addSubCommand(new DebugCommand());
                this.addSubCommand(new ResetAllPlayersCommand());
                this.addSubCommand(new ResetSkillPointsCommand());
                this.addSubCommand(new ResetCooldownsCommand());
                this.addSubCommand(new PrestigeCommand());
                this.addSubCommand(new ReloadCommand());
                this.addSubCommand(new LanguageCommand());
                this.addSubCommand(new AugmentCommand());
                this.addSubCommand(new AugmentTestCommand());
                this.addSubCommand(new ToggleHudCommand());

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
        @Nullable
        protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
                Player player = commandContext.sender() instanceof Player p ? p : null;
                if (player == null) {
                        commandContext.sendMessage(Message.raw(
                                        "This root command is player-only; use a subcommand from console.")
                                        .color("#ff9900"));
                        return CompletableFuture.completedFuture(null);
                }

                Ref<EntityStore> ref = player.getReference();
                if (ref == null) {
                        commandContext.sendMessage(Message.raw("Unable to open skills page right now.").color("#ff6666"));
                        return CompletableFuture.completedFuture(null);
                }

                Store<EntityStore> store = ref.getStore();
                PlayerRef playerRef = Universe.get().getPlayer(player.getUuid());
                if (playerRef == null) {
                        commandContext.sendMessage(Message.raw("Unable to open skills page right now.").color("#ff6666"));
                        return CompletableFuture.completedFuture(null);
                }

                player.getPageManager().openCustomPage(ref, store,
                                new SkillsUIPage(playerRef, CustomPageLifetime.CanDismiss));
                return CompletableFuture.completedFuture(null);
        }

        private OpenPageSubCommand addGuiShortcut(String keyword,
                        String description,
                        OpenPageSubCommand.PageFactory factory) {
                OpenPageSubCommand command = new OpenPageSubCommand(keyword, description, factory);
                this.addSubCommand(command);
                return command;
        }
}