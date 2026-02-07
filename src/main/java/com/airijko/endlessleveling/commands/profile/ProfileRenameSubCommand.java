package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ProfileRenameSubCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;

    private final RequiredArg<Integer> slotArg = this.withRequiredArg("slot", "Profile slot to rename",
            ArgTypes.INTEGER);
    private final RequiredArg<String> nameArg = this.withRequiredArg("name", "New profile name", ArgTypes.STRING);

    public ProfileRenameSubCommand() {
        super("rename", "Rename one of your EndlessLeveling profiles");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        if (playerDataManager == null) {
            senderRef.sendMessage(Message.raw("Player data is unavailable right now.").color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(senderRef.getUuid());
        if (playerData == null) {
            senderRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
            return;
        }

        int slot = slotArg.get(context);
        if (!PlayerData.isValidProfileIndex(slot)) {
            senderRef.sendMessage(Message
                    .raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff6666"));
            return;
        }

        if (!playerData.hasProfile(slot)) {
            senderRef.sendMessage(Message.raw("Profile slot " + slot + " has not been created yet.")
                    .color("#ff6666"));
            return;
        }

        String newName = nameArg.get(context);
        boolean renamed = playerData.renameProfile(slot, newName);
        if (!renamed) {
            senderRef.sendMessage(Message.raw("Unable to rename that slot right now.").color("#ff6666"));
            return;
        }

        playerDataManager.save(playerData);
        senderRef.sendMessage(Message.raw("Profile slot " + slot + " is now named \""
                + playerData.getProfileName(slot) + "\".").color("#4fd7f7"));
    }
}
