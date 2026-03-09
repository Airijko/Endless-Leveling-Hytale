package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class SetPrestigeCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.setprestige");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final AugmentUnlockManager augmentUnlockManager;

    private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
    private final RequiredArg<Integer> prestigeArg = this.withRequiredArg("prestige", "New prestige to set",
            ArgTypes.INTEGER);

    public SetPrestigeCommand() {
        super("setprestige", "Set a player's prestige level");

        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.augmentUnlockManager = EndlessLeveling.getInstance().getAugmentUnlockManager();
        this.addAliases("prestigeset");
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
        int requestedPrestige = prestigeArg.get(commandContext);

        if (requestedPrestige < 0) {
            senderRef.sendMessage(Message.raw("Prestige must be 0 or higher."));
            return;
        }

        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            senderRef.sendMessage(Message.raw("Player not found: " + targetName));
            return;
        }

        int previousPrestige = Math.max(0, targetData.getPrestigeLevel());
        int previousLevel = Math.max(1, targetData.getLevel());

        targetData.setPrestigeLevel(requestedPrestige);
        int newCap = levelingManager.getLevelCap(targetData);
        boolean clampedToCap = previousLevel > newCap;

        if (clampedToCap) {
            levelingManager.setPlayerLevel(targetData, newCap);
        } else {
            if (targetData.getLevel() >= newCap) {
                targetData.setXp(0.0D);
            }
            playerDataManager.save(targetData);
            PlayerHud.refreshHud(targetData.getUuid());
        }

        boolean removedExcessUnlocks = false;
        if (augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(targetData);
            removedExcessUnlocks = augmentUnlockManager.trimExcessUnlocks(targetData);
        }

        int finalLevel = Math.max(1, targetData.getLevel());
        String suffix = removedExcessUnlocks ? " Removed excess prestige augment slots." : "";

        if (clampedToCap) {
            senderRef.sendMessage(Message.raw(
                    "Set prestige of " + targetName + " from " + previousPrestige + " to " + requestedPrestige
                            + ". Level exceeded new cap and was clamped to " + finalLevel + "." + suffix));
        } else {
            senderRef.sendMessage(Message.raw(
                    "Set prestige of " + targetName + " from " + previousPrestige + " to " + requestedPrestige
                            + ". Level remains " + finalLevel + "." + suffix));
        }

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            if (clampedToCap) {
                targetRef.sendMessage(Message.raw(
                        "An admin set your prestige to " + requestedPrestige
                                + ". Your level was clamped to " + finalLevel + "." + suffix));
            } else {
                targetRef.sendMessage(Message.raw(
                        "An admin set your prestige to " + requestedPrestige + "." + suffix));
            }
        }
    }
}
