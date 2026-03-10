package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
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

public class AugmentRerollCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final OptionalArg<String> tierArg = this.withOptionalArg("tier", "Tier to reroll (ELITE/MYTHIC)",
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

        PassiveTier tier = resolveTier(commandContext, playerData);
        if (tier == null) {
            playerRef.sendMessage(Message.raw("No rerollable augment tier available. " + summarizeRerolls(playerData))
                    .color("#ff9900"));
            return;
        }

        int remainingBefore = augmentUnlockManager.getRemainingRerolls(playerData, tier);
        if (remainingBefore <= 0) {
            playerRef.sendMessage(
                    Message.raw("No rerolls remaining for " + tier.name() + ". " + summarizeRerolls(playerData))
                            .color("#ff9900"));
            return;
        }

        if (!augmentUnlockManager.tryConsumeReroll(playerData, tier)) {
            playerRef.sendMessage(Message.raw("No pending " + tier.name()
                    + " offers to reroll right now. Unlock offers first with /el augments.").color("#ff9900"));
            return;
        }

        int remainingAfter = augmentUnlockManager.getRemainingRerolls(playerData, tier);
        playerRef.sendMessage(Message.raw("Rerolled " + tier.name() + " augment offers. Remaining " + tier.name()
                + " rerolls: " + remainingAfter).color("#4fd7f7"));
    }

    private PassiveTier resolveTier(CommandContext commandContext, PlayerData playerData) {
        if (tierArg.provided(commandContext)) {
            String requested = tierArg.get(commandContext);
            return parseTier(requested);
        }

        PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON };
        for (PassiveTier tier : priority) {
            if (augmentUnlockManager.getRemainingRerolls(playerData, tier) > 0
                    && !playerData.getAugmentOffersForTier(tier.name()).isEmpty()) {
                return tier;
            }
        }

        for (PassiveTier tier : priority) {
            if (augmentUnlockManager.getRemainingRerolls(playerData, tier) > 0) {
                return tier;
            }
        }

        return null;
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
        for (PassiveTier tier : new PassiveTier[] { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON }) {
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
}
