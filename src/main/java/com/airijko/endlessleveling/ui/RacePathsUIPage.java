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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.MouseEntered;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.MouseExited;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Placeholder page for race ascension paths.
 */
public class RacePathsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final String TIER_ROW_TEMPLATE = "Pages/Races/RacePathTierRow.ui";
    private static final String NODE_CARD_TEMPLATE = "Pages/Races/RacePathNodeCard.ui";
    private static final int MAX_NODES_PER_TIER_ROW = 3;
    private static final int MAX_TIER_DEPTH = 12;
    private static final String ACTION_SELECT_PREFIX = "racepath:select:";
    private static final String ACTION_HOVER_PREFIX = "racepath:hover:";
    private static final String ACTION_HOVER_END = "racepath:hoverend";

    private final RaceManager raceManager;
    private final PlayerDataManager playerDataManager;
    private String selectedPathKey;
    private String hoveredPathKey;

    public RacePathsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.selectedPathKey = null;
        this.hoveredPathKey = null;
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

        if (selectedPathKey == null && currentRace != null) {
            selectedPathKey = pathKey(currentRace);
        }

        ui.clear("#RacePathRows");

        List<PathTierRow> rows = buildTierRows(currentRace);
        renderTierRows(ui, events, rows, data, currentRace);
        applyPathInfoPanel(ui, data, currentRace);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isBlank()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }

            String action = data.action.trim();
            boolean selectionChanged = false;
            boolean infoPanelChanged = false;

            if (action.startsWith(ACTION_SELECT_PREFIX)) {
                String key = normalizePathKey(action.substring(ACTION_SELECT_PREFIX.length()));
                if (key != null) {
                    selectedPathKey = key;
                    selectionChanged = true;
                    infoPanelChanged = true;
                }
            } else if (action.startsWith(ACTION_HOVER_PREFIX)) {
                String key = normalizePathKey(action.substring(ACTION_HOVER_PREFIX.length()));
                hoveredPathKey = key;
                infoPanelChanged = true;
            } else if (ACTION_HOVER_END.equals(action)) {
                if (hoveredPathKey != null) {
                    hoveredPathKey = null;
                    infoPanelChanged = true;
                }
            }

            if (selectionChanged || infoPanelChanged) {
                UICommandBuilder ui = new UICommandBuilder();
                UIEventBuilder eventBuilder = new UIEventBuilder();
                PlayerData playerData = loadPlayerData();
                RaceDefinition currentRace = playerData != null && raceManager != null
                        ? raceManager.getPlayerRace(playerData)
                        : null;

                if (selectionChanged) {
                    ui.clear("#RacePathRows");
                    List<PathTierRow> rows = buildTierRows(currentRace);
                    renderTierRows(ui, eventBuilder, rows, playerData, currentRace);
                }

                applyPathInfoPanel(ui, playerData, currentRace);
                sendUpdate(ui, eventBuilder, false);
            }
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

    private void renderTierRows(UICommandBuilder ui,
            UIEventBuilder events,
            List<PathTierRow> rows,
            PlayerData playerData,
            RaceDefinition currentPlayerRace) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PathTierRow row = rows.get(rowIndex);
            ui.append("#RacePathRows", TIER_ROW_TEMPLATE);
            String rowBase = "#RacePathRows[" + rowIndex + "]";

            ui.set(rowBase + " #TierLabel.Text", row.label());
            ui.set(rowBase + " #TierLabel.Style.TextColor", row.finalTier() ? "#ffe59f" : "#9fb6d3");

            renderTierCards(ui,
                    events,
                    rowBase + " #TierCards",
                    row.nodes(),
                    row.baseTier(),
                    row.finalTier(),
                    playerData,
                    currentPlayerRace);

            // Hide connectors in horizontal layout - the left-to-right flow is self-evident
            ui.set(rowBase + " #TierConnector.Visible", false);
        }
    }

    private void renderTierCards(UICommandBuilder ui,
            UIEventBuilder events,
            String cardsSelector,
            List<RaceDefinition> races,
            boolean baseTier,
            boolean finalTier,
            PlayerData playerData,
            RaceDefinition currentPlayerRace) {
        List<RaceDefinition> nodes = races == null ? List.of() : races;
        if (nodes.isEmpty()) {
            return;
        }

        int rowIndex = 0;
        int inRow = 0;
        for (RaceDefinition race : nodes) {
            if (inRow == 0) {
                ui.appendInline(cardsSelector, "Group { LayoutMode: Middle; Anchor: (Bottom: 0); }");
            }

            ui.append(cardsSelector + "[" + rowIndex + "]", NODE_CARD_TEMPLATE);
            String nodeBase = cardsSelector + "[" + rowIndex + "][" + inRow + "]";
            NodeStatus status = resolveNodeStatus(race, baseTier, playerData, currentPlayerRace);
            String key = pathKey(race);
            boolean selected = selectedPathKey != null && selectedPathKey.equals(key);
            String nodeLabel = buildNodeLabel(race, !baseTier);

            ui.set(nodeBase + " #NodeIcon.ItemId", resolveIconItemId(race));
            applyNodeNameVariant(ui, nodeBase, nodeLabel, status, selected, finalTier);
            applyNodeStatusVariant(ui, nodeBase, status);
            ui.set(nodeBase + " #NodeActiveBrightOverlay.Visible", status.isActive());
            ui.set(nodeBase + " #NodeLockedDimOverlay.Visible", status.isLocked());
            ui.set(nodeBase + " #NodeSelectedOutlineOverlay.Visible", selected);

            events.addEventBinding(Activating, nodeBase, of("Action", ACTION_SELECT_PREFIX + key), false);
            events.addEventBinding(MouseEntered, nodeBase, of("Action", ACTION_HOVER_PREFIX + key), false);
            events.addEventBinding(MouseExited, nodeBase, of("Action", ACTION_HOVER_END), false);

            inRow++;
            if (inRow >= MAX_NODES_PER_TIER_ROW) {
                inRow = 0;
                rowIndex++;
            }
        }
    }

    private void applyNodeNameVariant(UICommandBuilder ui,
            String nodeBase,
            String nodeLabel,
            NodeStatus status,
            boolean selected,
            boolean finalTier) {
        String variant = resolveNodeNameVariant(status, selected, finalTier);

        ui.set(nodeBase + " #NodeNameDefault.Text", nodeLabel);
        ui.set(nodeBase + " #NodeNameSelected.Text", nodeLabel);
        ui.set(nodeBase + " #NodeNameFinal.Text", nodeLabel);
        ui.set(nodeBase + " #NodeNameFinalSelected.Text", nodeLabel);
        ui.set(nodeBase + " #NodeNameLocked.Text", nodeLabel);
        ui.set(nodeBase + " #NodeNameActive.Text", nodeLabel);

        ui.set(nodeBase + " #NodeNameDefault.Visible", "default".equals(variant));
        ui.set(nodeBase + " #NodeNameSelected.Visible", "selected".equals(variant));
        ui.set(nodeBase + " #NodeNameFinal.Visible", "final".equals(variant));
        ui.set(nodeBase + " #NodeNameFinalSelected.Visible", "final_selected".equals(variant));
        ui.set(nodeBase + " #NodeNameLocked.Visible", "locked".equals(variant));
        ui.set(nodeBase + " #NodeNameActive.Visible", "active".equals(variant));
    }

    private String resolveNodeNameVariant(NodeStatus status, boolean selected, boolean finalTier) {
        if (status.isLocked()) {
            return "locked";
        }
        if (status.isActive()) {
            return "active";
        }
        if (finalTier && selected) {
            return "final_selected";
        }
        if (finalTier) {
            return "final";
        }
        if (selected) {
            return "selected";
        }
        return "default";
    }

    private void applyNodeStatusVariant(UICommandBuilder ui, String nodeBase, NodeStatus status) {
        ui.set(nodeBase + " #NodeStatusActive.Visible", status.isActive());
        ui.set(nodeBase + " #NodeStatusUnlocked.Visible", status.isUnlocked());
        ui.set(nodeBase + " #NodeStatusAvailable.Visible", status.isAvailable());
        ui.set(nodeBase + " #NodeStatusLocked.Visible", status.isLocked());
    }

    private void applyPathInfoPanel(UICommandBuilder ui, PlayerData playerData, RaceDefinition currentPlayerRace) {
        RaceDefinition focused = resolveFocusedRace(currentPlayerRace);
        if (focused == null) {
            ui.set("#PathInfoIcon.ItemId", RaceDefinition.DEFAULT_ICON_ITEM_ID);
            ui.set("#PathInfoName.Text", "Select a path");
            ui.set("#PathInfoSubtitle.Text", "Hover or click a node to inspect it.");
            ui.set("#PathInfoStatus.Text", "Waiting for selection");
            ui.set("#PathInfoStatus.Style.TextColor", "#9fb6d3");
            ui.set("#PathInfoPath.Text", "Path: -");
            ui.set("#PathInfoStage.Text", "Stage: -");
            ui.set("#PathInfoSource.Text", "Source: -");
            ui.set("#PathInfoRequirements.Text", "Hover or select a race path to inspect requirements.");
            return;
        }

        String focusedKey = pathKey(focused);
        boolean focusFromHover = hoveredPathKey != null && hoveredPathKey.equals(focusedKey);
        boolean focusFromSelection = selectedPathKey != null && selectedPathKey.equals(focusedKey);
        boolean baseTier = currentPlayerRace != null && pathKey(currentPlayerRace).equals(focusedKey);

        NodeStatus status = resolveNodeStatus(focused, baseTier, playerData, currentPlayerRace);
        RaceAscensionEligibility eligibility = (playerData != null && raceManager != null)
                ? raceManager.evaluateAscensionEligibility(playerData, focused.getId())
                : null;

        RaceAscensionDefinition ascension = focused.getAscension();
        String pathLabel = prettifyPathName(ascension != null ? ascension.getPath() : null);
        if (pathLabel.isBlank()) {
            pathLabel = "None";
        }

        String stageLabel = prettifyPathName(ascension != null ? ascension.getStage() : null);
        if (stageLabel.isBlank()) {
            stageLabel = "Base";
        }
        if (ascension != null && ascension.isFinalForm()) {
            stageLabel += " (Final)";
        }

        String subtitle = focusFromHover
                ? "Hover preview"
                : (focusFromSelection ? "Selected path" : "Current path");

        ui.set("#PathInfoIcon.ItemId", resolveIconItemId(focused));
        ui.set("#PathInfoName.Text", resolveDisplayName(focused));
        ui.set("#PathInfoSubtitle.Text", subtitle);
        ui.set("#PathInfoStatus.Text", status.label());
        ui.set("#PathInfoStatus.Style.TextColor", status.color());
        ui.set("#PathInfoPath.Text", "Path: " + pathLabel);
        ui.set("#PathInfoStage.Text", "Stage: " + stageLabel);
        ui.set("#PathInfoSource.Text", resolveSourceLabel(focused));
        ui.set("#PathInfoRequirements.Text", buildRequirementsText(status, baseTier, eligibility));
    }

    private RaceDefinition resolveFocusedRace(RaceDefinition currentPlayerRace) {
        if (raceManager == null) {
            return null;
        }

        RaceDefinition hovered = findRaceByPathKey(hoveredPathKey);
        if (hovered != null) {
            return hovered;
        }

        RaceDefinition selected = findRaceByPathKey(selectedPathKey);
        if (selected != null) {
            return selected;
        }

        return currentPlayerRace;
    }

    private RaceDefinition findRaceByPathKey(String key) {
        String normalized = normalizePathKey(key);
        if (normalized == null || raceManager == null) {
            return null;
        }

        for (RaceDefinition candidate : raceManager.getLoadedRaces()) {
            if (candidate == null) {
                continue;
            }
            if (normalized.equals(pathKey(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizePathKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String resolveSourceLabel(RaceDefinition race) {
        if (race == null || raceManager == null) {
            return "Source: Unknown";
        }

        String targetKey = pathKey(race);
        List<String> sources = new ArrayList<>();
        for (RaceDefinition candidate : raceManager.getLoadedRaces()) {
            if (candidate == null) {
                continue;
            }
            for (RaceDefinition child : raceManager.getNextAscensionRaces(candidate.getId())) {
                if (child != null && targetKey.equals(pathKey(child))) {
                    sources.add(resolveDisplayName(candidate));
                    break;
                }
            }
        }

        if (sources.isEmpty()) {
            return "Source: Base progression";
        }

        Collections.sort(sources);
        return "Source: " + String.join(", ", sources);
    }

    private String buildRequirementsText(NodeStatus status,
            boolean baseTier,
            RaceAscensionEligibility eligibility) {
        if ("Active".equalsIgnoreCase(status.label())) {
            return "This is your currently active race form.";
        }

        if (baseTier) {
            return "This is your base form for this race path.";
        }

        if ("Unlocked".equalsIgnoreCase(status.label())) {
            return "Already unlocked on your account.";
        }

        if ("Available".equalsIgnoreCase(status.label())) {
            return "All requirements met. You can ascend into this form.";
        }

        if (eligibility == null || eligibility.getBlockers().isEmpty()) {
            return "Requirements not currently met.";
        }

        List<String> blockers = eligibility.getBlockers();
        int shown = Math.min(4, blockers.size());
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                summary.append("\n");
            }
            summary.append("- ").append(blockers.get(i));
        }
        if (blockers.size() > shown) {
            summary.append("\n- +").append(blockers.size() - shown).append(" more requirement(s)");
        }
        return summary.toString();
    }

    private NodeStatus resolveNodeStatus(RaceDefinition race,
            boolean baseTier,
            PlayerData playerData,
            RaceDefinition currentPlayerRace) {
        if (isActiveRace(race, currentPlayerRace)) {
            return NodeStatus.active();
        }

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

    private boolean isActiveRace(RaceDefinition race, RaceDefinition currentPlayerRace) {
        if (race == null || currentPlayerRace == null) {
            return false;
        }
        return pathKey(race).equals(pathKey(currentPlayerRace));
    }

    private boolean isRaceAvailable(RaceDefinition race, PlayerData playerData) {
        if (race == null || playerData == null || raceManager == null) {
            return false;
        }
        RaceAscensionEligibility eligibility = raceManager.evaluateAscensionEligibility(playerData, race.getId());
        return eligibility != null && eligibility.isEligible();
    }

    private record NodeStatus(String label, String color) {
        private boolean isUnlocked() {
            return "Unlocked".equalsIgnoreCase(label);
        }

        private boolean isAvailable() {
            return "Available".equalsIgnoreCase(label);
        }

        private boolean isLocked() {
            return "Locked".equalsIgnoreCase(label);
        }

        private boolean isActive() {
            return "Active".equalsIgnoreCase(label);
        }

        private static NodeStatus active() {
            return new NodeStatus("Active", "#9cd8ff");
        }

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
            return new PathTierRow("Base", nodes == null ? List.of() : nodes, true, false);
        }

        private static PathTierRow tier(int tierNumber, List<RaceDefinition> nodes) {
            return new PathTierRow("Tier " + tierNumber, nodes == null ? List.of() : nodes, false, false);
        }

        private static PathTierRow finalTier(List<RaceDefinition> nodes) {
            return new PathTierRow("Final Tier", nodes == null ? List.of() : nodes, false, true);
        }
    }
}
