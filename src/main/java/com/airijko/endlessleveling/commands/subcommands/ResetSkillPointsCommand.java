package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.PartnerConsoleGuard;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.airijko.endlessleveling.util.Lang;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * /el resetskillpoints - resets the caller's allocated attributes back to
 * the baseline.
 */
public class ResetSkillPointsCommand extends AbstractCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetskillpoints");

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final OptionalArg<String> targetArg = this.withOptionalArg("player", "Target player name", ArgTypes.STRING);

    public ResetSkillPointsCommand() {
        super("resetskillpoints", "Reset your EndlessLeveling skill points to their default distribution");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.addAliases("resetskills");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext commandContext) {
        Player senderPlayer = commandContext.senderAs(Player.class);
        boolean senderIsPlayer = senderPlayer != null;

        if (senderIsPlayer) {
            if (targetArg.provided(commandContext)) {
                CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);
            }
        } else if (!PartnerConsoleGuard.isConsoleAllowed("el resetskillpoints")) {
            commandContext.sendMessage(Message.raw(
                    "Console admin access requires an authorized EndlessLevelingPartnerAddon.")
                    .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        if (playerDataManager == null || skillManager == null) {
            if (senderIsPlayer) {
                PlayerRef senderRef = Universe.get().getPlayer(senderPlayer.getUuid());
                if (senderRef != null) {
                    senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                            "command.reset_skillpoints.system_unavailable",
                            "Skill system is not initialised. Please contact an admin."))
                            .color("#ff6666"));
                } else {
                    commandContext.sendMessage(Message.raw("Skill system is not initialised. Please contact an admin.")
                            .color("#ff6666"));
                }
            } else {
                commandContext.sendMessage(Message.raw("Skill system is not initialised. Please contact an admin.")
                        .color("#ff6666"));
            }
            return CompletableFuture.completedFuture(null);
        }

        boolean hasTarget = targetArg.provided(commandContext);

        PlayerData targetData;
        PlayerRef targetRef;
        String targetName;
        PlayerRef senderRef = senderIsPlayer ? Universe.get().getPlayer(senderPlayer.getUuid()) : null;

        if (hasTarget) {
            targetName = targetArg.get(commandContext);
            targetData = playerDataManager.getByName(targetName);
            if (targetData == null) {
                if (senderRef != null) {
                    senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                            "command.reset_skillpoints.player_not_found",
                            "Player not found: {0}", targetName))
                            .color("#ff6666"));
                } else {
                    commandContext.sendMessage(Message.raw("Player not found: " + targetName).color("#ff6666"));
                }
                return CompletableFuture.completedFuture(null);
            }
            targetRef = Universe.get().getPlayer(targetData.getUuid());
        } else {
            if (!senderIsPlayer || senderRef == null) {
                commandContext.sendMessage(Message.raw("Console usage requires a target player argument.")
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            targetData = playerDataManager.get(senderRef.getUuid());
            if (targetData == null) {
                senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                        "command.reset_skillpoints.no_data",
                        "No saved data found. Try rejoining."))
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }
            targetRef = senderRef;
            targetName = senderRef.getUsername();
        }

        skillManager.resetSkillAttributes(targetData);
        applySkillModifiers(targetData, targetRef);
        playerDataManager.save(targetData);

        if (senderRef != null) {
            senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                    "command.reset_skillpoints.success_target",
                    "Reset skill points for {0}.", targetName))
                    .color("#4fd7f7"));
        } else {
            commandContext.sendMessage(Message.raw("Reset skill points for " + targetName + ".").color("#4fd7f7"));
        }

        if (targetRef != null && (senderRef == null || !targetRef.getUuid().equals(senderRef.getUuid()))) {
            targetRef.sendMessage(Message.raw(Lang.tr(targetRef.getUuid(),
                    "command.reset_skillpoints.notify_target",
                    "An admin reset your skill points to the default layout."))
                    .color("#4fd7f7"));
        } else if (!hasTarget && senderRef != null) {
            senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                    "command.reset_skillpoints.success_self",
                    "Your skill points have been reset to the default layout."))
                    .color("#4fd7f7"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private void applySkillModifiers(PlayerData targetData,
            PlayerRef targetRef) {
        Ref<EntityStore> targetEntity;
        Store<EntityStore> targetStore;

        if (targetRef != null && targetRef.getReference() != null) {
            targetEntity = targetRef.getReference();
            targetStore = targetEntity.getStore();
        } else {
            // Offline targets cannot be applied immediately; defer via retry system.
            var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(targetData.getUuid());
            }
            return;
        }

        boolean applied = skillManager.applyAllSkillModifiers(targetEntity, targetStore, targetData);
        if (!applied) {
            LOGGER.atFine().log("ResetSkillPointsCommand: modifiers deferred for %s", targetData.getUuid());
            var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(targetData.getUuid());
            }
        }
    }
}
