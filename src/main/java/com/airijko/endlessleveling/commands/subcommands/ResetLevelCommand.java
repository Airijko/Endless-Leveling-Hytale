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
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;

import javax.annotation.Nonnull;

public class ResetLevelCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetlevel");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;

    private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public ResetLevelCommand() {
        super("resetlevel", "Reset a player's level back to 1");

        this.playerDataManager = Endlessleveling.getInstance().getPlayerDataManager();
        this.levelingManager = Endlessleveling.getInstance().getLevelingManager();
        this.skillManager = Endlessleveling.getInstance().getSkillManager();
    }

    @Override
    protected void execute(
            @Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        String targetName = targetArg.get(commandContext);

        PlayerData targetData = playerDataManager.getByName(targetName);
        if (targetData == null) {
            senderRef.sendMessage(Message.raw("Player not found: " + targetName));
            return;
        }

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());

        levelingManager.setPlayerLevel(targetData, 1);
        applySkillModifiers(targetData, targetRef);

        senderRef.sendMessage(Message.raw("Reset level of " + targetName + " to 1."));

        if (targetRef != null) {
            targetRef.sendMessage(Message.raw("An admin reset your level back to 1."));
        }
    }

    private void applySkillModifiers(PlayerData targetData, PlayerRef targetRef) {
        if (targetRef == null) {
            return;
        }

        Ref<EntityStore> targetEntity = targetRef.getReference();
        if (targetEntity == null) {
            return;
        }

        Store<EntityStore> targetStore = targetEntity.getStore();

        skillManager.applyAllSkillModifiers(targetEntity, targetStore, targetData);
    }
}
