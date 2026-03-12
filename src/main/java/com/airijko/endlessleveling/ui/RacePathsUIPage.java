package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Placeholder page for race ascension paths.
 */
public class RacePathsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private final RaceManager raceManager;
    private final PlayerDataManager playerDataManager;

    public RacePathsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Races/RacePathsPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef);
        NavUIHelper.bindNavEvents(events);

        String raceName = resolveCurrentRaceDisplayName();
        ui.set("#RacePathsTitleLabel.Text", raceName + " Paths");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isBlank()) {
            NavUIHelper.handleNavAction(data.action, ref, store, playerRef);
        }
    }

    private String resolveCurrentRaceDisplayName() {
        if (playerDataManager == null || raceManager == null) {
            return PlayerData.DEFAULT_RACE_ID;
        }

        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(playerRef.getUuid(), playerRef.getUsername());
        }
        if (data == null) {
            return PlayerData.DEFAULT_RACE_ID;
        }

        RaceDefinition currentRace = raceManager.getPlayerRace(data);
        if (currentRace == null) {
            return PlayerData.DEFAULT_RACE_ID;
        }

        String display = currentRace.getDisplayName();
        if (display == null || display.isBlank()) {
            return currentRace.getId();
        }
        return display;
    }
}
