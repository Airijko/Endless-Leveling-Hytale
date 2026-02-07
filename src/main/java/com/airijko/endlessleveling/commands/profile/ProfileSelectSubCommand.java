package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.ProfileSwitchResult;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.ui.PlayerHud;
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

public class ProfileSelectSubCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final PlayerRaceStatSystem playerRaceStatSystem;

    private final RequiredArg<Integer> profileIndexArg = this.withRequiredArg("slot", "Profile slot to activate",
            ArgTypes.INTEGER);

    public ProfileSelectSubCommand() {
        super("select", "Switch to another EndlessLeveling profile slot");
        this.addAliases("use");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.passiveManager = EndlessLeveling.getInstance().getPassiveManager();
        this.playerRaceStatSystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        if (playerDataManager == null || skillManager == null) {
            playerRef.sendMessage(Message.raw("Skill system is not initialised. Please contact an admin.")
                    .color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
            return;
        }

        int requestedIndex = profileIndexArg.get(commandContext);
        if (!PlayerData.isValidProfileIndex(requestedIndex)) {
            playerRef.sendMessage(Message
                    .raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff6666"));
            return;
        }

        if (!playerData.hasProfile(requestedIndex)) {
            playerRef.sendMessage(Message
                    .raw("Profile slot " + requestedIndex
                            + " has not been created yet. Use /skills profile new <name> first.")
                    .color("#ff6666"));
            return;
        }

        ProfileSwitchResult result = playerData.switchProfile(requestedIndex);
        if (result == ProfileSwitchResult.ALREADY_ACTIVE) {
            playerRef.sendMessage(Message.raw("Profile " + requestedIndex + " is already active.")
                    .color("#ffcc66"));
            return;
        }
        if (result == ProfileSwitchResult.INVALID_INDEX) {
            playerRef.sendMessage(Message
                    .raw("Unable to activate that slot. Please choose between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff6666"));
            return;
        }
        if (result == ProfileSwitchResult.MISSING_PROFILE) {
            playerRef.sendMessage(Message
                    .raw("Profile slot " + requestedIndex
                            + " has not been created yet. Use /skills profile new <name> first.")
                    .color("#ff6666"));
            return;
        }

        resyncPassives(playerData);
        playerDataManager.save(playerData);

        boolean applied = skillManager.applyAllSkillModifiers(ref, store, playerData);

        if (!applied && playerRaceStatSystem == null) {
            playerRef.sendMessage(Message.raw("Skill modifiers could not be applied right now.").color("#ff6666"));
        }

        if (playerRaceStatSystem != null) {
            playerRaceStatSystem.scheduleRetry(playerData.getUuid());
        }

        PlayerHud.refreshHud(playerData.getUuid());

        String profileName = playerData.getProfileName(requestedIndex);
        playerRef.sendMessage(Message
                .raw("Switched to profile slot " + requestedIndex + " (" + profileName + ").")
                .color("#4fd7f7"));
    }

    private void resyncPassives(PlayerData playerData) {
        if (passiveManager == null || playerData == null) {
            return;
        }
        passiveManager.syncPassives(playerData);
    }
}
