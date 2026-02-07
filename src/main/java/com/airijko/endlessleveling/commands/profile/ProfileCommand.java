package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ProfileCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final ProfileListSubCommand listSubCommand;

    public ProfileCommand() {
        super("profile", "Manage EndlessLeveling profiles");
        this.addAliases("profiles");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.listSubCommand = new ProfileListSubCommand();
        this.addSubCommand(listSubCommand);
        this.addSubCommand(new ProfileNewSubCommand());
        this.addSubCommand(new ProfileSelectSubCommand());
        this.addSubCommand(new ProfileDeleteSubCommand());
        this.addSubCommand(new ProfileRenameSubCommand());
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

        PlayerData data = playerDataManager.get(senderRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(senderRef.getUuid(), senderRef.getUsername());
        }
        if (data == null) {
            senderRef.sendMessage(Message.raw("Unable to load your player data.").color("#ff6666"));
            return;
        }

        senderRef.sendMessage(Message.raw(
                "Usage: /profile <list|new|select|delete|rename> ...").color("#4fd7f7"));

        listSubCommand.execute(context, store, ref, senderRef, world);
    }
}
