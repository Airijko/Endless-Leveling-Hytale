package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.commands.races.RaceProfileCommand;
import com.airijko.endlessleveling.commands.races.RaceChooseCommand;
import com.airijko.endlessleveling.commands.races.ToggleRaceModelCommand;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RaceCommand extends AbstractPlayerCommand {

    private final RaceManager raceManager;

    public RaceCommand(RaceManager raceManager, PlayerDataManager playerDataManager) {
        super("races", "List available EndlessLeveling races");
        this.raceManager = raceManager;
        this.addAliases("race");
        this.addSubCommand(new RaceProfileCommand(raceManager, playerDataManager));
        this.addSubCommand(new RaceChooseCommand(raceManager, playerDataManager));
        this.addSubCommand(new ToggleRaceModelCommand());
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

        List<RaceDefinition> races = new ArrayList<>(raceManager.getLoadedRaces());
        if (races.isEmpty()) {
            senderRef.sendMessage(Message.raw("No races have been configured yet.").color("#ff6666"));
            return;
        }

        races.sort(Comparator.comparing(
                race -> race.getDisplayName() == null ? race.getId() : race.getDisplayName(),
                String.CASE_INSENSITIVE_ORDER));

        RaceDefinition defaultRace = raceManager.getDefaultRace();
        senderRef.sendMessage(Message.raw("Available races (" + races.size() + "): ").color("#4fd7f7"));

        for (RaceDefinition race : races) {
            boolean isDefault = defaultRace != null
                    && defaultRace.getId().equalsIgnoreCase(race.getId());
            String displayName = race.getDisplayName() != null ? race.getDisplayName() : race.getId();

            if (isDefault) {
                senderRef.sendMessage(Message.join(
                        Message.raw(" • ").color("#ffc300"),
                        Message.raw(displayName).color("#ffffff"),
                        Message.raw(" (default)").color("#6cff78")));
            } else {
                senderRef.sendMessage(Message.join(
                        Message.raw(" • ").color("#ffc300"),
                        Message.raw(displayName).color("#ffffff")));
            }
        }
    }
}
