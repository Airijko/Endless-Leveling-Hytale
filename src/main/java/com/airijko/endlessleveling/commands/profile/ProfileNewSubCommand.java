package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
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

public class ProfileNewSubCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PassiveManager passiveManager;
    private final PlayerRaceStatSystem playerRaceStatSystem;

    private final RequiredArg<String> nameArg = this.withRequiredArg("name", "Display name for the profile",
            ArgTypes.STRING);

    public ProfileNewSubCommand() {
        super("new", "Create a new EndlessLeveling profile slot");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.passiveManager = EndlessLeveling.getInstance().getPassiveManager();
        this.playerRaceStatSystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        if (playerDataManager == null || skillManager == null) {
            senderRef.sendMessage(Message.raw("EndlessLeveling data is not ready. Please contact an admin.")
                    .color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(senderRef.getUuid());
        if (playerData == null) {
            playerData = playerDataManager.loadOrCreate(senderRef.getUuid(), senderRef.getUsername());
        }
        if (playerData == null) {
            senderRef.sendMessage(Message.raw("Unable to load your player data.").color("#ff6666"));
            return;
        }

        int nextSlot = playerData.findNextAvailableProfileSlot();
        if (!PlayerData.isValidProfileIndex(nextSlot)) {
            senderRef.sendMessage(Message
                    .raw("All " + PlayerData.MAX_PROFILES + " profile slots are already in use. Delete one first.")
                    .color("#ff6666"));
            return;
        }

        String requestedName = nameArg.get(context);
        boolean created = playerData.createProfile(nextSlot, requestedName, false, true);
        if (!created) {
            senderRef.sendMessage(Message.raw("Unable to create that profile slot right now.").color("#ff6666"));
            return;
        }

        resyncPassives(playerData);
        playerDataManager.save(playerData);

        boolean applied = skillManager.applyAllSkillModifiers(ref, store, playerData);
        if (!applied && playerRaceStatSystem == null) {
            senderRef.sendMessage(Message.raw("Skill modifiers could not be applied right now.").color("#ff6666"));
        }
        if (playerRaceStatSystem != null) {
            playerRaceStatSystem.scheduleRetry(playerData.getUuid());
        }

        PlayerHud.refreshHud(playerData.getUuid());
        String normalizedName = playerData.getProfileName(nextSlot);
        senderRef.sendMessage(Message
                .raw("Created and activated profile slot " + nextSlot + " (" + normalizedName + ").")
                .color("#4fd7f7"));
    }

    private void resyncPassives(PlayerData playerData) {
        if (passiveManager == null || playerData == null) {
            return;
        }
        passiveManager.syncPassives(playerData);
    }
}
