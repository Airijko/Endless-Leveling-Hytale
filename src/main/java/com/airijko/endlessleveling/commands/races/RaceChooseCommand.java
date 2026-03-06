package com.airijko.endlessleveling.commands.races;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.time.Instant;

/**
 * /races choose <race> : lets a player pick a race subject to cooldowns.
 */
public class RaceChooseCommand extends AbstractPlayerCommand {

    private final RaceManager raceManager;
    private final PlayerDataManager playerDataManager;

    private final RequiredArg<String> raceArg = this.withRequiredArg("race", "Race to choose", ArgTypes.STRING);

    public RaceChooseCommand(RaceManager raceManager, PlayerDataManager playerDataManager) {
        super("choose", "Choose a new EndlessLeveling race");
        this.raceManager = raceManager;
        this.playerDataManager = playerDataManager;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        if (raceManager == null || !raceManager.isEnabled()) {
            senderRef.sendMessage(Message.raw("Races are currently disabled.").color("#ff6666"));
            return;
        }
        if (playerDataManager == null) {
            senderRef.sendMessage(Message.raw("Player data is unavailable right now.").color("#ff6666"));
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

        String desiredInput = raceArg.get(context);
        if (desiredInput == null || desiredInput.isBlank()) {
            senderRef.sendMessage(Message.raw("Please specify a race to choose.").color("#ff6666"));
            return;
        }
        RaceDefinition desiredRace = raceManager.findRaceByUserInput(desiredInput);
        if (desiredRace == null) {
            senderRef.sendMessage(Message.join(
                    Message.raw("[Races] ").color("#ff6666"),
                    Message.raw("Unknown race: ").color("#ffffff"),
                    Message.raw(desiredInput).color("#ffc300")));
            return;
        }

        RaceDefinition currentRace = raceManager.getPlayerRace(data);
        if (currentRace != null && currentRace.getId().equalsIgnoreCase(desiredRace.getId())) {
            senderRef.sendMessage(Message.raw("You already belong to that race.").color("#ff6666"));
            return;
        }

        if (!OperatorHelper.isOperator(senderRef)) {
            if (!raceManager.hasRaceSwitchesRemaining(data)) {
                senderRef.sendMessage(Message.raw("No race changes remaining.").color("#ff6666"));
                return;
            }

            long now = Instant.now().getEpochSecond();
            long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
            long lastChange = data.getLastRaceChangeEpochSeconds();
            if (cooldownSeconds > 0 && lastChange > 0) {
                long availableAt = lastChange + cooldownSeconds;
                if (now < availableAt) {
                    long secondsRemaining = availableAt - now;
                    senderRef.sendMessage(Message.join(
                            Message.raw("You can choose another race in ").color("#ffffff"),
                            Message.raw(formatDuration(secondsRemaining)).color("#ffc300"),
                            Message.raw(".").color("#ffffff")));
                    return;
                }
            }
        }

        data.setRaceId(desiredRace.getId());
        raceManager.markRaceChange(data);
        playerDataManager.save(data);

        var skillManager = EndlessLeveling.getInstance().getSkillManager();
        boolean applied = false;
        if (skillManager != null) {
            applied = skillManager.applyAllSkillModifiers(ref, store, data);
        }
        if (!applied) {
            var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(data.getUuid());
            }
        }

        if (raceManager != null) {
            raceManager.applyRaceModelIfEnabled(data);
        }

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(data);
        }

        String displayName = desiredRace.getDisplayName() != null ? desiredRace.getDisplayName() : desiredRace.getId();
        senderRef.sendMessage(Message.join(
                Message.raw("[Races] ").color("#4fd7f7"),
                Message.raw("You are now a ").color("#ffffff"),
                Message.raw(displayName).color("#ffc300"),
                Message.raw("!").color("#ffffff")));
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h");
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(minutes).append("m");
        }
        if (remainingSeconds > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(remainingSeconds).append("s");
        }
        return builder.toString();
    }
}
