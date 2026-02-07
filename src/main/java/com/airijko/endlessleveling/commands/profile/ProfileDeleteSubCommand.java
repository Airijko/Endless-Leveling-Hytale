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

public class ProfileDeleteSubCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;

    private final RequiredArg<Integer> slotArg = this.withRequiredArg("slot", "Profile slot to delete",
            ArgTypes.INTEGER);

    public ProfileDeleteSubCommand() {
        super("delete", "Delete one of your EndlessLeveling profile slots");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
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
            senderRef.sendMessage(Message.raw("Profile slot " + slot + " is already empty.").color("#ffcc66"));
            return;
        }

        if (playerData.isProfileActive(slot)) {
            senderRef.sendMessage(Message
                    .raw("Switch to a different profile before deleting slot " + slot + ".")
                    .color("#ff6666"));
            return;
        }

        if (playerData.getProfileCount() <= 1) {
            senderRef.sendMessage(Message.raw("You must keep at least one profile slot.").color("#ff6666"));
            return;
        }

        boolean deleted = playerData.deleteProfile(slot);
        if (!deleted) {
            senderRef.sendMessage(Message.raw("Unable to delete that slot right now.").color("#ff6666"));
            return;
        }

        playerDataManager.save(playerData);
        senderRef.sendMessage(Message.raw("Deleted profile slot " + slot + ".").color("#4fd7f7"));
    }
}
