package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ResetAugmentsCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.augments.reset");

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final OptionalArg<String> targetArg = this.withOptionalArg("player", "Target player name", ArgTypes.STRING);

    public ResetAugmentsCommand() {
        this("resetaugments", "Reset selected augments and reroll eligible offers for the active profile",
                "augmentsreset", "resetallaugments");
    }

    public ResetAugmentsCommand(String name, String description, String... aliases) {
        super(name, description);
        if (aliases != null && aliases.length > 0) {
            this.addAliases(aliases);
        }
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        if (playerDataManager == null || augmentUnlockManager == null) {
            playerRef.sendMessage(Message.raw("Augment system is not initialised.").color("#ff6666"));
            return;
        }

        boolean hasTarget = targetArg.provided(commandContext);
        PlayerData targetData;
        PlayerRef targetRef;
        String targetName;

        if (hasTarget) {
            targetName = targetArg.get(commandContext);
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                playerRef.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
                return;
            }
            targetRef = Universe.get().getPlayer(targetData.getUuid());
        } else {
            targetData = playerDataManager.get(playerRef.getUuid());
            if (targetData == null) {
                playerRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
                return;
            }
            targetRef = playerRef;
            targetName = playerRef.getUsername();
        }

        int activeProfileIndex = targetData.getActiveProfileIndex();
        augmentUnlockManager.resetAllAugments(targetData);

        playerRef.sendMessage(Message
            .raw("Reset augments and rebuilt eligible offers for "
                + targetName
                + " on active profile "
                + activeProfileIndex
                + ".")
                .color("#4fd7f7"));
        if (targetRef != null && !targetRef.getUuid().equals(playerRef.getUuid())) {
            targetRef.sendMessage(Message
                .raw("An admin reset your augments on active profile "
                    + activeProfileIndex
                    + " and rerolled eligible offers.")
                    .color("#4fd7f7"));
        }
    }
}
