package com.airijko.endlessleveling.commands.profile;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.PlayerProfile;
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
import java.util.Comparator;
import java.util.Map;

public class ProfileListSubCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;

    public ProfileListSubCommand() {
        super("list", "List your EndlessLeveling profiles");
        this.addAliases("show");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
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

        final PlayerData finalData = data;

        senderRef.sendMessage(Message.raw(
                "You currently have " + finalData.getProfileCount() + "/" + PlayerData.MAX_PROFILES + " profiles.")
                .color("#cccccc"));

        finalData.getProfiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    int slot = entry.getKey();
                    PlayerProfile profile = entry.getValue();
                    boolean active = finalData.isProfileActive(slot);
                    String header = (active ? "*" : "•") + " Slot " + slot + " [" + profile.getName() + "]";
                    senderRef.sendMessage(Message.join(
                            Message.raw(header + ": ").color(active ? "#ffc300" : "#999999"),
                            Message.raw("Level " + profile.getLevel()).color("#ffffff"),
                            Message.raw(", XP " + (long) profile.getXp()).color("#6cff78"),
                            Message.raw(", Race " + profile.getRaceId()).color("#4fd7f7")));
                });

        if (finalData.getProfileCount() < PlayerData.MAX_PROFILES) {
            senderRef.sendMessage(Message
                    .raw("Use /profile new <name> to create another profile.")
                    .color("#4fd7f7"));
        } else {
            senderRef.sendMessage(Message.raw("All profile slots are currently in use.").color("#ffcc66"));
        }
    }
}
