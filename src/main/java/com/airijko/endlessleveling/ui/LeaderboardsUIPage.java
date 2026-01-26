package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endlessleveling;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;

/**
 * Leaderboards page showing players ordered by level (and XP) descending.
 */
public class LeaderboardsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public LeaderboardsUIPage(@Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Leaderboards/LeaderboardsPage.ui");
        NavUIHelper.bindNavEvents(events);

        PlayerDataManager dataManager = Endlessleveling.getInstance().getPlayerDataManager();
        List<PlayerData> all = dataManager.getAllPlayersSortedByLevel().reversed();

        LOGGER.atInfo().log("LeaderboardsUIPage: rendering %d players", all.size());

        // Clear any existing rows and rebuild dynamically
        ui.clear("#RowCards");

        int index = 0;
        for (PlayerData pd : all) {
            String rowUi;
            if (index == 0) {
                rowUi = "Pages/Leaderboards/LeaderboardsRowFirst.ui";
            } else if (index == 1) {
                rowUi = "Pages/Leaderboards/LeaderboardsRowSecond.ui";
            } else if (index == 2) {
                rowUi = "Pages/Leaderboards/LeaderboardsRowThird.ui";
            } else {
                rowUi = "Pages/Leaderboards/LeaderboardsRow.ui";
            }

            ui.append("#RowCards", rowUi);

            String base = "#RowCards[" + index + "]";
            ui.set(base + " #Rank.Text", (index + 1) + ".");
            ui.set(base + " #Name.Text", pd.getPlayerName());
            ui.set(base + " #Level.Text", String.valueOf(pd.getLevel()));

            index++;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isEmpty()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }
        }
    }
}
