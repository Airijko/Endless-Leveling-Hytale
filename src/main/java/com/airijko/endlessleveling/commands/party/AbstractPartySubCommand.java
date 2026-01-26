package com.airijko.endlessleveling.commands.party;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endlessleveling;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Shared logic for party subcommands to access managers and helper utilities.
 */
abstract class AbstractPartySubCommand extends AbstractPlayerCommand {

    protected final PartyManager partyManager;
    protected final PlayerDataManager playerDataManager;

    protected AbstractPartySubCommand(@Nonnull String name, @Nonnull String description) {
        super(name, description);
        Endlessleveling plugin = Endlessleveling.getInstance();
        this.partyManager = plugin.getPartyManager();
        this.playerDataManager = plugin.getPlayerDataManager();
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    protected String resolveName(@Nonnull UUID uuid) {
        PlayerData data = playerDataManager.get(uuid);
        if (data != null && data.getPlayerName() != null) {
            return data.getPlayerName();
        }
        PlayerRef ref = Universe.get().getPlayer(uuid);
        if (ref != null && ref.getUsername() != null) {
            return ref.getUsername();
        }
        return uuid.toString();
    }

    @Override
    protected abstract void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world);
}
