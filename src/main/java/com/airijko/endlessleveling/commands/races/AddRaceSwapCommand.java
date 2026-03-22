package com.airijko.endlessleveling.commands.races;

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

/**
 * /el races addswap &lt;player&gt; [&lt;count&gt;]
 *
 * <p>Grants additional race swaps to a player's active profile. Requires the
 * {@code endlessleveling.races.addswap} permission node when executed by a
 * player, or an authorized EndlessLevelingPartnerAddon when executed from the
 * console.
 */
public class AddRaceSwapCommand extends AbstractCommand {

    private final PlayerDataManager playerDataManager;

    private final RequiredArg<String> playerArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
        private final RequiredArg<Integer> countArg =
            this.withRequiredArg("count", "Number of swaps to add", ArgTypes.INTEGER);

    public AddRaceSwapCommand(PlayerDataManager playerDataManager) {
        super("addswap", "Grant an additional race swap to a player");
        this.playerDataManager = playerDataManager;
        this.addUsageVariant(new AddRaceSwapSelfVariant());
        this.addUsageVariant(new AddRaceSwapTargetVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        return executeInternal(context, playerArg.get(context), countArg.get(context));
    }

    private CompletableFuture<Void> executeInternal(@Nonnull CommandContext context,
            @Nullable String explicitTargetName,
            int count) {
        Player senderPlayer = context.sender() instanceof Player p ? p : null;
        boolean senderIsPlayer = senderPlayer != null;

        if (!PartnerConsoleGuard.isConsoleAllowed("el races addswap")) {
            context.sendMessage(Message.raw(
                    "This command requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        if (playerDataManager == null) {
            context.sendMessage(Message.raw("Player data system is not initialised.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        if (count <= 0) {
            context.sendMessage(Message.raw("Count must be a positive integer.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerData targetData;
        String targetName;

        if (explicitTargetName != null && !explicitTargetName.isBlank()) {
            targetName = explicitTargetName;
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                context.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
        } else {
            if (!senderIsPlayer) {
                context.sendMessage(Message.raw("Console usage requires a target player argument.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            targetData = playerDataManager.get(senderPlayer.getUuid());
            if (targetData == null) {
                context.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            PlayerRef selfRef = Universe.get().getPlayer(targetData.getUuid());
            targetName = selfRef != null ? selfRef.getUsername() : targetData.getPlayerName();
        }

        int before = targetData.getRemainingRaceSwitches();
        int after = safeAddNonNegative(before, count);
        int added = Math.max(0, after - before);
        targetData.setRemainingRaceSwitches(after);
        playerDataManager.save(targetData);

        context.sendMessage(Message.raw(
            "Added " + added + " race swap(s) to " + targetName
            + " (remaining: " + before + " -> " + after + ").").color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(
                Message.raw("An admin granted you " + added + " additional race swap(s).").color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private final class AddRaceSwapSelfVariant extends AbstractCommand {
        private AddRaceSwapSelfVariant() {
            super("Grant yourself an additional race swap");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return executeInternal(context, null, 1);
        }
    }

    private final class AddRaceSwapTargetVariant extends AbstractCommand {
        private final RequiredArg<String> targetPlayerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

        private AddRaceSwapTargetVariant() {
            super("Grant a player one additional race swap");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return executeInternal(context, targetPlayerArg.get(context), 1);
        }
    }

    private int safeAddNonNegative(int base, int delta) {
        int safeBase = Math.max(0, base);
        int safeDelta = Math.max(0, delta);
        long sum = (long) safeBase + safeDelta;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
