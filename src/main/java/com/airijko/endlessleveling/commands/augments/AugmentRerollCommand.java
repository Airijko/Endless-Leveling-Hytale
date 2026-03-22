package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AugmentRerollCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final OptionalArg<String> tierOrAugmentArg = this.withOptionalArg("tier_or_augment",
            "Tier (ELITE/LEGENDARY/MYTHIC) or offered augment id",
            ArgTypes.STRING);
    private final OptionalArg<String> augmentArg = this.withOptionalArg("augment",
            "Offered augment id to reroll (when tier is provided)",
            ArgTypes.STRING);

    public AugmentRerollCommand() {
        this("augmentreroll", "Consume an unlocked augment reroll", "rerollaugments", "rerollaugment");
    }

    public AugmentRerollCommand(String name, String description, String... aliases) {
        super(name, description);
        if (aliases != null && aliases.length > 0) {
            this.addAliases(aliases);
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        if (playerDataManager == null || augmentUnlockManager == null) {
            playerRef.sendMessage(Message.raw("Augment system is not initialised.").color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
            return;
        }

        augmentUnlockManager.ensureUnlocks(playerData);

        ParsedTarget parsedTarget = parseTarget(commandContext);
        if (!parsedTarget.valid()) {
            playerRef.sendMessage(Message.raw(parsedTarget.error()).color("#ff9900"));
            playerRef.sendMessage(Message.raw("Usage: /el augment reroll <augment_id>").color("#4fd7f7"));
            playerRef.sendMessage(Message.raw("Usage: /el augment reroll <tier> <augment_id>").color("#4fd7f7"));
            playerRef.sendMessage(Message.raw(describePendingOffers(playerData)).color("#4fd7f7"));
            return;
        }

        String targetAugmentId = parsedTarget.augmentId();
        PassiveTier tier = parsedTarget.tier();
        if (tier == null) {
            tier = augmentUnlockManager.findOfferTier(playerData, targetAugmentId);
        }
        if (tier == null) {
            playerRef.sendMessage(Message.raw("That augment is not currently in your pending offers: "
                    + targetAugmentId).color("#ff9900"));
            playerRef.sendMessage(Message.raw(describePendingOffers(playerData)).color("#4fd7f7"));
            return;
        }

        int remainingBefore = augmentUnlockManager.getRemainingRerolls(playerData, tier);
        if (remainingBefore <= 0) {
            playerRef.sendMessage(Message.raw("No rerolls remaining for " + tier.name() + ". "
                    + summarizeRerolls(playerData)).color("#ff9900"));
            return;
        }

        String replacementId = augmentUnlockManager.tryConsumeRerollForOffer(playerData, tier, targetAugmentId);
        if (replacementId == null) {
            playerRef.sendMessage(Message.raw("Unable to reroll that offer right now. Make sure it is pending under "
                    + tier.name() + ".").color("#ff9900"));
            return;
        }

        int remainingAfter = augmentUnlockManager.getRemainingRerolls(playerData, tier);
        playerRef.sendMessage(Message.raw("Rerolled " + targetAugmentId + " -> " + replacementId + " ("
                + tier.name() + "). Remaining " + tier.name() + " rerolls: " + remainingAfter).color("#4fd7f7"));
    }

    private ParsedTarget parseTarget(CommandContext commandContext) {
        String first = tierOrAugmentArg.provided(commandContext) ? tierOrAugmentArg.get(commandContext) : null;
        String second = augmentArg.provided(commandContext) ? augmentArg.get(commandContext) : null;

        if (first == null || first.isBlank()) {
            return ParsedTarget.invalid("You must specify which pending augment to reroll.");
        }

        if (second != null && !second.isBlank()) {
            PassiveTier tier = parseTier(first);
            if (tier == null) {
                return ParsedTarget.invalid("Invalid tier: " + first + " (use ELITE, LEGENDARY, or MYTHIC).");
            }
            return ParsedTarget.valid(tier, second.trim());
        }

        PassiveTier maybeTier = parseTier(first);
        if (maybeTier != null) {
            return ParsedTarget.invalid("Tier-only reroll is not supported. Specify the augment id to reroll.");
        }

        return ParsedTarget.valid(null, first.trim());
    }

    private PassiveTier parseTier(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return PassiveTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String summarizeRerolls(PlayerData playerData) {
        List<String> entries = new ArrayList<>();
        for (PassiveTier tier : new PassiveTier[] {
            PassiveTier.MYTHIC,
            PassiveTier.LEGENDARY,
            PassiveTier.ELITE,
            PassiveTier.COMMON
        }) {
            int remaining = augmentUnlockManager.getRemainingRerolls(playerData, tier);
            if (remaining > 0) {
                entries.add(tier.name() + ": " + remaining);
            }
        }

        if (entries.isEmpty()) {
            return "No unlocked rerolls.";
        }

        return "Unlocked rerolls -> " + String.join(", ", entries);
    }

    private String describePendingOffers(PlayerData playerData) {
        if (playerData == null) {
            return "Pending offers unavailable.";
        }

        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        List<String> lines = new ArrayList<>();
        for (PassiveTier tier : new PassiveTier[] {
            PassiveTier.MYTHIC,
            PassiveTier.LEGENDARY,
            PassiveTier.ELITE,
            PassiveTier.COMMON
        }) {
            List<String> tierOffers = offers.getOrDefault(tier.name(), List.of());
            if (tierOffers.isEmpty()) {
                continue;
            }
            lines.add(tier.name() + ": " + String.join(", ", tierOffers));
        }

        if (lines.isEmpty()) {
            return "No pending augment offers to reroll.";
        }

        return "Pending offers -> " + String.join(" | ", lines);
    }

    private record ParsedTarget(PassiveTier tier, String augmentId, boolean valid, String error) {
        static ParsedTarget valid(PassiveTier tier, String augmentId) {
            return new ParsedTarget(tier, augmentId, true, "");
        }

        static ParsedTarget invalid(String error) {
            return new ParsedTarget(null, "", false, error == null ? "Invalid reroll target." : error);
        }
    }
}
