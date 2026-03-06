package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;

import javax.annotation.Nonnull;

public class SetLevelCommand extends AbstractPlayerCommand {

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

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        String targetName = targetArg.get(commandContext);
        int requestedLevel = levelArg.get(commandContext);

        if (requestedLevel < 1) {
            senderRef.sendMessage(Message.raw("Level must be 1 or higher."));
            return;
        }

        // Look up player by name in the data manager
        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            senderRef.sendMessage(Message.raw("Player not found: " + targetName));
            return;
        }

        int levelCap = levelingManager.getLevelCap(targetData);
        int clampedLevel = Math.min(requestedLevel, levelCap);

        // Apply level change through LevelingManager
        levelingManager.setPlayerLevel(targetData, clampedLevel);

        if (requestedLevel != clampedLevel) {
            senderRef.sendMessage(Message.raw(
                    "Requested level exceeds cap (" + levelCap + "). Applied cap instead."));
        }

        senderRef.sendMessage(Message.raw(
                "Set level of " + targetName + " to " + clampedLevel));

        // If target is online, notify them
        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(Message.raw(
                    "Your level has been set to " + clampedLevel + " by an admin!"));
        }
    }
}
