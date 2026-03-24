package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.airijko.endlessleveling.classes.WeaponConfig;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page for class ascension paths.
 */
public class ClassPathsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final String TIER_ROW_TEMPLATE = "Pages/Classes/ClassPathTierRow.ui";
    private static final String NODE_CARD_TEMPLATE = "Pages/Classes/ClassPathNodeCard.ui";
    private static final int MAX_NODES_PER_TIER_ROW = 3;
    private static final int MAX_TIER_DEPTH = 12;
    private static final String ACTION_SELECT_PREFIX = "classpath:select:";
    private static final String ACTION_PATH_BUTTON = "classpath:action";
    private static final String NODE_OUTLINE_DEFAULT_COLOR = "#25384b";
    private static final String NODE_OUTLINE_SELECTED_COLOR = "#f0cf78";

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;
    private String browsedClassId;
    private String selectedPathKey;

    public ClassPathsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        this(playerRef, lifetime, null);
    }

    public ClassPathsUIPage(@Nonnull PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime,
            String initialClassId) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.classManager = plugin != null ? plugin.getClassManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.browsedClassId = initialClassId;
        this.selectedPathKey = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Classes/ClassPathsPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef, "classes",
            "Common/UI/Custom/Pages/Classes/ClassPathsPage.ui",
            "#ClassPathsTitle");
        NavUIHelper.bindNavEvents(events);

        PlayerData data = loadPlayerData();
        CharacterClassDefinition currentClass = data != null && classManager != null
                ? classManager.getPlayerPrimaryClass(data)
                : null;
        CharacterClassDefinition browsedClass = resolveBrowsedClass(currentClass);

        String titleClass = browsedClass != null ? resolveDisplayName(browsedClass)
                : PlayerData.DEFAULT_PRIMARY_CLASS_ID;
        ui.set("#ClassPathsTitleLabel.Text", titleClass + " Paths");

        if (selectedPathKey == null && browsedClass != null) {
            selectedPathKey = pathKey(browsedClass);
        }

        ui.clear("#ClassPathRows");

        CharacterClassDefinition pathTreeRoot = resolvePathTreeRoot(browsedClass);
        List<PathTierRow> rows = buildTierRows(pathTreeRoot);
        renderTierRows(ui, events, rows, data, currentClass);
        applyPathInfoPanel(ui, data, currentClass, browsedClass);
        events.addEventBinding(Activating, "#ChooseClassPathButton", of("Action", ACTION_PATH_BUTTON), false);
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

            if (ACTION_PATH_BUTTON.equals(action)) {
                handlePathAction(ref, store);
                return;
            }

            if (action.startsWith(ACTION_SELECT_PREFIX)) {
                String key = normalizePathKey(action.substring(ACTION_SELECT_PREFIX.length()));
                if (key != null) {
                    selectedPathKey = key;
                    selectionChanged = true;
                }
            }

            if (selectionChanged) {
                UICommandBuilder ui = new UICommandBuilder();
                UIEventBuilder eventBuilder = new UIEventBuilder();
                PlayerData playerData = loadPlayerData();
                CharacterClassDefinition currentClass = playerData != null && classManager != null
                        ? classManager.getPlayerPrimaryClass(playerData)
                        : null;
                CharacterClassDefinition browsedClass = resolveBrowsedClass(currentClass);

                if (selectionChanged) {
                    ui.clear("#ClassPathRows");
                    CharacterClassDefinition pathTreeRoot = resolvePathTreeRoot(browsedClass);
                    List<PathTierRow> rows = buildTierRows(pathTreeRoot);
                    renderTierRows(ui, eventBuilder, rows, playerData, currentClass);
                }

                applyPathInfoPanel(ui, playerData, currentClass, browsedClass);
                sendUpdate(ui, eventBuilder, false);
            }
        }
    }

    private void handlePathAction(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        PlayerData playerData = loadPlayerData();
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("Unable to load your class info right now.").color("#ff6666"));
            return;
        }

        if (classManager == null || !classManager.isEnabled()) {
            playerRef.sendMessage(Message.raw("Classes are disabled.").color("#ff6666"));
            return;
        }

        CharacterClassDefinition currentClass = classManager.getPlayerPrimaryClass(playerData);
        CharacterClassDefinition browsedClass = resolveBrowsedClass(currentClass);
        CharacterClassDefinition focusedClass = resolveFocusedClass(currentClass, browsedClass);
        if (focusedClass == null) {
            playerRef.sendMessage(Message.raw("Select a class path first.").color("#ff9900"));
            return;
        }

        boolean baseTier = browsedClass != null && pathKey(browsedClass).equals(pathKey(focusedClass));
        NodeStatus status = resolveNodeStatus(focusedClass, baseTier, playerData, currentClass);

        if (status.isActive()) {
            playerRef.sendMessage(Message.raw("That class form is already active.").color("#ff9900"));
            return;
        }

        if (status.isLocked()) {
            RaceAscensionEligibility eligibility = classManager.evaluateAscensionEligibility(playerData,
                    focusedClass.getId());
            if (eligibility != null && !eligibility.getBlockers().isEmpty()) {
                playerRef.sendMessage(Message.raw("Cannot upgrade yet:").color("#ff6666"));
                for (String blocker : eligibility.getBlockers()) {
                    playerRef.sendMessage(Message.join(
                            Message.raw(" - ").color("#ff6666"),
                            Message.raw(blocker).color("#ffc300")));
                }
            } else {
                playerRef.sendMessage(Message.raw("That class path is currently locked.").color("#ff6666"));
            }
            return;
        }

        if (status.isAvailable()) {
            RaceAscensionEligibility eligibility = classManager.evaluateAscensionEligibility(playerData,
                    focusedClass.getId());
            if (eligibility == null || !eligibility.isEligible()) {
                playerRef.sendMessage(Message.raw("Cannot upgrade yet.").color("#ff6666"));
                if (eligibility != null) {
                    for (String blocker : eligibility.getBlockers()) {
                        playerRef.sendMessage(Message.join(
                                Message.raw(" - ").color("#ff6666"),
                                Message.raw(blocker).color("#ffc300")));
                    }
                }
                return;
            }
        }

        CharacterClassDefinition previousClass = currentClass;
        if (previousClass != null) {
            playerData.addCompletedClassForm(classManager.resolveAscensionPathId(previousClass.getId()));
        }

        playerData.setPrimaryClassId(focusedClass.getId());
        playerData.addCompletedClassForm(classManager.resolveAscensionPathId(focusedClass.getId()));

        if (playerDataManager != null) {
            playerDataManager.save(playerData);
        }

        var skillManager = EndlessLeveling.getInstance().getSkillManager();
        boolean applied = false;
        if (skillManager != null) {
            applied = skillManager.applyAllSkillModifiers(ref, store, playerData);
        }
        if (!applied) {
            var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(playerData.getUuid());
            }
        }

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(playerData);
        }

        var player = Universe.get().getPlayer(playerRef.getUuid());
        if (player != null) {
            String display = focusedClass.getDisplayName() == null ? focusedClass.getId()
                    : focusedClass.getDisplayName();
            String verb = status.isAvailable() ? "advanced to" : "switched to";
            player.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#4fd7f7"),
                    Message.raw("You " + verb + " ").color("#ffffff"),
                    Message.raw(display).color("#ffc300"),
                    Message.raw("!").color("#ffffff")));
        }

        browsedClassId = focusedClass.getId();
        selectedPathKey = pathKey(focusedClass);
        rebuild();
    }

    private CharacterClassDefinition resolveBrowsedClass(CharacterClassDefinition currentClass) {
        if (classManager == null) {
            return currentClass;
        }
        if (browsedClassId != null && !browsedClassId.isBlank()) {
            CharacterClassDefinition browsed = classManager.findClassByUserInput(browsedClassId);
            if (browsed == null) {
                browsed = classManager.getClass(browsedClassId);
            }
            if (browsed != null) {
                return browsed;
            }
        }
        return currentClass;
    }

    private List<PathTierRow> buildTierRows(CharacterClassDefinition rootClass) {
        List<PathTierRow> rows = new ArrayList<>();
        if (rootClass == null) {
            rows.add(PathTierRow.base(List.of()));
            return rows;
        }

        rows.add(PathTierRow.base(List.of(rootClass)));

        Set<String> seen = new LinkedHashSet<>();
        seen.add(pathKey(rootClass));

        List<CharacterClassDefinition> frontier = collectUniqueChildren(List.of(rootClass), seen);
        List<CharacterClassDefinition> finalTier = new ArrayList<>();
        Set<String> finalSeen = new LinkedHashSet<>();

        int tierNumber = 1;
        int depth = 0;
        while (!frontier.isEmpty() && depth < MAX_TIER_DEPTH) {
            depth++;

            List<CharacterClassDefinition> nonFinalTier = new ArrayList<>();
            for (CharacterClassDefinition candidate : frontier) {
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
                rows.add(PathTierRow.tier(resolveTierLabel(nonFinalTier, tierNumber), nonFinalTier));
                tierNumber++;
                frontier = collectUniqueChildren(nonFinalTier, seen);
            } else {
                frontier = List.of();
            }
        }

        if (!finalTier.isEmpty()) {
            rows.add(PathTierRow.finalTier(resolveTierLabel(finalTier, tierNumber), finalTier));
        }

        return rows;
    }

    private CharacterClassDefinition resolvePathTreeRoot(CharacterClassDefinition currentClass) {
        if (currentClass == null || classManager == null) {
            return currentClass;
        }

        CharacterClassDefinition baseClass = resolveAscensionBaseClass(currentClass);
        if (baseClass == null || baseClass.getAscension() == null) {
            return currentClass;
        }

        if (baseClass.getAscension().isSingleRouteOnly()) {
            return currentClass;
        }

        return baseClass;
    }

    private CharacterClassDefinition resolveAscensionBaseClass(CharacterClassDefinition clazz) {
        if (clazz == null || classManager == null) {
            return clazz;
        }

        CharacterClassDefinition current = clazz;
        Set<String> visited = new LinkedHashSet<>();
        int depth = 0;
        while (current != null && depth < MAX_TIER_DEPTH) {
            String currentKey = pathKey(current);
            if (!visited.add(currentKey)) {
                break;
            }

            CharacterClassDefinition parent = findAscensionParent(current);
            if (parent == null) {
                return current;
            }

            current = parent;
            depth++;
        }

        return clazz;
    }

    private CharacterClassDefinition findAscensionParent(CharacterClassDefinition childClass) {
        if (childClass == null || classManager == null) {
            return null;
        }

        String childKey = pathKey(childClass);
        CharacterClassDefinition bestParent = null;
        for (CharacterClassDefinition candidate : classManager.getLoadedClasses()) {
            if (candidate == null) {
                continue;
            }

            for (CharacterClassDefinition child : classManager.getNextAscensionClasses(candidate.getId())) {
                if (child == null) {
                    continue;
                }
                if (!childKey.equals(pathKey(child))) {
                    continue;
                }

                if (bestParent == null || candidate.getId().compareToIgnoreCase(bestParent.getId()) < 0) {
                    bestParent = candidate;
                }
                break;
            }
        }

        return bestParent;
    }

    private List<CharacterClassDefinition> collectUniqueChildren(List<CharacterClassDefinition> parents,
            Set<String> seen) {
        if (classManager == null || parents == null || parents.isEmpty()) {
            return List.of();
        }

        List<CharacterClassDefinition> result = new ArrayList<>();
        for (CharacterClassDefinition parent : parents) {
            if (parent == null) {
                continue;
            }
            for (CharacterClassDefinition child : classManager.getNextAscensionClasses(parent.getId())) {
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

    private String pathKey(CharacterClassDefinition clazz) {
        if (clazz == null) {
            return "";
        }
        RaceAscensionDefinition asc = clazz.getAscension();
        String key = asc != null && asc.getId() != null && !asc.getId().isBlank() ? asc.getId() : clazz.getId();
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private void renderTierRows(UICommandBuilder ui,
            UIEventBuilder events,
            List<PathTierRow> rows,
            PlayerData playerData,
            CharacterClassDefinition currentPlayerClass) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            PathTierRow row = rows.get(rowIndex);
            ui.append("#ClassPathRows", TIER_ROW_TEMPLATE);
            String rowBase = "#ClassPathRows[" + rowIndex + "]";

            ui.set(rowBase + " #TierLabel.Text", row.label());
            ui.set(rowBase + " #TierLabel.Style.TextColor", row.finalTier() ? "#ffe59f" : "#9fb6d3");

            renderTierCards(ui,
                    events,
                    rowBase + " #TierCards",
                    row.nodes(),
                    row.baseTier(),
                    row.finalTier(),
                    playerData,
                    currentPlayerClass);

            // Hide connectors in horizontal layout - the left-to-right flow is self-evident
            ui.set(rowBase + " #TierConnector.Visible", false);
        }
    }

    private void renderTierCards(UICommandBuilder ui,
            UIEventBuilder events,
            String cardsSelector,
            List<CharacterClassDefinition> classes,
            boolean baseTier,
            boolean finalTier,
            PlayerData playerData,
            CharacterClassDefinition currentPlayerClass) {
        List<CharacterClassDefinition> nodes = classes == null ? List.of() : classes;
        if (nodes.isEmpty()) {
            return;
        }

        int rowIndex = 0;
        int inRow = 0;
        for (CharacterClassDefinition clazz : nodes) {
            if (inRow == 0) {
                ui.appendInline(cardsSelector, "Group { LayoutMode: Middle; Anchor: (Bottom: 0); }");
            }

            ui.append(cardsSelector + "[" + rowIndex + "]", NODE_CARD_TEMPLATE);
            String nodeBase = cardsSelector + "[" + rowIndex + "][" + inRow + "]";
            NodeStatus status = resolveNodeStatus(clazz, baseTier, playerData, currentPlayerClass);
            String key = pathKey(clazz);
            boolean selected = selectedPathKey != null && selectedPathKey.equals(key);
            String nodeLabel = buildNodeLabel(clazz);

            ui.set(nodeBase + " #NodeIcon.ItemId", resolveIconItemId(clazz));
            applyNodeNameVariant(ui, nodeBase, nodeLabel, status, selected, finalTier);
            applyNodeStatusVariant(ui, nodeBase, status);
            applyNodeBackgroundVariant(ui, nodeBase, status);
            applyNodeOutlineVariant(ui, nodeBase, selected);
            ui.set(nodeBase + " #NodeSelectedOutlineOverlay.Visible", false);

            events.addEventBinding(Activating, nodeBase, of("Action", ACTION_SELECT_PREFIX + key), false);

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

    private void applyNodeBackgroundVariant(UICommandBuilder ui, String nodeBase, NodeStatus status) {
        ui.set(nodeBase + " #NodeBackgroundActive.Visible", status.isActive());
        ui.set(nodeBase + " #NodeBackgroundUnlocked.Visible", status.isUnlocked());
        ui.set(nodeBase + " #NodeBackgroundAvailable.Visible", status.isAvailable());
        ui.set(nodeBase + " #NodeBackgroundLocked.Visible", status.isLocked());
    }

    private void applyNodeOutlineVariant(UICommandBuilder ui, String nodeBase, boolean selected) {
        String outlineColor = selected ? NODE_OUTLINE_SELECTED_COLOR : NODE_OUTLINE_DEFAULT_COLOR;
        ui.set(nodeBase + " #NodeOutlineTop.Background", outlineColor);
        ui.set(nodeBase + " #NodeOutlineLeft.Background", outlineColor);
        ui.set(nodeBase + " #NodeOutlineRight.Background", outlineColor);
        ui.set(nodeBase + " #NodeOutlineBottom.Background", outlineColor);
    }

    private void applyPathInfoPanel(UICommandBuilder ui,
            PlayerData playerData,
            CharacterClassDefinition currentPlayerClass,
            CharacterClassDefinition browsedClass) {
        CharacterClassDefinition focused = resolveFocusedClass(currentPlayerClass, browsedClass);
        if (focused == null) {
            ui.set("#PathInfoIcon.ItemId", "Ingredient_Life_Essence");
            ui.set("#PathInfoName.Text", "Select a path");
            ui.set("#PathInfoStatus.Text", "Waiting for selection");
            ui.set("#PathInfoStatus.Style.TextColor", "#9fb6d3");
            ui.set("#PathInfoPath.Text", "Path: -");
            ui.set("#PathInfoStage.Text", "Stage: -");
            ui.set("#PathInfoSource.Text", "Source: -");
            ui.set("#PathInfoWeapons.Text", "Select a path to view weapon bonuses.");
            ui.set("#PathInfoPassives.Text", "Select a path to view passives.");
            ui.set("#PathInfoRequirements.Text", "Select a class path to inspect requirements.");
            ui.set("#ChooseClassPathButton.Text", "SELECT PATH");
            return;
        }

        String focusedKey = pathKey(focused);
        boolean baseTier = browsedClass != null && pathKey(browsedClass).equals(focusedKey);

        NodeStatus status = resolveNodeStatus(focused, baseTier, playerData, currentPlayerClass);
        RaceAscensionEligibility eligibility = (playerData != null && classManager != null)
                ? classManager.evaluateAscensionEligibility(playerData, focused.getId())
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

        ui.set("#PathInfoIcon.ItemId", resolveIconItemId(focused));
        ui.set("#PathInfoName.Text", resolveDisplayName(focused));
        ui.set("#PathInfoStatus.Text", status.label());
        ui.set("#PathInfoStatus.Style.TextColor", status.color());
        ui.set("#PathInfoPath.Text", "Path: " + pathLabel);
        ui.set("#PathInfoStage.Text", "Stage: " + stageLabel);
        ui.set("#PathInfoSource.Text", resolveSourceLabel(focused));
        ui.set("#PathInfoWeapons.Text", buildWeaponsText(focused));
        ui.set("#PathInfoPassives.Text", buildPassivesText(focused));
        ui.set("#PathInfoRequirements.Text", buildRequirementsText(status, baseTier, eligibility, focused));
        ui.set("#ChooseClassPathButton.Text", resolvePathActionButtonText(status));
    }

    private String buildWeaponsText(CharacterClassDefinition clazz) {
        if (clazz == null) {
            return "No weapon bonuses listed.";
        }

        Map<String, Double> weaponMultipliers = clazz.getWeaponMultipliers();
        if (weaponMultipliers == null || weaponMultipliers.isEmpty()) {
            return "No weapon bonuses listed.";
        }

        List<String> parts = new ArrayList<>();
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weaponMultipliers.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        for (Map.Entry<String, Double> entry : entries) {
            String normalized = WeaponConfig.normalizeCategoryKey(entry.getKey());
            if (normalized == null) {
                continue;
            }
            double multiplier = entry.getValue() == null ? 1.0D : entry.getValue();
            double percent = (multiplier - 1.0D) * 100.0D;
            String sign = percent >= 0.0D ? "+" : "";
            parts.add(prettifyPathName(normalized) + ": " + sign + formatNumber(percent) + "%");
        }

        if (parts.isEmpty()) {
            return "No weapon bonuses listed.";
        }
        return String.join("\n", parts);
    }

    private String buildPassivesText(CharacterClassDefinition clazz) {
        if (clazz == null || clazz.getPassiveDefinitions() == null || clazz.getPassiveDefinitions().isEmpty()) {
            return "No passives listed.";
        }

        List<String> labels = new ArrayList<>();
        for (RacePassiveDefinition passive : clazz.getPassiveDefinitions()) {
            if (passive == null || passive.type() == null) {
                continue;
            }

            String label = prettifyPathName(passive.type().name());
            if (passive.attributeType() != null) {
                label += " (" + prettifyPathName(passive.attributeType().getConfigKey()) + ")";
            }
            labels.add("- " + label);
        }

        if (labels.isEmpty()) {
            return "No passives listed.";
        }
        return String.join("\n", labels);
    }

    private String resolvePathActionButtonText(NodeStatus status) {
        if (status == null) {
            return "SELECT PATH";
        }
        if (status.isActive()) {
            return "CURRENT";
        }
        if (status.isAvailable()) {
            return "UPGRADE";
        }
        if (status.isUnlocked()) {
            return "CHOOSE";
        }
        if (status.isLocked()) {
            return "LOCKED";
        }
        return "SELECT PATH";
    }

    private CharacterClassDefinition resolveFocusedClass(CharacterClassDefinition currentPlayerClass,
            CharacterClassDefinition browsedClass) {
        if (classManager == null) {
            return null;
        }

        CharacterClassDefinition selected = findClassByPathKey(selectedPathKey);
        if (selected != null) {
            return selected;
        }

        if (browsedClass != null) {
            return browsedClass;
        }

        return currentPlayerClass;
    }

    private CharacterClassDefinition findClassByPathKey(String key) {
        String normalized = normalizePathKey(key);
        if (normalized == null || classManager == null) {
            return null;
        }

        for (CharacterClassDefinition candidate : classManager.getLoadedClasses()) {
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

    private String resolveSourceLabel(CharacterClassDefinition clazz) {
        if (clazz == null || classManager == null) {
            return "Source: Unknown";
        }

        String targetKey = pathKey(clazz);
        List<String> sources = new ArrayList<>();
        for (CharacterClassDefinition candidate : classManager.getLoadedClasses()) {
            if (candidate == null) {
                continue;
            }
            for (CharacterClassDefinition child : classManager.getNextAscensionClasses(candidate.getId())) {
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
            RaceAscensionEligibility eligibility,
            CharacterClassDefinition focusedClass) {
        if ("Active".equalsIgnoreCase(status.label())) {
            return "This is your currently active class form.";
        }

        if (baseTier) {
            return "This is your base form for this class path.";
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
            summary.append("- ").append(normalizeAnySkillSetBlocker(blockers.get(i), focusedClass));
        }
        if (blockers.size() > shown) {
            summary.append("\n- +").append(blockers.size() - shown).append(" more requirement(s)");
        }
        return summary.toString();
    }

    private String normalizeAnySkillSetBlocker(String blocker, CharacterClassDefinition focusedClass) {
        if (blocker == null || blocker.isBlank()) {
            return blocker;
        }
        if (!isAnySkillSetBlocker(blocker)) {
            return blocker;
        }

        String options = buildAnySkillOptions(focusedClass);
        if (options.isBlank()) {
            return blocker;
        }
        return "Requires at least one skill option set to be met: " + options + ".";
    }

    private boolean isAnySkillSetBlocker(String blocker) {
        if (blocker == null || blocker.isBlank()) {
            return false;
        }
        String normalized = blocker.toLowerCase(Locale.ROOT);
        return normalized.contains("at least one or skill requirement set")
                || normalized.contains("at least one skill requirement set")
                || normalized.contains("any one skill requirement set")
                || normalized.contains("one or skill requirement set");
    }

    private String buildAnySkillOptions(CharacterClassDefinition focusedClass) {
        RaceAscensionDefinition ascension = focusedClass == null ? null : focusedClass.getAscension();
        RaceAscensionRequirements requirements = ascension == null
                ? RaceAscensionRequirements.none()
                : ascension.getRequirements();

        List<String> groups = new ArrayList<>();
        for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            List<String> rendered = new ArrayList<>();
            for (Map.Entry<SkillAttributeType, Integer> entry : group.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                rendered.add(prettifyPathName(entry.getKey().getConfigKey()) + " >= " + entry.getValue());
            }
            if (!rendered.isEmpty()) {
                groups.add(String.join(", ", rendered));
            }
        }

        return String.join(" OR ", groups);
    }

    private NodeStatus resolveNodeStatus(CharacterClassDefinition clazz,
            boolean baseTier,
            PlayerData playerData,
            CharacterClassDefinition currentPlayerClass) {
        if (isActiveClass(clazz, currentPlayerClass)) {
            return NodeStatus.active();
        }

        if (baseTier || isClassUnlocked(clazz, currentPlayerClass, playerData)) {
            return NodeStatus.unlocked();
        }
        if (isClassAvailable(clazz, playerData)) {
            return NodeStatus.available();
        }
        return NodeStatus.locked();
    }

    private boolean isClassUnlocked(CharacterClassDefinition clazz, CharacterClassDefinition currentPlayerClass,
            PlayerData playerData) {
        if (clazz == null) {
            return false;
        }
        String classKey = pathKey(clazz);

        if (currentPlayerClass != null) {
            String currentKey = pathKey(currentPlayerClass);
            if (classKey.equals(currentKey)) {
                return true;
            }
        }

        if (playerData == null || classManager == null) {
            return false;
        }

        String pathId = classManager.resolveAscensionPathId(clazz.getId());
        if (pathId != null && playerData.hasCompletedClassForm(pathId)) {
            return true;
        }

        return playerData.hasCompletedClassForm(clazz.getId());
    }

    private boolean isActiveClass(CharacterClassDefinition clazz, CharacterClassDefinition currentPlayerClass) {
        if (clazz == null || currentPlayerClass == null) {
            return false;
        }
        return pathKey(clazz).equals(pathKey(currentPlayerClass));
    }

    private boolean isClassAvailable(CharacterClassDefinition clazz, PlayerData playerData) {
        if (clazz == null || playerData == null || classManager == null) {
            return false;
        }
        RaceAscensionEligibility eligibility = classManager.evaluateAscensionEligibility(playerData, clazz.getId());
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
        if (playerDataManager == null || classManager == null) {
            return null;
        }

        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(playerRef.getUuid(), playerRef.getUsername());
        }
        return data;
    }

    private String buildNodeLabel(CharacterClassDefinition clazz) {
        if (clazz == null) {
            return "";
        }

        return resolveDisplayName(clazz);
    }

    private String resolveDisplayName(CharacterClassDefinition clazz) {
        if (clazz == null) {
            return PlayerData.DEFAULT_PRIMARY_CLASS_ID;
        }
        String display = clazz.getDisplayName();
        if (display == null || display.isBlank()) {
            return clazz.getId();
        }
        return display;
    }

    private String resolveIconItemId(CharacterClassDefinition clazz) {
        if (clazz == null) {
            return "Ingredient_Life_Essence";
        }
        String icon = clazz.getIconItemId();
        if (icon == null || icon.isBlank()) {
            return "Ingredient_Life_Essence";
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

    private String formatNumber(double value) {
        String formatted = String.format(Locale.US, "%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    private String resolveTierLabel(List<CharacterClassDefinition> classes, int fallbackTierNumber) {
        if (classes != null && !classes.isEmpty()) {
            RaceAscensionDefinition ascension = classes.get(0).getAscension();
            String stage = prettifyPathName(ascension != null ? ascension.getStage() : null);
            if (!stage.isBlank()) {
                return stage;
            }
        }
        return "Tier " + fallbackTierNumber;
    }

    private record PathTierRow(String label, List<CharacterClassDefinition> nodes, boolean baseTier,
            boolean finalTier) {
        private static PathTierRow base(List<CharacterClassDefinition> nodes) {
            return new PathTierRow("Base", nodes == null ? List.of() : nodes, true, false);
        }

        private static PathTierRow tier(String label, List<CharacterClassDefinition> nodes) {
            return new PathTierRow(label, nodes == null ? List.of() : nodes, false, false);
        }

        private static PathTierRow finalTier(String label, List<CharacterClassDefinition> nodes) {
            return new PathTierRow(label, nodes == null ? List.of() : nodes, false, true);
        }
    }
}
