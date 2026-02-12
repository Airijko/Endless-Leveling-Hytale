package com.airijko.endlessleveling.commands.races;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

/**
 * /races toggleracemodel : toggles whether race models apply to this player.
 */
public class ToggleRaceModelCommand extends AbstractPlayerCommand {

    public ToggleRaceModelCommand() {
        super("toggleracemodel", "Toggle applying race-specific models");
        this.addAliases("modeltoggle", "toggleracemodels");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false; // allow regular players to use
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
        RaceManager raceManager = plugin.getRaceManager();

        if (playerDataManager == null) {
            senderRef.sendMessage(Message.raw("Player data unavailable.").color("#ff6666"));
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

        if (raceManager != null && raceManager.isRaceModelGloballyDisabled()) {
            senderRef.sendMessage(Message.raw("Race models are disabled by the server configuration.")
                    .color("#ff6666"));
            raceManager.resetRaceModelIfOnline(data);
            return;
        }

        boolean newValue = !data.isUseRaceModel();
        data.setUseRaceModel(newValue);
        playerDataManager.save(data);

        if (newValue) {
            if (raceManager != null) {
                raceManager.applyRaceModelIfEnabled(data);
            }
            senderRef.sendMessage(Message.raw("Race models enabled.").color("#4fd7f7"));
        } else {
            if (raceManager != null) {
                raceManager.resetRaceModelIfOnline(data);
            }
            senderRef.sendMessage(Message.raw("Race models disabled.").color("#ff9900"));
        }
    }
}
