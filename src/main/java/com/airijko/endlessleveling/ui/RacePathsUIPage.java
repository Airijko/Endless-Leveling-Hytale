package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        PathViewData viewData = buildPathViewData();
        ui.set("#RacePathsTitleLabel.Text", viewData.titleRaceName + " Paths");
        ui.set("#RacePathBaseName.Text", viewData.baseName);
        ui.set("#RacePathBranchName1.Text", viewData.branch1Name);
        ui.set("#RacePathBranchName2.Text", viewData.branch2Name);
        ui.set("#RacePathTier2Name1.Text", viewData.tier2Name1);
        ui.set("#RacePathTier2Name2.Text", viewData.tier2Name2);
        ui.set("#RacePathFinalName.Text", viewData.finalName);
        ui.set("#RacePathBaseIcon.ItemId", viewData.baseIconItemId);
        ui.set("#RacePathBranchIcon1.ItemId", viewData.branch1IconItemId);
        ui.set("#RacePathBranchIcon2.ItemId", viewData.branch2IconItemId);
        ui.set("#RacePathTier2Icon1.ItemId", viewData.tier2IconItemId1);
        ui.set("#RacePathTier2Icon2.ItemId", viewData.tier2IconItemId2);
        ui.set("#RacePathFinalIcon.ItemId", viewData.finalIconItemId);
        ui.set("#RacePathBranchSlot1.Visible", viewData.branch1Visible);
        ui.set("#RacePathBranchSlot2.Visible", viewData.branch2Visible);
        ui.set("#RacePathTier2Slot1.Visible", viewData.tier2Visible1);
        ui.set("#RacePathTier2Slot2.Visible", viewData.tier2Visible2);
        ui.set("#RacePathBranchIcon1.Visible", viewData.branch1Visible);
        ui.set("#RacePathBranchIcon2.Visible", viewData.branch2Visible);
        ui.set("#RacePathTier2Icon1.Visible", viewData.tier2Visible1);
        ui.set("#RacePathTier2Icon2.Visible", viewData.tier2Visible2);
        ui.set("#RacePathTopConnectorRow.Visible", viewData.hasAnyBranch);
        ui.set("#RacePathBottomConnectorRow.Visible", viewData.hasAnyBranch);
        ui.set("#RacePathTier2ToFinalConnectorRow.Visible", viewData.hasAnyTier2 && viewData.hasFinal);
        ui.set("#RacePathFinalRow.Visible", viewData.hasFinal);
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

    private PathViewData buildPathViewData() {
        PathViewData viewData = new PathViewData();

        PlayerData data = loadPlayerData();
        RaceDefinition currentRace = data != null && raceManager != null
                ? raceManager.getPlayerRace(data)
                : null;

        if (currentRace == null) {
            viewData.titleRaceName = PlayerData.DEFAULT_RACE_ID;
            viewData.baseName = "Base";
            viewData.baseIconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.branch1Visible = false;
            viewData.branch2Visible = false;
            viewData.branch1Name = "";
            viewData.branch2Name = "";
            viewData.branch1IconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.branch2IconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.tier2Visible1 = false;
            viewData.tier2Visible2 = false;
            viewData.tier2Name1 = "";
            viewData.tier2Name2 = "";
            viewData.tier2IconItemId1 = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.tier2IconItemId2 = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.finalName = "Final";
            viewData.finalIconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
            viewData.hasAnyBranch = false;
            viewData.hasAnyTier2 = false;
            viewData.hasFinal = false;
            return viewData;
        }

        viewData.titleRaceName = resolveDisplayName(currentRace);
        viewData.baseName = resolveDisplayName(currentRace);
        viewData.baseIconItemId = resolveIconItemId(currentRace);

        List<RaceDefinition> branchCandidates = new ArrayList<>(raceManager.getNextAscensionRaces(currentRace.getId()));
        RaceDefinition branch1 = branchCandidates.size() > 0 ? branchCandidates.get(0) : null;
        RaceDefinition branch2 = branchCandidates.size() > 1 ? branchCandidates.get(1) : null;

        if (branch1 != null) {
            viewData.branch1Visible = true;
            viewData.branch1Name = buildPathSlotLabel(branch1);
            viewData.branch1IconItemId = resolveIconItemId(branch1);
        } else {
            viewData.branch1Visible = false;
            viewData.branch1Name = "";
            viewData.branch1IconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }

        if (branch2 != null) {
            viewData.branch2Visible = true;
            viewData.branch2Name = buildPathSlotLabel(branch2);
            viewData.branch2IconItemId = resolveIconItemId(branch2);
        } else {
            viewData.branch2Visible = false;
            viewData.branch2Name = "";
            viewData.branch2IconItemId = RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }
        viewData.hasAnyBranch = viewData.branch1Visible || viewData.branch2Visible;

        RaceDefinition tier2A = resolvePrimaryNextRace(branch1);
        if (tier2A != null) {
            viewData.tier2Visible1 = true;
            viewData.tier2Name1 = buildPathSlotLabel(tier2A);
            viewData.tier2IconItemId1 = resolveIconItemId(tier2A);
        } else {
            viewData.tier2Visible1 = false;
            viewData.tier2Name1 = "";
            viewData.tier2IconItemId1 = RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }

        RaceDefinition tier2B = resolvePrimaryNextRace(branch2);
        if (tier2B != null) {
            viewData.tier2Visible2 = true;
            viewData.tier2Name2 = buildPathSlotLabel(tier2B);
            viewData.tier2IconItemId2 = resolveIconItemId(tier2B);
        } else {
            viewData.tier2Visible2 = false;
            viewData.tier2Name2 = "";
            viewData.tier2IconItemId2 = RaceDefinition.DEFAULT_ICON_ITEM_ID;
        }
        viewData.hasAnyTier2 = viewData.tier2Visible1 || viewData.tier2Visible2;

        List<RaceDefinition> tier2Candidates = new ArrayList<>();
        if (tier2A != null) {
            tier2Candidates.add(tier2A);
        }
        if (tier2B != null && !tier2B.getId().equalsIgnoreCase(tier2A != null ? tier2A.getId() : "")) {
            tier2Candidates.add(tier2B);
        }

        RaceDefinition finalRace = resolveConvergedFinalRace(currentRace, branchCandidates, tier2Candidates);
        viewData.finalName = finalRace != null ? resolveDisplayName(finalRace) : "Final";
        viewData.finalIconItemId = resolveIconItemId(finalRace);
        viewData.hasFinal = finalRace != null;
        return viewData;
    }

    private RaceDefinition resolvePrimaryNextRace(RaceDefinition sourceRace) {
        if (raceManager == null || sourceRace == null) {
            return null;
        }
        List<RaceDefinition> next = raceManager.getNextAscensionRaces(sourceRace.getId());
        if (next == null || next.isEmpty()) {
            return null;
        }
        return next.get(0);
    }

    private RaceDefinition resolveConvergedFinalRace(RaceDefinition currentRace,
            List<RaceDefinition> branchCandidates,
            List<RaceDefinition> tier2Candidates) {
        if (raceManager == null || currentRace == null) {
            return null;
        }

        Map<String, Integer> candidateHits = new LinkedHashMap<>();
        Map<String, RaceDefinition> byKey = new LinkedHashMap<>();
        List<RaceDefinition> source = (tier2Candidates != null && !tier2Candidates.isEmpty())
                ? tier2Candidates
                : branchCandidates;

        for (RaceDefinition node : source) {
            if (node == null) {
                continue;
            }
            for (RaceDefinition branchNext : raceManager.getNextAscensionRaces(node.getId())) {
                String key = branchNext.getId().toLowerCase(Locale.ROOT);
                candidateHits.put(key, candidateHits.getOrDefault(key, 0) + 1);
                byKey.putIfAbsent(key, branchNext);
            }
        }

        if (!candidateHits.isEmpty()) {
            return candidateHits.entrySet().stream()
                    .sorted((a, b) -> {
                        int byCount = Integer.compare(b.getValue(), a.getValue());
                        if (byCount != 0) {
                            return byCount;
                        }
                        RaceDefinition raceA = byKey.get(a.getKey());
                        RaceDefinition raceB = byKey.get(b.getKey());
                        boolean finalA = raceA != null && raceA.getAscension().isFinalForm();
                        boolean finalB = raceB != null && raceB.getAscension().isFinalForm();
                        int byFinalFlag = Boolean.compare(finalB, finalA);
                        if (byFinalFlag != 0) {
                            return byFinalFlag;
                        }
                        return resolveDisplayName(raceA).compareToIgnoreCase(resolveDisplayName(raceB));
                    })
                    .map(entry -> byKey.get(entry.getKey()))
                    .filter(race -> race != null)
                    .findFirst()
                    .orElse(null);
        }

        List<RaceDefinition> directNext = raceManager.getNextAscensionRaces(currentRace.getId());
        if (directNext.isEmpty()) {
            return currentRace.getAscension().isFinalForm() ? currentRace : null;
        }

        return directNext.stream()
                .sorted((a, b) -> {
                    int byFinalFlag = Boolean.compare(b.getAscension().isFinalForm(), a.getAscension().isFinalForm());
                    if (byFinalFlag != 0) {
                        return byFinalFlag;
                    }
                    return resolveDisplayName(a).compareToIgnoreCase(resolveDisplayName(b));
                })
                .findFirst()
                .orElse(null);
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

    private String buildPathSlotLabel(RaceDefinition race) {
        if (race == null) {
            return "";
        }

        String displayName = resolveDisplayName(race);
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

        String[] pieces = trimmed.split("[_\\\\-\\\\s]+");
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

    private static final class PathViewData {
        String titleRaceName;
        String baseName;
        String baseIconItemId;
        boolean branch1Visible;
        boolean branch2Visible;
        boolean hasAnyBranch;
        boolean tier2Visible1;
        boolean tier2Visible2;
        boolean hasAnyTier2;
        boolean hasFinal;
        String branch1Name;
        String branch2Name;
        String branch1IconItemId;
        String branch2IconItemId;
        String tier2Name1;
        String tier2Name2;
        String tier2IconItemId1;
        String tier2IconItemId2;
        String finalName;
        String finalIconItemId;
    }
}
