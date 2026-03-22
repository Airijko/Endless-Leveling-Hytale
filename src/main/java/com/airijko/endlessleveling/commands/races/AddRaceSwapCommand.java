package com.airijko.endlessleveling.commands.races;

import com.airijko.endlessleveling.EndlessLeveling;
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
 * /el races addswap &lt;player&gt;
 *
 * <p>Grants one additional race swap to a player's active profile. Requires the
 * {@code endlessleveling.races.addswap} permission node when executed by a
 * player, or an authorized EndlessLevelingPartnerAddon when executed from the
 * console.
 */
public class AddRaceSwapCommand extends AbstractCommand {

    private static final String PERMISSION_NODE =
            HytalePermissions.fromCommand("endlessleveling.races.addswap");

    private final PlayerDataManager playerDataManager;

    private final RequiredArg<String> playerArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public AddRaceSwapCommand(PlayerDataManager playerDataManager) {
        super("addswap", "Grant an additional race swap to a player");
        this.playerDataManager = playerDataManager;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.sender() instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
            CommandUtil.requirePermission(context.sender(), PERMISSION_NODE);
        } else {
            if (!PartnerConsoleGuard.isConsoleAllowed("el races addswap")) {
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

        int before = targetData.getRemainingRaceSwitches();
        targetData.setRemainingRaceSwitches(before + 1);
        playerDataManager.save(targetData);

        context.sendMessage(Message.raw(
                "Added 1 race swap to " + targetName
                + " (remaining: " + before + " → " + (before + 1) + ").").color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(
                    Message.raw("An admin granted you 1 additional race swap.").color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }
}
