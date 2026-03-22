package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
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
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ResetAllCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetall");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;
    private final AugmentUnlockManager augmentUnlockManager;

    private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public ResetAllCommand() {
        super("resetall", "Reset a player's level to 1 and prestige to 0");

        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.augmentUnlockManager = EndlessLeveling.getInstance().getAugmentUnlockManager();
        this.addAliases("fullreset");
        this.addUsageVariant(new ResetAllSelfVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        return executeInternal(commandContext, targetArg.get(commandContext));
    }

    private CompletableFuture<Void> executeInternal(@Nonnull CommandContext commandContext, @Nullable String explicitTargetName) {
        if (commandContext.sender() instanceof Player) {
            CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el resetall")) {
            commandContext.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        Player senderPlayer = commandContext.sender() instanceof Player p ? p : null;
        boolean senderIsPlayer = senderPlayer != null;

        PlayerData targetData;
        String targetName;

        if (explicitTargetName != null && !explicitTargetName.isBlank()) {
            targetName = explicitTargetName;
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                commandContext.sendMessage(Message.raw("Player not found: " + targetName));
                return CompletableFuture.completedFuture(null);
            }
        } else {
            if (!senderIsPlayer) {
                commandContext.sendMessage(Message.raw("Console usage requires a target player argument.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            targetData = playerDataManager.get(senderPlayer.getUuid());
            if (targetData == null) {
                commandContext.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            PlayerRef selfRef = Universe.get().getPlayer(targetData.getUuid());
            targetName = selfRef != null ? selfRef.getUsername() : targetData.getPlayerName();
        }

        PlayerRef targetRef = Universe.get().getPlayer(targetData.getUuid());

        targetData.setPrestigeLevel(0);
        levelingManager.setPlayerLevel(targetData, 1);
        applySkillModifiers(targetData, targetRef);
        if (augmentUnlockManager != null) {
            augmentUnlockManager.trimExcessUnlocks(targetData);
        }

        commandContext.sendMessage(Message.raw("Reset level and prestige of " + targetName + " (level 1, prestige 0)."));

        if (targetRef != null) {
            targetRef.sendMessage(Message.raw("An admin reset your level to 1 and your prestige to 0."));
        }

        return CompletableFuture.completedFuture(null);
    }

    private final class ResetAllSelfVariant extends AbstractCommand {
        private ResetAllSelfVariant() {
            super("Reset your level to 1 and prestige to 0");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
            return executeInternal(commandContext, null);
        }
    }

    private void applySkillModifiers(PlayerData targetData, PlayerRef targetRef) {
        if (targetRef == null) {
            scheduleRetry(targetData);
            return;
        }
        UUID worldUuid = targetRef.getWorldUuid();
        World world = worldUuid != null ? Universe.get().getWorld(worldUuid) : null;
        if (world == null) {
            scheduleRetry(targetData);
            return;
        }
        try {
            world.execute(() -> {
                Ref<EntityStore> targetEntity = targetRef.getReference();
                if (targetEntity == null) {
                    scheduleRetry(targetData);
                    return;
                }
                Store<EntityStore> targetStore = targetEntity.getStore();
                boolean applied = skillManager.applyAllSkillModifiers(targetEntity, targetStore, targetData);
                if (!applied) {
                    scheduleRetry(targetData);
                }
            });
        } catch (Exception ex) {
            scheduleRetry(targetData);
        }
    }

    private void scheduleRetry(PlayerData targetData) {
        var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
        if (retrySystem != null) {
            retrySystem.scheduleRetry(targetData.getUuid());
        }
    }
}
