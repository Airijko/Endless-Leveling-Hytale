package com.airijko.endlessleveling.commands.subcommands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Generic /skills subcommand that opens a specific EndlessLeveling UI page.
 */
public class OpenPageSubCommand extends AbstractPlayerCommand {

    private final PageFactory pageFactory;

    public OpenPageSubCommand(@Nonnull String name,
            @Nonnull String description,
            @Nonnull PageFactory pageFactory) {
        super(name, description);
        this.pageFactory = Objects.requireNonNull(pageFactory, "pageFactory");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        Player player = commandContext.senderAs(Player.class);
        if (player == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            InteractiveCustomUIPage<?> page = pageFactory.create(playerRef);
            player.getPageManager().openCustomPage(ref, store, page);
        }, world);
    }

    @FunctionalInterface
    public interface PageFactory {
        InteractiveCustomUIPage<?> create(PlayerRef playerRef);
    }
}
