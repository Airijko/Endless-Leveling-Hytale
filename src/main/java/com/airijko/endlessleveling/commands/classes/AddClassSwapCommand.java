package com.airijko.endlessleveling.commands.classes;

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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * /el classes addswap &lt;player&gt; [&lt;count&gt;]
 *
 * <p>Grants additional primary class swaps (and secondary class swaps
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
        private final RequiredArg<Integer> countArg =
            this.withRequiredArg("count", "Number of swaps to add", ArgTypes.INTEGER);

    public AddClassSwapCommand(ClassManager classManager, PlayerDataManager playerDataManager) {
        super("addswap", "Grant an additional class swap to a player");
        this.classManager = classManager;
        this.playerDataManager = playerDataManager;
        this.addUsageVariant(new AddClassSwapSelfVariant());
        this.addUsageVariant(new AddClassSwapTargetVariant());
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

        if (senderIsPlayer) {
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

        int primaryBefore = targetData.getRemainingPrimaryClassSwitches();
        int primaryAfter = safeAddNonNegative(primaryBefore, count);
        int addedPrimary = Math.max(0, primaryAfter - primaryBefore);
        targetData.setRemainingPrimaryClassSwitches(primaryAfter);

        StringBuilder summary = new StringBuilder("primary: ")
            .append(primaryBefore).append(" -> ").append(primaryAfter);

        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        int secondaryBefore = 0;
        int secondaryAfter = 0;
        if (secondaryEnabled) {
            secondaryBefore = targetData.getRemainingSecondaryClassSwitches();
            secondaryAfter = safeAddNonNegative(secondaryBefore, count);
            targetData.setRemainingSecondaryClassSwitches(secondaryAfter);
            summary.append(", secondary: ").append(secondaryBefore).append(" -> ").append(secondaryAfter);
        }

        playerDataManager.save(targetData);

        context.sendMessage(Message.raw(
            "Added " + addedPrimary + " class swap(s) to " + targetName + " (" + summary + ").")
            .color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            String msg = secondaryEnabled
                ? "An admin granted you " + addedPrimary + " additional primary and "
                    + Math.max(0, secondaryAfter - secondaryBefore) + " secondary class swap(s)."
                : "An admin granted you " + addedPrimary + " additional primary class swap(s).";
            targetRef.sendMessage(Message.raw(msg).color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private final class AddClassSwapSelfVariant extends AbstractCommand {
        private AddClassSwapSelfVariant() {
            super("Grant yourself an additional class swap");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return executeInternal(context, null, 1);
        }
    }

    private final class AddClassSwapTargetVariant extends AbstractCommand {
        private final RequiredArg<String> targetPlayerArg =
                this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

        private AddClassSwapTargetVariant() {
            super("Grant a player one additional class swap");
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
