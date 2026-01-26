package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endlessleveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ResetAllPlayersCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetallplayers");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;

    public ResetAllPlayersCommand() {
        super("resetallplayers", "Reset every loaded player's level to 1");
        this.playerDataManager = Endlessleveling.getInstance().getPlayerDataManager();
        this.levelingManager = Endlessleveling.getInstance().getLevelingManager();
        this.skillManager = Endlessleveling.getInstance().getSkillManager();
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        var cachedPlayers = playerDataManager.getAllCached();
        if (cachedPlayers.isEmpty()) {
            senderRef.sendMessage(Message.raw("No loaded player data to reset."));
            return;
        }

        int resetCount = 0;
        for (PlayerData data : cachedPlayers) {
            levelingManager.setPlayerLevel(data, 1);
            PlayerRef targetRef = Universe.get().getPlayer(data.getUuid());
            applySkillModifiers(data, targetRef);
            if (targetRef != null) {
                targetRef.sendMessage(Message.raw("An admin reset your level back to 1."));
            }
            resetCount++;
        }

        senderRef.sendMessage(Message.raw("Reset " + resetCount + " player(s) to level 1."));
    }

    private void applySkillModifiers(PlayerData data, PlayerRef targetRef) {
        if (targetRef == null) {
            return;
        }

        Ref<EntityStore> targetEntity = targetRef.getReference();
        if (targetEntity == null) {
            return;
        }

        Store<EntityStore> targetStore = targetEntity.getStore();
        skillManager.applyAllSkillModifiers(targetEntity, targetStore, data);
    }
}
