package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.PartnerConsoleGuard;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class AugmentRefreshCommand extends AbstractCommand {

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;
        private final RequiredArg<String> targetArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public AugmentRefreshCommand() {
        this("augmentrefresh",
                "Reroll stored augment offers for a player's active profile",
                "augmentsrefresh",
                "refreshaugments");
    }

    public AugmentRefreshCommand(String name, String description, String... aliases) {
        super(name, description);
        if (aliases != null && aliases.length > 0) {
            this.addAliases(aliases);
        }

        this.addUsageVariant(new RefreshSelfVariant());

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        return executeInternal(context, targetArg.get(context));
    }

    private CompletableFuture<Void> executeInternal(@Nonnull CommandContext context, @Nullable String explicitTargetName) {
        Player senderPlayer = context.sender() instanceof Player p ? p : null;
        boolean senderIsPlayer = senderPlayer != null;

        if (!PartnerConsoleGuard.isConsoleAllowed("el augments refresh")) {
            context.sendMessage(Message.raw(
                    "This command requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        if (playerDataManager == null || augmentUnlockManager == null) {
            context.sendMessage(Message.raw("Augment system is not initialised.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerData targetData;
        String targetName;
        PlayerRef targetRef;

        if (explicitTargetName != null && !explicitTargetName.isBlank()) {
            targetName = explicitTargetName;
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                context.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
            targetRef = Universe.get().getPlayer(targetData.getUuid());
        } else {
            if (!senderIsPlayer) {
                context.sendMessage(Message.raw("Console usage requires a target player argument.")
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            targetData = playerDataManager.get(senderPlayer.getUuid());
            if (targetData == null) {
                context.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
            targetRef = Universe.get().getPlayer(targetData.getUuid());
            targetName = targetRef != null ? targetRef.getUsername() : targetData.getPlayerName();
        }

        int activeProfileIndex = targetData.getActiveProfileIndex();
        augmentUnlockManager.refreshUnlocks(targetData);

        context.sendMessage(Message.raw(
                "Refreshed augment offers for " + targetName
                + " on active profile " + activeProfileIndex
                + " (selected augments unchanged).").color("#4fd7f7"));

        if (targetRef != null) {
            targetRef.sendMessage(Message.raw(
                    "An admin refreshed your augment offers on active profile "
                    + activeProfileIndex + ".").color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private final class RefreshSelfVariant extends AbstractCommand {

        private RefreshSelfVariant() {
            super("Reroll stored augment offers for your active profile");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return executeInternal(context, null);
        }
    }
}
