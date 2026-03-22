package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.ui.PlayerHud;
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

public class ResetPrestigeCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetprestige");

    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;
    private final AugmentUnlockManager augmentUnlockManager;

    private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Target player name", ArgTypes.STRING);

    public ResetPrestigeCommand() {
        super("resetprestige", "Reset a player's prestige to 0 without resetting level unless capped");

        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.augmentUnlockManager = EndlessLeveling.getInstance().getAugmentUnlockManager();
        this.addAliases("prestigereset");
        this.addUsageVariant(new ResetPrestigeSelfVariant());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        return executeInternal(commandContext, targetArg.get(commandContext));
    }

    private CompletableFuture<Void> executeInternal(@Nonnull CommandContext commandContext, @Nullable String explicitTargetName) {
        if (commandContext.sender() instanceof Player) {
            CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el resetprestige")) {
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
        int previousPrestige = Math.max(0, targetData.getPrestigeLevel());
        int previousLevel = Math.max(1, targetData.getLevel());

        targetData.setPrestigeLevel(0);
        int prestigeZeroCap = levelingManager.getLevelCap(targetData);

        boolean clampedToCap = previousLevel > prestigeZeroCap;
        if (clampedToCap) {
            levelingManager.setPlayerLevel(targetData, prestigeZeroCap);
            applySkillModifiers(targetData, targetRef);
        } else {
            if (targetData.getLevel() >= prestigeZeroCap) {
                targetData.setXp(0.0D);
            }
            playerDataManager.save(targetData);
            PlayerHud.refreshHud(targetData.getUuid());
        }

        boolean removedPrestigeAugmentSlots = false;
        if (augmentUnlockManager != null) {
            removedPrestigeAugmentSlots = augmentUnlockManager.trimExcessUnlocks(targetData);
        }

        if (clampedToCap) {
            commandContext.sendMessage(Message.raw(
                    "Reset prestige of " + targetName + " from " + previousPrestige
                            + " to 0. Level exceeded cap and was clamped to " + prestigeZeroCap
                            + (removedPrestigeAugmentSlots ? ". Removed excess prestige augment slots." : ".")));
        } else {
            commandContext.sendMessage(Message.raw(
                    "Reset prestige of " + targetName + " from " + previousPrestige
                            + " to 0. Level remains " + previousLevel
                            + (removedPrestigeAugmentSlots ? ". Removed excess prestige augment slots." : ".")));
        }

        if (targetRef != null) {
            if (clampedToCap) {
                targetRef.sendMessage(Message.raw(
                        "An admin reset your prestige to 0. Your level was clamped to " + prestigeZeroCap
                                + (removedPrestigeAugmentSlots ? ". Extra prestige augment slots were removed."
                                        : ".")));
            } else {
                targetRef.sendMessage(Message.raw(
                        "An admin reset your prestige to 0."
                                + (removedPrestigeAugmentSlots ? " Extra prestige augment slots were removed." : "")));
            }
        }

            return CompletableFuture.completedFuture(null);
    }

    private final class ResetPrestigeSelfVariant extends AbstractCommand {
        private ResetPrestigeSelfVariant() {
            super("Reset your prestige to 0");
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
