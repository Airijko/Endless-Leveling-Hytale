package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Placeholder page for race ascension paths.
 */
public class RacePathsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final String TIER_ROW_TEMPLATE = "Pages/Races/RacePathTierRow.ui";
    private static final String NODE_CARD_TEMPLATE = "Pages/Races/RacePathNodeCard.ui";
    private static final int MAX_NODES_PER_TIER_ROW = 3;
    private static final int MAX_TIER_DEPTH = 12;

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

        PlayerData data = loadPlayerData();
        RaceDefinition currentRace = data != null && raceManager != null ? raceManager.getPlayerRace(data) : null;

        String titleRace = currentRace != null ? resolveDisplayName(currentRace) : PlayerData.DEFAULT_RACE_ID;
        ui.set("#RacePathsTitleLabel.Text", titleRace + " Paths");

        ui.clear("#RacePathRows");

        List<PathTierRow> rows = buildTierRows(currentRace);
        renderTierRows(ui, rows);
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

    private List<PathTierRow> buildTierRows(RaceDefinition rootRace) {
        List<PathTierRow> rows = new ArrayList<>();
        if (rootRace == null) {
            rows.add(PathTierRow.base(List.of()));
            return rows;
        }

        rows.add(PathTierRow.base(List.of(rootRace)));

        Set<String> seen = new LinkedHashSet<>();
        seen.add(pathKey(rootRace));

        List<RaceDefinition> frontier = collectUniqueChildren(List.of(rootRace), seen);
        List<RaceDefinition> finalTier = new ArrayList<>();
        Set<String> finalSeen = new LinkedHashSet<>();

        int tierNumber = 1;
        int depth = 0;
        while (!frontier.isEmpty() && depth < MAX_TIER_DEPTH) {
            depth++;

            List<RaceDefinition> nonFinalTier = new ArrayList<>();
            for (RaceDefinition candidate : frontier) {
                if (candidate == null) {
                    continue;
                }
                if (candidate.getAscension().isFinalForm()) {
                    String key = pathKey(candidate);
                    if (finalSeen.add(key)) {
                        finalTier.add(candidate);
                    }
                } else {
                    nonFinalTier.add(candidate);
                }
            }

            if (!nonFinalTier.isEmpty()) {
                rows.add(PathTierRow.tier(tierNumber, nonFinalTier));
                tierNumber++;
                frontier = collectUniqueChildren(nonFinalTier, seen);
            } else {
                frontier = List.of();
            }
        }

        if (!finalTier.isEmpty()) {
            rows.add(PathTierRow.finalTier(finalTier));
        }

        return rows;
    }

    private List<RaceDefinition> collectUniqueChildren(List<RaceDefinition> parents, Set<String> seen) {
        if (raceManager == null || parents == null || parents.isEmpty()) {
            return List.of();
        }

        List<RaceDefinition> result = new ArrayList<>();
        for (RaceDefinition parent : parents) {
            if (parent == null) {
                continue;
            }
            for (RaceDefinition child : raceManager.getNextAscensionRaces(parent.getId())) {
                if (child == null) {
                    continue;
                }
                String key = pathKey(child);
                if (seen.add(key)) {
                    result.add(child);
                }
            }
        }
        return result;
    }

    private String pathKey(RaceDefinition race) {
        if (race == null) {
            return "";
        }
        RaceAscensionDefinition asc = race.getAscension();
        String key = asc != null && asc.getId() != null && !asc.getId().isBlank() ? asc.getId() : race.getId();
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private void renderTierRows(UICommandBuilder ui, List<PathTierRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PathTierRow row = rows.get(rowIndex);
            ui.append("#RacePathRows", TIER_ROW_TEMPLATE);
            String rowBase = "#RacePathRows[" + rowIndex + "]";

            ui.set(rowBase + " #TierLabel.Text", row.label());
            ui.set(rowBase + " #TierLabel.Style.TextColor", row.finalTier() ? "#ffe59f" : "#9fb6d3");

            renderTierCards(ui, rowBase + " #TierCards", row.nodes(), row.baseTier(), row.finalTier());

            // Hide connectors in horizontal layout - the left-to-right flow is self-evident
            ui.set(rowBase + " #TierConnector.Visible", false);
        }
    }

    private void renderTierCards(UICommandBuilder ui,
            String cardsSelector,
            List<RaceDefinition> races,
            boolean baseTier,
            boolean finalTier) {
        List<RaceDefinition> nodes = races == null ? List.of() : races;
        if (nodes.isEmpty()) {
            return;
        }

        PlayerData playerData = loadPlayerData();
        RaceDefinition currentPlayerRace = playerData != null && raceManager != null
                ? raceManager.getPlayerRace(playerData)
                : null;

        int rowIndex = 0;
        int inRow = 0;
        for (RaceDefinition race : nodes) {
            if (inRow == 0) {
                ui.appendInline(cardsSelector, "Group { LayoutMode: Middle; Anchor: (Bottom: 0); }");
            }

            ui.append(cardsSelector + "[" + rowIndex + "]", NODE_CARD_TEMPLATE);
            String nodeBase = cardsSelector + "[" + rowIndex + "][" + inRow + "]";
            ui.set(nodeBase + " #NodeName.Text", buildNodeLabel(race, !baseTier));
            ui.set(nodeBase + " #NodeIcon.ItemId", resolveIconItemId(race));
            ui.set(nodeBase + " #NodeName.Style.TextColor", finalTier ? "#ffe59f" : "#dbe7f5");

            NodeStatus status = resolveNodeStatus(race, baseTier, playerData, currentPlayerRace);
            ui.set(nodeBase + " #NodeStatus.Text", status.label());
            ui.set(nodeBase + " #NodeStatus.Style.TextColor", status.color());

            inRow++;
            if (inRow >= MAX_NODES_PER_TIER_ROW) {
                inRow = 0;
                rowIndex++;
            }
        }
    }

    private NodeStatus resolveNodeStatus(RaceDefinition race,
            boolean baseTier,
            PlayerData playerData,
            RaceDefinition currentPlayerRace) {
        if (baseTier || isRaceUnlocked(race, currentPlayerRace, playerData)) {
            return NodeStatus.unlocked();
        }
        if (isRaceAvailable(race, playerData)) {
            return NodeStatus.available();
        }
        return NodeStatus.locked();
    }

    private boolean isRaceUnlocked(RaceDefinition race, RaceDefinition currentPlayerRace, PlayerData playerData) {
        if (race == null) {
            return false;
        }
        String raceKey = pathKey(race);

        if (currentPlayerRace != null) {
            String currentKey = pathKey(currentPlayerRace);
            if (raceKey.equals(currentKey)) {
                return true;
            }
        }

        if (playerData == null || raceManager == null) {
            return false;
        }

        String pathId = raceManager.resolveAscensionPathId(race.getId());
        if (pathId != null && playerData.hasCompletedRaceForm(pathId)) {
            return true;
        }

        return playerData.hasCompletedRaceForm(race.getId());
    }

    private boolean isRaceAvailable(RaceDefinition race, PlayerData playerData) {
        if (race == null || playerData == null || raceManager == null) {
            return false;
        }
        RaceAscensionEligibility eligibility = raceManager.evaluateAscensionEligibility(playerData, race.getId());
        return eligibility != null && eligibility.isEligible();
    }

    private record NodeStatus(String label, String color) {
        private static NodeStatus unlocked() {
            return new NodeStatus("Unlocked", "#7fa6cf");
        }

        private static NodeStatus available() {
            return new NodeStatus("Available", "#9adf86");
        }

        private static NodeStatus locked() {
            return new NodeStatus("Locked", "#a0522d");
        }
    }

    private PlayerData loadPlayerData() {
        if (playerDataManager == null || raceManager == null) {
            return null;
        }

        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(playerRef.getUuid(), playerRef.getUsername());
        }
        return data;
    }

    private String buildNodeLabel(RaceDefinition race, boolean includePathSuffix) {
        if (race == null) {
            return "";
        }

        String displayName = resolveDisplayName(race);
        if (!includePathSuffix) {
            return displayName;
        }

        RaceAscensionDefinition ascension = race.getAscension();
        String prettyPath = prettifyPathName(ascension != null ? ascension.getPath() : null);
        if (prettyPath.isBlank()) {
            return displayName;
        }
        return displayName + " (" + prettyPath + ")";
    }

    private String resolveDisplayName(RaceDefinition race) {
        if (race == null) {
            return PlayerData.DEFAULT_RACE_ID;
        }
        String display = race.getDisplayName();
        if (display == null || display.isBlank()) {
            return race.getId();
        }
        return display;
    }

    private String resolveIconItemId(RaceDefinition race) {
        if (race == null) {
            return RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }
        String icon = race.getIcon();
        if (icon == null || icon.isBlank()) {
            return RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }
        return icon;
    }

    private String prettifyPathName(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed)) {
            return "";
        }

        String[] pieces = trimmed.split("[_\\-\\s]+");
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            String lower = piece.toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                out.append(lower.substring(1));
            }
        }
        return out.toString();
    }

    private String connectorText(int upperCount, int lowerCount) {
        // Horizontal layout uses simple directional arrow
        return "→";
    }

    private record PathTierRow(String label, List<RaceDefinition> nodes, boolean baseTier, boolean finalTier) {
        private static PathTierRow base(List<RaceDefinition> nodes) {
            return new PathTierRow("Base Class", nodes == null ? List.of() : nodes, true, false);
        }

        private static PathTierRow tier(int tierNumber, List<RaceDefinition> nodes) {
            return new PathTierRow("Tier " + tierNumber, nodes == null ? List.of() : nodes, false, false);
        }

        private static PathTierRow finalTier(List<RaceDefinition> nodes) {
            return new PathTierRow("Final Tier", nodes == null ? List.of() : nodes, false, true);
        }
    }
}
