package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.PartnerConsoleGuard;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class ResetAugmentsCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.augments.reset");

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final RequiredArg<String> targetArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

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

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.sender() instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
            CommandUtil.requirePermission(context.sender(), PERMISSION_NODE);
        } else {
            if (!PartnerConsoleGuard.isConsoleAllowed("el augments reset")) {
                context.sendMessage(Message.raw(
                        "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
        }

        if (playerDataManager == null || augmentUnlockManager == null) {
            context.sendMessage(Message.raw("Augment system is not initialised.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String targetName = targetArg.get(context);
        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            context.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        int activeProfileIndex = targetData.getActiveProfileIndex();
        augmentUnlockManager.resetAllAugments(targetData);

        context.sendMessage(Message.raw(
                "Reset augments and rebuilt eligible offers for "
                + targetName + " on active profile " + activeProfileIndex + ".")
                .color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(Message.raw(
                    "An admin reset your augments on active profile "
                    + activeProfileIndex + " and rerolled eligible offers.").color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }
}
