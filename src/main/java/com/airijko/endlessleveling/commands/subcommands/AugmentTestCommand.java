package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin command to force-select a specific augment for quick testing.
 * Replaces all existing selections with the chosen augment on its tier.
 */
public class AugmentTestCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.augments.test");

    private final AugmentManager augmentManager;
    private final PlayerDataManager playerDataManager;
    private final OptionalArg<String> targetArg = this.withOptionalArg("player", "Target player name", ArgTypes.STRING);
    private final RequiredArg<String> augmentArg = this.withRequiredArg("augment", "Augment id (case-insensitive)",
            ArgTypes.STRING);

    public AugmentTestCommand() {
        super("augmenttest", "Force select an augment for testing");
        this.addAliases("testaugment", "augmentdev", "augtest", "testaug");
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        if (augmentManager == null || playerDataManager == null) {
            senderRef.sendMessage(Message.raw("Augment system not initialised.").color("#ff6666"));
            return;
        }

        String augmentInput = augmentArg.get(commandContext);
        AugmentDefinition definition = resolveAugment(augmentInput);
        if (definition == null) {
            senderRef.sendMessage(Message.raw("Unknown augment: " + augmentInput).color("#ff6666"));
            senderRef.sendMessage(Message
                    .raw("Try one of: " + String.join(", ", sampleIds(augmentManager.getAugments()))).color("#4fd7f7"));
            return;
        }

        PlayerData targetData;
        PlayerRef targetRef;
        String targetName;
        if (targetArg.provided(commandContext)) {
            targetName = targetArg.get(commandContext);
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                senderRef.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
                return;
            }
            targetRef = Universe.get().getPlayer(targetData.getUuid());
            if (targetRef == null) {
                senderRef.sendMessage(Message.raw("Player is not online: " + targetName).color("#ff9f43"));
            }
        } else {
            targetData = playerDataManager.get(senderRef.getUuid());
            if (targetData == null) {
                senderRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
                return;
            }
            targetRef = senderRef;
            targetName = senderRef.getUsername();
        }

        // Replace existing augment selections with just the chosen augment on its tier.
        targetData.clearSelectedAugments();
        targetData.clearAugmentOffers();
        String tierKey = definition.getTier().name();
        targetData.setSelectedAugmentForTier(tierKey, definition.getId());
        targetData.setAugmentOffersForTier(tierKey, List.of(definition.getId()));
        playerDataManager.save(targetData);

        senderRef.sendMessage(
                Message.raw(String.format("Applied augment %s (%s) to %s", definition.getName(), tierKey, targetName))
                        .color("#6cff78"));
        if (targetRef != null && !targetRef.getUuid().equals(senderRef.getUuid())) {
            targetRef.sendMessage(Message
                    .raw(String.format("An admin applied augment %s (%s) for testing.", definition.getName(), tierKey))
                    .color("#6cff78"));
        }
    }

    private AugmentDefinition resolveAugment(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, AugmentDefinition> entry : augmentManager.getAugments().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
            AugmentDefinition def = entry.getValue();
            if (def != null && def.getName() != null && def.getName().equalsIgnoreCase(normalized)) {
                return def;
            }
        }
        return null;
    }

    private List<String> sampleIds(Map<String, AugmentDefinition> augments) {
        if (augments == null || augments.isEmpty()) {
            return List.of("none loaded");
        }
        List<String> ids = new ArrayList<>(augments.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        if (ids.size() > 10) {
            ids = ids.subList(0, 10);
            ids.add("...");
        }
        return ids;
    }
}