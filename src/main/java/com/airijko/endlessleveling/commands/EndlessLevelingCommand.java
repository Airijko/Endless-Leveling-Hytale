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
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class EndlessLevelingCommand extends AbstractCommand {

        private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
        private static final String DEFAULT_COMMAND_NAME = "lvl";
        private static final String COMMAND_DESCRIPTION = "Endless Leveling Menu";
        private static final String[] ALL_COMMAND_NAMES = {"lvl", "el", "sk", "eskills", "level", "endlessleveling"};

        private String commandName;

        // Priority rules:
        // 1) Use the root command name always (`/lvl`).
        // 2) If `/lvl` is not available (another mod defines it), the framework may route one of the alias names here.
        //    This command still lives as a fallback via those aliases.

        public EndlessLevelingCommand() {
                this(DEFAULT_COMMAND_NAME);
        }

        public EndlessLevelingCommand(String commandName) {
                super(commandName, COMMAND_DESCRIPTION);
                this.commandName = commandName;

                LOGGER.atInfo().log("EndlessLevelingCommand initialized with root '/%s', aliases=%s", commandName,
                        Arrays.toString(ALL_COMMAND_NAMES));

                for (String candidate : ALL_COMMAND_NAMES) {
                        if (!candidate.equalsIgnoreCase(commandName)) {
                                this.addAliases(candidate);
                        }
                }

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
                // Prefer levels from root commandName; fall back to aliases.
                String invoked = resolveInvokedCommandName(commandContext);
                if (invoked != null && !commandName.equalsIgnoreCase(invoked)) {
                        LOGGER.atInfo().log("Command invoked through '/%s' while active root is '/%s'", invoked, commandName);
                        if (!isAlias(invoked)) {
                                commandContext.sendMessage(Message.raw("Command conflict: please use /" + commandName).color("#ffcc00"));
                                return CompletableFuture.completedFuture(null);
                        }
                }

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

        public void setActiveCommandRoot(String commandRoot) {
                if (commandRoot != null && !commandRoot.isBlank()) {
                        this.commandName = commandRoot;
                }
        }

        public String getActiveCommandRoot() {
                return commandName != null ? commandName : DEFAULT_COMMAND_NAME;
        }

        private static boolean isAlias(String value) {
                if (value == null || value.isBlank()) {
                        return false;
                }
                for (String candidate : ALL_COMMAND_NAMES) {
                        if (candidate.equalsIgnoreCase(value)) {
                                return true;
                        }
                }
                return false;
        }

        private static String resolveInvokedCommandName(CommandContext context) {
                if (context == null) {
                        return null;
                }
                try {
                        var method = context.getClass().getMethod("getCommand");
                        Object raw = method.invoke(context);
                        if (raw instanceof String) {
                                return ((String) raw).trim().toLowerCase();
                        }
                } catch (Exception ignored) {
                }
                try {
                        var method = context.getClass().getMethod("getCommandName");
                        Object raw = method.invoke(context);
                        if (raw instanceof String) {
                                return ((String) raw).trim().toLowerCase();
                        }
                } catch (Exception ignored) {
                }
                return null;
        }

        private OpenPageSubCommand addGuiShortcut(String keyword,
                        String description,
                        OpenPageSubCommand.PageFactory factory) {
                OpenPageSubCommand command = new OpenPageSubCommand(keyword, description, factory);
                this.addSubCommand(command);
                return command;
        }
}