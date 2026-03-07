package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.LevelingManager;
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

public class PrestigeCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;

    public PrestigeCommand() {
        super("prestige", "Gain a prestige level when you are at your current max level");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.addAliases("ascend", "rankup");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        if (playerDataManager == null || levelingManager == null) {
            playerRef.sendMessage(Message.raw("Prestige system is unavailable right now.").color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("No player data found. Try rejoining.").color("#ff6666"));
            return;
        }

        int currentCap = levelingManager.getLevelCap(playerData);
        LevelingManager.PrestigeResult result = levelingManager.tryGainPrestige(playerData);
        switch (result) {
            case SUCCESS -> {
                int prestigeLevel = playerData.getPrestigeLevel();
                int newCap = levelingManager.getLevelCap(playerData);
                playerRef.sendMessage(Message.raw(
                        "Prestige increased to " + prestigeLevel + ". Level reset to 1. New cap: " + newCap + ".")
                        .color("#4fd7f7"));
            }
            case NOT_AT_CAP -> playerRef.sendMessage(
                    Message.raw("You must reach level " + currentCap + " before prestiging.").color("#ff9d00"));
            case DISABLED ->
                playerRef.sendMessage(Message.raw("Prestige is disabled on this server.").color("#ff6666"));
            default ->
                playerRef.sendMessage(Message.raw("Unable to prestige right now. Please try again.").color("#ff6666"));
        }
    }
}
