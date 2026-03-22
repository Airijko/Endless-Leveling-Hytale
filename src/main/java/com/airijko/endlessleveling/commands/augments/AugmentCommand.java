package com.airijko.endlessleveling.commands.augments;

import com.airijko.endlessleveling.commands.subcommands.OpenPageSubCommand;
import com.airijko.endlessleveling.ui.AugmentsChoosePage;
import com.airijko.endlessleveling.ui.AugmentsUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class AugmentCommand extends AbstractCommand {

    public AugmentCommand() {
        super("augments", "Augment commands");
        this.addAliases("augment", "aug", "a");
        this.addSubCommand(new AugmentRerollSelectedCommand());
        this.addSubCommand(new AugmentAddRerollCommand());
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
    @Nullable
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        Player sender = context.sender() instanceof Player p ? p : null;
        if (sender == null) {
            context.sendMessage(Message.raw("This root command is player-only; use a subcommand from console.")
                    .color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> ref = sender.getReference();
        if (ref == null) {
            context.sendMessage(Message.raw("Unable to open augments page right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerRef senderRef = Universe.get().getPlayer(sender.getUuid());
        if (senderRef == null) {
            context.sendMessage(Message.raw("Unable to open augments page right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        World world = sender.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("Unable to open augments page right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        try {
            world.execute(() -> sender.getPageManager().openCustomPage(ref, ref.getStore(),
                    new AugmentsUIPage(senderRef, CustomPageLifetime.CanDismiss)));
        } catch (Exception ex) {
            context.sendMessage(Message.raw("Unable to open augments page right now.").color("#ff6666"));
        }

        return CompletableFuture.completedFuture(null);
    }
}
