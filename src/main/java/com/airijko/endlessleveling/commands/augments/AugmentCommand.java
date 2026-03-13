package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.commands.subcommands.OpenPageSubCommand;
import com.airijko.endlessleveling.commands.subcommands.AugmentRefreshCommand;
import com.airijko.endlessleveling.commands.subcommands.AugmentRerollCommand;
import com.airijko.endlessleveling.commands.subcommands.ResetAugmentsCommand;
import com.airijko.endlessleveling.ui.AugmentsChoosePage;
import com.airijko.endlessleveling.ui.AugmentsUIPage;
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

public class AugmentCommand extends AbstractPlayerCommand {

    public AugmentCommand() {
        super("augments", "Augment commands");
        this.addAliases("augment", "aug", "a");
        this.addSubCommand(new AugmentRerollCommand("reroll", "Consume an unlocked reroll for your offers"));
        this.addSubCommand(new AugmentRefreshCommand("refresh", "Reroll stored augment offers for a player"));
        this.addSubCommand(new ResetAugmentsCommand("reset",
                "Reset selected augments and reroll all eligible offers"));
        this.addSubCommand(new ResetAugmentsAllPlayersCommand());
        this.addSubCommand(new OpenPageSubCommand("choose", "Open the augments choose page",
                playerRef -> new AugmentsChoosePage(playerRef, CustomPageLifetime.CanDismiss)));
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
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store,
                    new AugmentsUIPage(senderRef, CustomPageLifetime.CanDismiss));
        }
    }
}
