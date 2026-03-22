package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.PartnerConsoleGuard;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class ResetAllPlayersCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetallplayers");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;

    public ResetAllPlayersCommand() {
        super("resetallplayers", "Reset every loaded player's level to 1");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        if (commandContext.sender() instanceof Player) {
            CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el resetallplayers")) {
            commandContext.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        var cachedPlayers = playerDataManager.getAllCached();
        if (cachedPlayers.isEmpty()) {
            commandContext.sendMessage(Message.raw("No loaded player data to reset."));
            return CompletableFuture.completedFuture(null);
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

        commandContext.sendMessage(Message.raw("Reset " + resetCount + " player(s) to level 1."));
        return CompletableFuture.completedFuture(null);
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
        boolean applied = skillManager.applyAllSkillModifiers(targetEntity, targetStore, data);
        if (!applied) {
            var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(data.getUuid());
            }
        }
    }
}
