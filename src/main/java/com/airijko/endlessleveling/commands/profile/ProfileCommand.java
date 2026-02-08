package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.ui.ProfileUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class ProfileCommand extends AbstractPlayerCommand {

    public ProfileCommand() {
        super("profile", "Manage EndlessLeveling profiles");
        this.addAliases("profiles");
        this.addSubCommand(new ProfileListSubCommand());
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
        Player player = context.senderAs(Player.class);
        CompletableFuture.runAsync(() -> player.getPageManager().openCustomPage(ref, store,
                new ProfileUIPage(senderRef, CustomPageLifetime.CanDismiss)), world);
    }
}
