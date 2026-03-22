package com.airijko.endlessleveling.commands.classes;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.ClassManager;
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

/**
 * /el classes addswap &lt;player&gt;
 *
 * <p>Grants one additional primary class swap (and one secondary class swap
 * when secondary classes are enabled) to a player's active profile. Requires
 * the {@code endlessleveling.classes.addswap} permission node when executed by
 * a player, or an authorized EndlessLevelingPartnerAddon when executed from the
 * console.
 */
public class AddClassSwapCommand extends AbstractCommand {

    private static final String PERMISSION_NODE =
            HytalePermissions.fromCommand("endlessleveling.classes.addswap");

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;

    private final RequiredArg<String> playerArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public AddClassSwapCommand(ClassManager classManager, PlayerDataManager playerDataManager) {
        super("addswap", "Grant an additional class swap to a player");
        this.classManager = classManager;
        this.playerDataManager = playerDataManager;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.sender() instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
            CommandUtil.requirePermission(context.sender(), PERMISSION_NODE);
        } else {
            if (!PartnerConsoleGuard.isConsoleAllowed("el classes addswap")) {
                context.sendMessage(Message.raw(
                        "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
        }

        if (playerDataManager == null) {
            context.sendMessage(Message.raw("Player data system is not initialised.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String targetName = playerArg.get(context);
        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            context.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        int primaryBefore = targetData.getRemainingPrimaryClassSwitches();
        targetData.setRemainingPrimaryClassSwitches(primaryBefore + 1);

        StringBuilder summary = new StringBuilder("primary: ")
                .append(primaryBefore).append(" → ").append(primaryBefore + 1);

        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        int secondaryBefore = 0;
        if (secondaryEnabled) {
            secondaryBefore = targetData.getRemainingSecondaryClassSwitches();
            targetData.setRemainingSecondaryClassSwitches(secondaryBefore + 1);
            summary.append(", secondary: ").append(secondaryBefore).append(" → ").append(secondaryBefore + 1);
        }

        playerDataManager.save(targetData);

        context.sendMessage(Message.raw(
                "Added 1 class swap to " + targetName + " (" + summary + ").").color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            String msg = secondaryEnabled
                    ? "An admin granted you 1 additional primary and 1 secondary class swap."
                    : "An admin granted you 1 additional primary class swap.";
            targetRef.sendMessage(Message.raw(msg).color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }
}
