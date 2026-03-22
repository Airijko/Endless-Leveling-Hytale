package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.enums.PassiveTier;
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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /el augments addreroll <player> <type> <count>
 *
 * <p>Grants additional augment rerolls of a given tier to a player by
 * increasing a dedicated reroll bonus pool for that tier.
 */
public class AugmentAddRerollCommand extends AbstractCommand {

    private static final String PERMISSION_NODE =
            HytalePermissions.fromCommand("endlessleveling.augments.addreroll");

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;

    private final RequiredArg<String> playerArg =
            this.withRequiredArg("player", "Target player name", ArgTypes.STRING);
    private final RequiredArg<String> typeArg =
            this.withRequiredArg("type", "Tier (COMMON/ELITE/LEGENDARY/MYTHIC)", ArgTypes.STRING);
    private final RequiredArg<Integer> countArg =
            this.withRequiredArg("count", "Number of rerolls to grant", ArgTypes.INTEGER);

    public AugmentAddRerollCommand() {
        super("addreroll", "Grant augment rerolls of a given tier to a player");
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.sender() instanceof com.hypixel.hytale.server.core.entity.entities.Player) {
            CommandUtil.requirePermission(context.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el augments addreroll")) {
            context.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        if (playerDataManager == null) {
            context.sendMessage(Message.raw("Player data system is not initialised.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        int count = countArg.get(context);
        if (count <= 0) {
            context.sendMessage(Message.raw("Count must be a positive integer.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        PassiveTier tier;
        try {
            tier = PassiveTier.valueOf(typeArg.get(context).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            context.sendMessage(Message.raw(
                    "Unknown tier: " + typeArg.get(context)
                            + ". Use COMMON, ELITE, LEGENDARY, or MYTHIC.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        String targetName = playerArg.get(context);
        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            context.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String tierKey = tier.name();
        int currentBonus = targetData.getAugmentRerollBonusForTier(tierKey);
        int newBonus = safeAddNonNegative(currentBonus, count);
        targetData.setAugmentRerollBonusForTier(tierKey, newBonus);
        playerDataManager.save(targetData);

        int granted = Math.max(0, newBonus - currentBonus);
        int totalRemaining = augmentUnlockManager != null
                ? augmentUnlockManager.getRemainingRerolls(targetData, tier)
                : granted;

        context.sendMessage(Message.raw(
                "Added " + granted + " " + tierKey + " reroll(s) to " + targetName
                        + " (bonus pool: " + currentBonus + " -> " + newBonus
                        + ", total remaining: " + totalRemaining + ").")
                .color("#4fd7f7"));

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());
        if (targetRef != null) {
            targetRef.sendMessage(Message.raw(
                    "An admin granted you " + granted + " " + tierKey
                            + " augment reroll(s). You now have " + totalRemaining + " " + tierKey
                            + " reroll(s) remaining.")
                    .color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private int safeAddNonNegative(int base, int delta) {
        int safeBase = Math.max(0, base);
        int safeDelta = Math.max(0, delta);
        long sum = (long) safeBase + safeDelta;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
