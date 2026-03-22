package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.ui.PlayerHud;
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
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class SetPrestigeCommand extends AbstractCommand {

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

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        if (commandContext.sender() instanceof Player) {
            CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el setprestige")) {
            commandContext.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String targetName = targetArg.get(commandContext);
        int requestedPrestige = prestigeArg.get(commandContext);

        if (requestedPrestige < 0) {
            commandContext.sendMessage(Message.raw("Prestige must be 0 or higher."));
            return CompletableFuture.completedFuture(null);
        }

        Integer prestigeCap = levelingManager != null ? levelingManager.getPrestigeCap() : null;
        if (prestigeCap != null && requestedPrestige > prestigeCap) {
            commandContext.sendMessage(Message.raw(
                    "Prestige cannot be set above the configured cap of " + prestigeCap + "."));
            return CompletableFuture.completedFuture(null);
        }

        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            commandContext.sendMessage(Message.raw("Player not found: " + targetName));
            return CompletableFuture.completedFuture(null);
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
            commandContext.sendMessage(Message.raw(
                    "Set prestige of " + targetName + " from " + previousPrestige + " to " + requestedPrestige
                            + ". Level exceeded new cap and was clamped to " + finalLevel + "." + suffix));
        } else {
            commandContext.sendMessage(Message.raw(
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

        return CompletableFuture.completedFuture(null);
    }
}
