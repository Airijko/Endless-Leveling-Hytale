package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.PartnerConsoleGuard;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class SetLevelCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.setlevel");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;

    // Arguments: target player name, new level
    private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
    private final RequiredArg<Integer> levelArg = this.withRequiredArg("level", "New level to set", ArgTypes.INTEGER);

    public SetLevelCommand() {
        super("setlevel", "Set a player's level");

        // Initialize the managers from your main plugin instance
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        if (commandContext.sender() instanceof Player) {
            CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el setlevel")) {
            commandContext.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String targetName = targetArg.get(commandContext);
        int requestedLevel = levelArg.get(commandContext);

        if (requestedLevel < 1) {
            commandContext.sendMessage(Message.raw("Level must be 1 or higher."));
            return CompletableFuture.completedFuture(null);
        }

        // Look up player by name in the data manager
        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            commandContext.sendMessage(Message.raw("Player not found: " + targetName));
            return CompletableFuture.completedFuture(null);
        }

        int levelCap = levelingManager.getLevelCap(targetData);
        int clampedLevel = Math.min(requestedLevel, levelCap);

        // Apply level change through LevelingManager
        levelingManager.setPlayerLevel(targetData, clampedLevel);

        if (requestedLevel != clampedLevel) {
            commandContext.sendMessage(Message.raw(
                    "Requested level exceeds cap (" + levelCap + "). Applied cap instead."));
        }

        commandContext.sendMessage(Message.raw(
                "Set level of " + targetName + " to " + clampedLevel));

        // If target is online, notify them
        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(Message.raw(
                    "Your level has been set to " + clampedLevel + " by an admin!"));
        }

        return CompletableFuture.completedFuture(null);
    }
}
