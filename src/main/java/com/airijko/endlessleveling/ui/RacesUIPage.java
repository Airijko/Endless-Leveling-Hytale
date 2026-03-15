package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.enums.themes.AttributeTheme;
import com.airijko.endlessleveling.enums.themes.EvolutionRequirementTheme;
import com.airijko.endlessleveling.enums.themes.EvolutionStatusTheme;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page that lists all available races, their stats, and passive bonuses.
 */
public class RacesUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String EVOLUTION_ENTRY_TEMPLATE = "Pages/Races/RaceEvolutionEntry.ui";

    private final RaceManager raceManager;
    private final PlayerDataManager playerDataManager;
    private String selectedRaceId;

    public RacesUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.selectedRaceId = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Races/RacesPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef, "races");
        NavUIHelper.bindNavEvents(events);
        applyStaticLabels(ui);
        events.addEventBinding(Activating,
                "#ConfirmRaceButton",
                of("Action", "race:confirm"),
                false);
        events.addEventBinding(Activating,
                "#ViewRacePathsButton",
                of("Action", "race:open_paths"),
                false);

        if (raceManager == null || !raceManager.isEnabled()) {
            ui.set("#SelectedRaceLabel.Text", tr("ui.races.offline.title", "Races Offline"));
            ui.set("#SelectedRaceSubtitle.Text",
                    tr("ui.races.offline.subtitle", "Races are currently disabled in config.yml."));
            ui.set("#RaceSwapCooldownValue.Text", tr("ui.races.cooldown.disabled", "Disabled"));
            ui.set("#RaceSwapCooldownHint.Text", tr("ui.races.offline.hint", "Enable races to manage identities."));
            ui.set("#RaceCountLabel.Text", tr("ui.races.count_none", "0 available"));
            ui.set("#CurrentRaceValue.Text", tr("hud.common.unavailable", "--"));
            ui.clear("#RaceRows");
            ui.clear("#RacePassiveEntries");
            ui.clear("#RaceEvolutionEntries");
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#SelectedRaceLabel.Text", tr("ui.races.playerdata.title", "Player data unavailable"));
            ui.set("#SelectedRaceSubtitle.Text",
                    tr("ui.races.playerdata.subtitle", "Unable to load your race information right now."));
            ui.set("#RaceSwapCooldownValue.Text", tr("hud.common.unavailable", "--"));
            ui.set("#RaceSwapCooldownHint.Text",
                    tr("ui.races.playerdata.hint", "Try reopening this page in a few moments."));
            ui.set("#CurrentRaceValue.Text", tr("hud.common.unavailable", "--"));
            ui.clear("#RaceRows");
            ui.clear("#RacePassiveEntries");
            ui.clear("#RaceEvolutionEntries");
            return;
        }

        boolean operatorBypass = OperatorHelper.isOperator(playerRef);
        RaceDefinition activeRace = raceManager.getPlayerRace(playerData);
        if (selectedRaceId == null && activeRace != null) {
            selectedRaceId = activeRace.getId();
        }

        updateStatusCard(ui, activeRace);
        updateCooldownCard(ui, playerData, operatorBypass);
        buildRaceList(ui, events, playerData, activeRace);
        updateRaceDetailPanel(ui, playerData, activeRace, operatorBypass);
    }

    private void refreshRaceUi(@Nonnull PlayerData playerData, boolean operatorBypass) {
        if (raceManager == null || !raceManager.isEnabled()) {
            return;
        }

        RaceDefinition activeRace = raceManager.getPlayerRace(playerData);
        if (selectedRaceId == null && activeRace != null) {
            selectedRaceId = activeRace.getId();
        }

        UICommandBuilder ui = new UICommandBuilder();
        updateStatusCard(ui, activeRace);
        updateCooldownCard(ui, playerData, operatorBypass);
        refreshRaceList(ui, activeRace);
        updateRaceDetailPanel(ui, playerData, activeRace, operatorBypass);
        sendUpdate(ui, false);
    }

    private void updateStatusCard(@Nonnull UICommandBuilder ui, RaceDefinition activeRace) {
        String current = activeRace != null ? activeRace.getDisplayName()
                : tr("ui.races.current.none", "None selected");
        ui.set("#CurrentRaceValue.Text", current);
    }

    private void updateCooldownCard(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            boolean operatorBypass) {
        if (operatorBypass) {
            ui.set("#RaceSwapCooldownValue.Text", tr("ui.races.cooldown.bypassed", "Bypassed"));
            ui.set("#RaceSwapCooldownHint.Text", tr("ui.races.cooldown.bypassed_hint", "Operator bypass active."));
            return;
        }
        boolean hasChangesRemaining = raceManager != null && raceManager.hasRaceSwitchesRemaining(data);
        if (!hasChangesRemaining) {
            ui.set("#RaceSwapCooldownValue.Text", tr("ui.races.cooldown.exhausted", "No changes left"));
            ui.set("#RaceSwapCooldownHint.Text",
                    tr("ui.races.error.no_changes_remaining", "No race changes remaining."));
            return;
        }
        long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
        long remaining = computeCooldownRemaining(data, cooldownSeconds);
        if (remaining > 0) {
            ui.set("#RaceSwapCooldownValue.Text", formatDuration(remaining));
            ui.set("#RaceSwapCooldownHint.Text", tr("ui.races.cooldown.in_cooldown", "Swap in cooldown"));
        } else {
            ui.set("#RaceSwapCooldownValue.Text", tr("ui.races.cooldown.ready", "Ready"));
            if (cooldownSeconds > 0) {
                ui.set("#RaceSwapCooldownHint.Text",
                        tr("ui.races.cooldown.swap_triggers", "Swapping will trigger a {0} cooldown.",
                                formatDuration(cooldownSeconds)));
            } else {
                ui.set("#RaceSwapCooldownHint.Text",
                        tr("ui.races.cooldown.unrestricted", "Race swapping is unrestricted right now."));
            }
        }
    }

    private void buildRaceList(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull PlayerData data,
            RaceDefinition activeRace) {
        ui.clear("#RaceRows");

        List<RaceDefinition> races = getSortedRaces();
        ui.set("#RaceCountLabel.Text",
                tr("ui.races.count", "{0} {1}", races.size(), races.size() == 1
                        ? tr("ui.races.count_word.singular", "race")
                        : tr("ui.races.count_word.plural", "races")));

        for (int index = 0; index < races.size(); index++) {
            RaceDefinition definition = races.get(index);
            ui.append("#RaceRows", "Pages/Races/RaceRow.ui");
            String baseSelector = "#RaceRows[" + index + "]";

            String displayName = definition.getDisplayName();
            boolean isCurrent = activeRace != null && activeRace.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedRaceMatches(definition.getId());

            ui.set(baseSelector + " #RaceName.Text", displayName);

            String selectionStatus = isCurrent ? tr("ui.races.status.current", "CURRENT")
                    : (isSelected ? tr("ui.races.status.viewing", "VIEWING") : "");
            boolean hasStatus = !selectionStatus.isEmpty();
            ui.set(baseSelector + " #RaceSelectionStatus.Visible", hasStatus);
            ui.set(baseSelector + " #RaceSelectionStatus.Text", selectionStatus);
            ui.set(baseSelector + " #ViewRaceButton.Text", tr("ui.races.actions.view", "VIEW"));

            events.addEventBinding(Activating,
                    baseSelector + " #ViewRaceButton",
                    of("Action", "race:view:" + definition.getId()),
                    false);
        }
    }

    private void refreshRaceList(@Nonnull UICommandBuilder ui,
            RaceDefinition activeRace) {
        List<RaceDefinition> races = getSortedRaces();
        ui.set("#RaceCountLabel.Text",
                tr("ui.races.count", "{0} {1}", races.size(), races.size() == 1
                        ? tr("ui.races.count_word.singular", "race")
                        : tr("ui.races.count_word.plural", "races")));

        for (int index = 0; index < races.size(); index++) {
            RaceDefinition definition = races.get(index);
            String baseSelector = "#RaceRows[" + index + "]";

            String displayName = definition.getDisplayName();
            boolean isCurrent = activeRace != null && activeRace.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedRaceMatches(definition.getId());

            ui.set(baseSelector + " #RaceName.Text", displayName);

            String selectionStatus = isCurrent ? tr("ui.races.status.current", "CURRENT")
                    : (isSelected ? tr("ui.races.status.viewing", "VIEWING") : "");
            boolean hasStatus = !selectionStatus.isEmpty();
            ui.set(baseSelector + " #RaceSelectionStatus.Visible", hasStatus);
            ui.set(baseSelector + " #RaceSelectionStatus.Text", selectionStatus);
            ui.set(baseSelector + " #ViewRaceButton.Text", tr("ui.races.actions.view", "VIEW"));
        }
    }

    private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
        ui.set("#RaceSwapCooldownCardTitle.Text", tr("ui.races.cooldown.card_title", "Race Swap Cooldown"));
        ui.set("#RaceSwapCooldownHint.Text",
                tr("ui.races.cooldown.card_hint", "Swapping again becomes available once the timer hits zero."));
        ui.set("#RaceListTitle.Text", tr("ui.races.page.available", "Available Races"));
        ui.set("#RaceCurrentTitle.Text", tr("ui.races.page.current_title", "Current Race"));
        ui.set("#RaceCurrentBadge.Text", tr("ui.races.page.current_badge", "Active"));
        ui.set("#RaceCurrentHint.Text",
                tr("ui.races.page.current_hint", "Your active race sets identity and innate bonuses."));
        ui.set("#RaceLoreTitle.Text", tr("ui.races.page.lore_title", "Lore Preview"));
        ui.set("#RaceStatsTitle.Text", tr("ui.races.page.stats_title", "Race Stats"));
        ui.set("#RacePassivesTitle.Text", tr("ui.races.page.passives_title", "Passives"));
        ui.set("#RaceEvolutionTitle.Text", tr("ui.races.page.evolution_title", "Evolution Paths"));
        ui.set("#RaceEvolutionSummary.Text",
                tr("ui.races.evolution.summary.placeholder", "Select a race to inspect evolution branches."));
        ui.set("#RaceDetailCooldownWarning.Text",
                tr("ui.races.cooldown.default_warning",
                        "You will be locked for the remaining cooldown after swapping."));
        ui.set("#ViewRacePathsButton.Text", tr("ui.races.actions.view_paths", "VIEW PATHS"));
        ui.set("#ConfirmRaceButton.Text", tr("ui.races.actions.swap", "SWAP"));

        for (AttributeTheme theme : AttributeTheme.values()) {
            ui.set(theme.raceLabelSelector() + ".Text", tr(theme.labelKey(), theme.labelFallback()));
            ui.set(theme.raceLabelSelector() + ".Style.TextColor", theme.labelColor());
            ui.set(theme.raceValueSelector() + ".Style.TextColor", theme.valueColor());
            ui.set(theme.raceNoteSelector() + ".Style.TextColor", theme.raceNoteColor());
        }
    }

    private void updateRaceDetailPanel(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            RaceDefinition activeRace,
            boolean operatorBypass) {
        RaceDefinition selection = resolveSelection(activeRace);
        if (selection == null) {
            ui.set("#SelectedRaceLabel.Text", tr("ui.races.select.title", "Select a Race"));
            ui.set("#SelectedRaceSubtitle.Text",
                    tr("ui.races.select.subtitle", "Choose a race on the left to preview its identity."));
            ui.set("#RaceLoreText.Text", tr("ui.races.lore.unavailable", "Lore unavailable."));
            ui.clear("#RacePassiveEntries");
            ui.set("#RacePassiveSummary.Visible", true);
            ui.set("#RacePassiveSummary.Text", tr("ui.races.passives.none_selected", "No race selected."));
            ui.set("#RaceEvolutionSummary.Text", tr("ui.races.evolution.none_selected", "No race selected."));
            ui.clear("#RaceEvolutionEntries");
            ui.set("#ViewRacePathsButton.Visible", false);
            clearAttributePreview(ui);
            return;
        }

        ui.set("#SelectedRaceLabel.Text", selection.getDisplayName());
        ui.set("#ViewRacePathsButton.Visible", true);
        ui.set("#SelectedRaceSubtitle.Text",
                selection == activeRace ? tr("ui.races.subtitle.current", "Currently active")
                        : tr("ui.races.subtitle.preview", "Preview only"));
        String lore = selection.getDescription();
        ui.set("#RaceLoreText.Text",
                lore == null || lore.isBlank() ? tr("ui.races.lore.missing", "No lore provided for this race.") : lore);

        applyAttributePreview(ui, selection, SkillAttributeType.LIFE_FORCE, "#RaceAttributeLifeForce");
        applyAttributePreview(ui, selection, SkillAttributeType.STRENGTH, "#RaceAttributeStrength");
        applyAttributePreview(ui, selection, SkillAttributeType.SORCERY, "#RaceAttributeSorcery");
        applyAttributePreview(ui, selection, SkillAttributeType.DEFENSE, "#RaceAttributeDefense");
        applyAttributePreview(ui, selection, SkillAttributeType.HASTE, "#RaceAttributeHaste");
        applyAttributePreview(ui, selection, SkillAttributeType.PRECISION, "#RaceAttributePrecision");
        applyAttributePreview(ui, selection, SkillAttributeType.FEROCITY, "#RaceAttributeFerocity");
        applyAttributePreview(ui, selection, SkillAttributeType.STAMINA, "#RaceAttributeStamina");
        applyAttributePreview(ui, selection, SkillAttributeType.FLOW, "#RaceAttributeFlow");
        applyAttributePreview(ui, selection, SkillAttributeType.DISCIPLINE, "#RaceAttributeDiscipline");

        List<RacePassiveDefinition> passives = selection.getPassiveDefinitions();
        if (passives == null || passives.isEmpty()) {
            ui.set("#RacePassiveSummary.Visible", true);
            ui.set("#RacePassiveSummary.Text",
                    tr("ui.races.passives.none_defined", "This race does not define passive bonuses."));
            ui.clear("#RacePassiveEntries");
        } else {
            ui.set("#RacePassiveSummary.Visible", false);
            ui.clear("#RacePassiveEntries");
            for (int index = 0; index < passives.size(); index++) {
                RacePassiveDefinition passive = passives.get(index);
                ui.append("#RacePassiveEntries", "Pages/Races/RacePassiveEntry.ui");
                String base = "#RacePassiveEntries[" + index + "]";
                ui.set(base + " #PassiveName.Text", buildPassiveLabel(passive));
                ui.set(base + " #PassiveValue.Text", formatPassiveDescription(passive, data));
            }
        }

        updateEvolutionPreview(ui, data, selection, activeRace);

        if (operatorBypass) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    tr("ui.races.cooldown.bypassed_detail", "Operator bypass active. Swapping is immediate."));
            return;
        }

        if (raceManager != null && !raceManager.hasRaceSwitchesRemaining(data)) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    tr("ui.races.error.no_changes_remaining", "No race changes remaining."));
            return;
        }

        long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
        long remaining = computeCooldownRemaining(data, cooldownSeconds);
        if (remaining > 0) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    tr("ui.races.cooldown.unlocks_in", "Swap unlocks in {0}.", formatDuration(remaining)));
        } else if (cooldownSeconds > 0) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    tr("ui.races.cooldown.swap_triggers", "Swapping will trigger a {0} cooldown.",
                            formatDuration(cooldownSeconds)));
        } else {
            ui.set("#RaceDetailCooldownWarning.Text",
                    tr("ui.races.cooldown.unrestricted", "Swapping is unrestricted right now."));
        }
    }

    private void updateEvolutionPreview(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            @Nonnull RaceDefinition selection,
            RaceDefinition activeRace) {
        RaceAscensionDefinition ascension = selection.getAscension();
        String stageLabel = ascension == null ? "" : toDisplay(ascension.getStage());
        if (stageLabel.isBlank()) {
            stageLabel = tr("ui.races.evolution.stage.base", "Base");
        }

        String pathLabel = ascension == null ? "" : toDisplay(ascension.getPath());
        if (pathLabel.isBlank()) {
            pathLabel = tr("ui.races.evolution.path.none", "None");
        }

        ui.clear("#RaceEvolutionEntries");
        List<EvolutionNode> evolutionNodes = collectEvolutionNodes(selection);

        if (ascension != null && ascension.isFinalForm()) {
            ui.set("#RaceEvolutionSummary.Text",
                    tr("ui.races.evolution.summary.final", "Stage: {0} - Final form reached.", stageLabel));
        } else if (evolutionNodes.isEmpty()) {
            ui.set("#RaceEvolutionSummary.Text",
                    tr("ui.races.evolution.summary", "Stage: {0} - Path: {1}", stageLabel, pathLabel));
        } else {
            String pathTypes = collectPathTypeSummary(evolutionNodes);
            ui.set("#RaceEvolutionSummary.Text",
                    tr("ui.races.evolution.summary.expanded",
                            "Stage: {0} - Path: {1} - Paths: {2} ({3})",
                            stageLabel,
                            pathLabel,
                            evolutionNodes.size(),
                            pathTypes));
        }

        if (evolutionNodes.isEmpty()) {
            ui.append("#RaceEvolutionEntries", EVOLUTION_ENTRY_TEMPLATE);
            ui.set("#RaceEvolutionEntries[0] #EvolutionName.Text",
                    tr("ui.races.evolution.none", "No further evolutions"));
            ui.set("#RaceEvolutionEntries[0] #EvolutionStatus.Text", tr("ui.races.evolution.none_value", "FINAL"));
            ui.set("#RaceEvolutionEntries[0] #EvolutionStatus.Style.TextColor",
                    EvolutionStatusTheme.FINAL.statusColor());
            ui.set("#RaceEvolutionEntries[0] #EvolutionMeta.Text",
                    tr("ui.races.evolution.none_meta", "This race is at the end of its ascension branch."));
            ui.set("#RaceEvolutionEntries[0] #EvolutionCriteria.Text",
                    tr("ui.races.evolution.none_criteria", "No additional upgrade criteria."));
            ui.set("#RaceEvolutionEntries[0] #EvolutionCriteria.Style.TextColor",
                    EvolutionRequirementTheme.NEUTRAL.color());
            ui.set("#RaceEvolutionEntries[0] #EvolutionSkillCriteria.Text", "");
            ui.set("#RaceEvolutionEntries[0] #EvolutionSkillCriteria.Visible", false);
            ui.set("#RaceEvolutionEntries[0] #EvolutionProgress.Style.TextColor",
                    EvolutionRequirementTheme.NEUTRAL.color());
            ui.set("#RaceEvolutionEntries[0] #EvolutionProgress.Text", "");
            return;
        }

        int rowIndex = 0;
        for (EvolutionNode node : evolutionNodes) {
            RaceDefinition nextRace = node.race();
            if (nextRace == null) {
                continue;
            }

            RaceAscensionEligibility eligibility = raceManager == null
                    ? null
                    : raceManager.evaluateAscensionEligibility(data, selection.getId(), nextRace.getId(), false);

            boolean active = isActiveRaceForm(nextRace, activeRace);
            boolean unlocked = isRaceFormUnlocked(nextRace, activeRace, data);
            boolean available = eligibility != null && eligibility.isEligible();
            boolean directPath = isDirectEvolutionPath(selection, nextRace);

            EvolutionStatusTheme statusTheme = resolveEvolutionStatusTheme(active, unlocked, available, directPath);
            String statusLabel = localizeEvolutionStatus(statusTheme);
            EvolutionCriteriaContent criteriaContent = buildEvolutionCriteriaContent(nextRace);

            String progressText;
            String progressColor = statusTheme.progressColor();
            if (active) {
                progressText = tr("ui.races.evolution.progress.active", "Currently active form.");
            } else if (unlocked) {
                progressText = tr("ui.races.evolution.progress.unlocked", "Previously completed and unlocked.");
            } else if (eligibility == null) {
                progressText = tr("ui.races.evolution.progress.unknown", "Unable to evaluate current progress.");
                progressColor = EvolutionRequirementTheme.NEUTRAL.color();
            } else if (eligibility.isEligible() && directPath) {
                progressText = tr("ui.races.evolution.progress.ready", "All criteria met. Can evolve immediately.");
                progressColor = EvolutionRequirementTheme.READY.color();
            } else if (eligibility.isEligible()) {
                progressText = tr("ui.races.evolution.progress.ready_after",
                        "All criteria met. Reach this branch by progressing through earlier path nodes.");
                progressColor = EvolutionRequirementTheme.READY.color();
            } else {
                progressText = formatMissingProgress(eligibility, nextRace);
                progressColor = EvolutionRequirementTheme.MISSING.color();
            }

            ui.append("#RaceEvolutionEntries", EVOLUTION_ENTRY_TEMPLATE);
            String base = "#RaceEvolutionEntries[" + rowIndex + "]";
            ui.set(base + " #EvolutionName.Text", buildEvolutionLabel(nextRace));
            ui.set(base + " #EvolutionStatus.Text", statusLabel);
            ui.set(base + " #EvolutionStatus.Style.TextColor", statusTheme.statusColor());
            ui.set(base + " #EvolutionMeta.Text", buildEvolutionMeta(nextRace, node));
            ui.set(base + " #EvolutionCriteria.Text", criteriaContent.generalText());
            ui.set(base + " #EvolutionCriteria.Style.TextColor", EvolutionRequirementTheme.GENERAL.color());
            ui.set(base + " #EvolutionSkillCriteria.Text", criteriaContent.skillText());
            ui.set(base + " #EvolutionSkillCriteria.Visible", !criteriaContent.skillText().isBlank());
            ui.set(base + " #EvolutionSkillCriteria.Style.TextColor",
                    EvolutionRequirementTheme.TRAINABLE_SKILLS.color());
            ui.set(base + " #EvolutionProgress.Text", progressText);
            ui.set(base + " #EvolutionProgress.Style.TextColor", progressColor);
            rowIndex++;
        }
    }

    private EvolutionStatusTheme resolveEvolutionStatusTheme(boolean active,
            boolean unlocked,
            boolean available,
            boolean directPath) {
        if (active) {
            return EvolutionStatusTheme.ACTIVE;
        }
        if (unlocked) {
            return EvolutionStatusTheme.UNLOCKED;
        }
        if (available && directPath) {
            return EvolutionStatusTheme.AVAILABLE;
        }
        if (available) {
            return EvolutionStatusTheme.ELIGIBLE;
        }
        return EvolutionStatusTheme.LOCKED;
    }

    private String localizeEvolutionStatus(@Nonnull EvolutionStatusTheme theme) {
        return switch (theme) {
            case ACTIVE -> tr("ui.races.evolution.status.active", "ACTIVE");
            case UNLOCKED -> tr("ui.races.evolution.status.unlocked", "UNLOCKED");
            case AVAILABLE -> tr("ui.races.evolution.status.available", "AVAILABLE");
            case ELIGIBLE -> tr("ui.races.evolution.status.eligible", "ELIGIBLE");
            case FINAL -> tr("ui.races.evolution.none_value", "FINAL");
            case LOCKED, UNKNOWN -> tr("ui.races.evolution.status.locked", "LOCKED");
        };
    }

    private String collectPathTypeSummary(@Nonnull List<EvolutionNode> nodes) {
        Set<String> uniqueTypes = new LinkedHashSet<>();
        for (EvolutionNode node : nodes) {
            if (node == null || node.race() == null) {
                continue;
            }
            uniqueTypes.add(resolvePathType(node.race()));
        }
        if (uniqueTypes.isEmpty()) {
            return tr("ui.races.evolution.path.none", "None");
        }
        return String.join(", ", uniqueTypes);
    }

    private List<EvolutionNode> collectEvolutionNodes(@Nonnull RaceDefinition root) {
        if (raceManager == null || root == null) {
            return List.of();
        }

        List<EvolutionNode> nodes = new ArrayList<>();
        ArrayDeque<EvolutionNode> frontier = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();

        for (RaceDefinition next : raceManager.getNextAscensionRaces(root.getId())) {
            if (next == null) {
                continue;
            }
            String key = evolutionPathKey(next);
            if (!seen.add(key)) {
                continue;
            }
            EvolutionNode node = new EvolutionNode(next, 1, root.getId());
            frontier.addLast(node);
            nodes.add(node);
        }

        while (!frontier.isEmpty()) {
            EvolutionNode current = frontier.removeFirst();
            for (RaceDefinition child : raceManager.getNextAscensionRaces(current.race().getId())) {
                if (child == null) {
                    continue;
                }
                String key = evolutionPathKey(child);
                if (!seen.add(key)) {
                    continue;
                }
                EvolutionNode childNode = new EvolutionNode(child, current.depth() + 1, current.race().getId());
                frontier.addLast(childNode);
                nodes.add(childNode);
            }
        }

        return nodes;
    }

    private String evolutionPathKey(RaceDefinition race) {
        if (race == null) {
            return "";
        }

        String key = race.getId();
        if (raceManager != null) {
            String resolvedPath = raceManager.resolveAscensionPathId(race.getId());
            if (resolvedPath != null && !resolvedPath.isBlank()) {
                key = resolvedPath;
            }
        }

        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDirectEvolutionPath(@Nonnull RaceDefinition source, @Nonnull RaceDefinition target) {
        if (raceManager == null) {
            return false;
        }
        for (RaceDefinition candidate : raceManager.getNextAscensionRaces(source.getId())) {
            if (candidate != null && candidate.getId().equalsIgnoreCase(target.getId())) {
                return true;
            }
        }
        return false;
    }

    private String buildEvolutionMeta(@Nonnull RaceDefinition race, @Nonnull EvolutionNode node) {
        String pathType = resolvePathType(race);
        String stage = race.getAscension() == null ? tr("ui.races.evolution.stage.base", "Base")
                : toDisplay(race.getAscension().getStage());
        if (stage.isBlank()) {
            stage = tr("ui.races.evolution.stage.base", "Base");
        }

        String parentName = resolveRaceDisplayName(node.parentRaceId());
        return tr("ui.races.evolution.meta",
                "Type: {0} | Stage: {1} | Tier: {2} | From: {3}",
                pathType,
                stage,
                node.depth(),
                parentName);
    }

    private EvolutionCriteriaContent buildEvolutionCriteriaContent(@Nonnull RaceDefinition race) {
        RaceAscensionDefinition ascension = race.getAscension();
        RaceAscensionRequirements requirements = ascension == null
                ? RaceAscensionRequirements.none()
                : ascension.getRequirements();

        List<String> generalSections = new ArrayList<>();
        List<String> skillSections = new ArrayList<>();

        if (requirements.getRequiredPrestige() > 0) {
            generalSections.add(tr("ui.races.evolution.criteria.prestige",
                    "- Prestige: >= {0}", requirements.getRequiredPrestige()));
        }

        String minSkillLine = formatSkillThresholds(requirements.getMinSkillLevels(), ">=");
        if (!minSkillLine.isBlank()) {
            skillSections.add(tr("ui.races.evolution.criteria.min_skills",
                    "- Minimum skill levels: {0}", minSkillLine));
        }

        String maxSkillLine = formatSkillThresholds(requirements.getMaxSkillLevels(), "<=");
        if (!maxSkillLine.isBlank()) {
            skillSections.add(tr("ui.races.evolution.criteria.max_skills",
                    "- Skill caps: {0}", maxSkillLine));
        }

        if (!requirements.getMinAnySkillLevels().isEmpty()) {
            List<String> anyGroups = new ArrayList<>();
            for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
                String renderedGroup = formatSkillThresholds(group, ">=");
                if (!renderedGroup.isBlank()) {
                    anyGroups.add(renderedGroup);
                }
            }
            if (!anyGroups.isEmpty()) {
                skillSections.add(tr("ui.races.evolution.criteria.any_skills",
                        "- Any one set: {0}", String.join(" OR ", anyGroups)));
            }
        }

        if (!requirements.getRequiredForms().isEmpty()) {
            List<String> forms = new ArrayList<>();
            for (String form : requirements.getRequiredForms()) {
                forms.add(resolveRaceDisplayName(form));
            }
            generalSections
                    .add(tr("ui.races.evolution.criteria.forms", "- Required forms: {0}", String.join(", ", forms)));
        }

        if (!requirements.getRequiredAnyForms().isEmpty()) {
            List<String> forms = new ArrayList<>();
            for (String form : requirements.getRequiredAnyForms()) {
                forms.add(resolveRaceDisplayName(form));
            }
            generalSections
                    .add(tr("ui.races.evolution.criteria.any_forms", "- Any one form: {0}",
                            String.join(" OR ", forms)));
        }

        if (!requirements.getRequiredAugments().isEmpty()) {
            generalSections.add(tr("ui.races.evolution.criteria.augments",
                    "- Required augments: {0}", String.join(", ", requirements.getRequiredAugments())));
        }

        if (generalSections.isEmpty() && skillSections.isEmpty()) {
            return new EvolutionCriteriaContent(tr("ui.races.evolution.criteria.none", "Requirements: none"), "");
        }

        String generalText;
        if (generalSections.isEmpty()) {
            generalText = tr("ui.races.evolution.criteria.general.none",
                    "Requirements: skill targets only");
        } else {
            generalText = tr("ui.races.evolution.criteria.general.prefix",
                    "Requirements:\n{0}", String.join("\n", generalSections));
        }

        String skillText = "";
        if (!skillSections.isEmpty()) {
            skillText = tr("ui.races.evolution.criteria.skills.prefix",
                    "Trainable Skill Targets:\n{0}", String.join("\n", skillSections));
        }

        return new EvolutionCriteriaContent(generalText, skillText);
    }

    private String formatSkillThresholds(Map<SkillAttributeType, Integer> thresholds, String operator) {
        if (thresholds == null || thresholds.isEmpty()) {
            return "";
        }

        List<Map.Entry<SkillAttributeType, Integer>> entries = new ArrayList<>();
        for (Map.Entry<SkillAttributeType, Integer> entry : thresholds.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            entries.add(entry);
        }

        if (entries.isEmpty()) {
            return "";
        }

        entries.sort(Comparator.comparingInt(entry -> entry.getKey().ordinal()));

        List<String> rendered = new ArrayList<>();
        for (Map.Entry<SkillAttributeType, Integer> entry : entries) {
            rendered.add(localizeAttributeName(entry.getKey()) + " " + operator + " " + entry.getValue());
        }
        return String.join(", ", rendered);
    }

    private String formatMissingProgress(@Nonnull RaceAscensionEligibility eligibility,
            @Nonnull RaceDefinition targetRace) {
        List<String> blockers = eligibility.getBlockers();
        if (blockers == null || blockers.isEmpty()) {
            return tr("ui.races.evolution.progress.missing_generic", "Missing requirements.");
        }

        List<String> lines = new ArrayList<>();
        lines.add(tr("ui.races.evolution.progress.missing_header", "Missing requirements:"));
        for (String blocker : blockers) {
            if (blocker == null || blocker.isBlank()) {
                continue;
            }

            String normalized = blocker.trim();
            if (isAnySkillSetBlocker(normalized)) {
                String anySkillOptions = buildAnySkillOptions(targetRace);
                if (!anySkillOptions.isBlank()) {
                    normalized = tr("ui.races.evolution.progress.missing_any_skill_specific",
                            "Requires at least one skill option set to be met: {0}.",
                            anySkillOptions);
                }
            }

            lines.add("- " + abbreviate(normalized, 180));
        }

        if (lines.size() == 1) {
            lines.add(tr("ui.races.evolution.progress.missing_generic", "- Requirement unmet."));
        }

        return String.join("\n", lines);
    }

    private boolean isAnySkillSetBlocker(@Nonnull String blocker) {
        String normalized = blocker.toLowerCase(Locale.ROOT);
        return normalized.contains("at least one or skill requirement set")
                || normalized.contains("at least one skill requirement set")
                || normalized.contains("any one skill requirement set")
                || normalized.contains("one or skill requirement set");
    }

    private String buildAnySkillOptions(@Nonnull RaceDefinition targetRace) {
        RaceAscensionDefinition ascension = targetRace.getAscension();
        RaceAscensionRequirements requirements = ascension == null
                ? RaceAscensionRequirements.none()
                : ascension.getRequirements();

        List<String> groups = new ArrayList<>();
        for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
            String rendered = formatSkillThresholds(group, ">=");
            if (!rendered.isBlank()) {
                groups.add(rendered);
            }
        }

        if (groups.isEmpty()) {
            return "";
        }

        return String.join(" OR ", groups);
    }

    private String resolveRaceDisplayName(String raceIdOrPathId) {
        if (raceIdOrPathId == null || raceIdOrPathId.isBlank()) {
            return tr("hud.common.unavailable", "--");
        }

        if (raceManager != null) {
            RaceDefinition direct = raceManager.getRace(raceIdOrPathId);
            if (direct != null && direct.getDisplayName() != null && !direct.getDisplayName().isBlank()) {
                return direct.getDisplayName();
            }

            String normalizedTarget = raceIdOrPathId.trim().toLowerCase(Locale.ROOT);
            for (RaceDefinition candidate : raceManager.getLoadedRaces()) {
                if (candidate == null) {
                    continue;
                }
                String candidatePath = raceManager.resolveAscensionPathId(candidate.getId());
                if (candidatePath != null && candidatePath.equalsIgnoreCase(normalizedTarget)) {
                    String displayName = candidate.getDisplayName();
                    return displayName == null || displayName.isBlank() ? candidate.getId() : displayName;
                }
            }
        }

        return toDisplay(raceIdOrPathId);
    }

    private String resolvePathType(@Nonnull RaceDefinition race) {
        RaceAscensionDefinition ascension = race.getAscension();
        if (ascension == null) {
            return tr("ui.races.evolution.path.none", "None");
        }

        String path = ascension.getPath();
        if (path == null || path.isBlank() || "none".equalsIgnoreCase(path)) {
            return tr("ui.races.evolution.path.none", "None");
        }

        String normalized = path.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "adventurer", "adventure" -> tr("ui.races.evolution.path.adventure", "Adventure");
            case "damage" -> tr("ui.races.evolution.path.damage", "Damage");
            case "sorcery" -> tr("ui.races.evolution.path.sorcery", "Sorcery");
            case "tank" -> tr("ui.races.evolution.path.tank", "Tank");
            default -> toDisplay(path);
        };
    }

    private boolean isActiveRaceForm(RaceDefinition candidate, RaceDefinition activeRace) {
        if (candidate == null || activeRace == null) {
            return false;
        }
        String candidatePath = raceManager == null ? candidate.getId()
                : raceManager.resolveAscensionPathId(candidate.getId());
        String activePath = raceManager == null ? activeRace.getId()
                : raceManager.resolveAscensionPathId(activeRace.getId());
        return candidatePath != null && activePath != null && candidatePath.equalsIgnoreCase(activePath);
    }

    private boolean isRaceFormUnlocked(RaceDefinition race, RaceDefinition activeRace, PlayerData data) {
        if (race == null) {
            return false;
        }

        if (isActiveRaceForm(race, activeRace)) {
            return true;
        }

        if (data == null || raceManager == null) {
            return false;
        }

        String pathId = raceManager.resolveAscensionPathId(race.getId());
        if (pathId != null && data.hasCompletedRaceForm(pathId)) {
            return true;
        }

        return data.hasCompletedRaceForm(race.getId());
    }

    private String buildEvolutionLabel(@Nonnull RaceDefinition race) {
        String display = race.getDisplayName();
        if (display == null || display.isBlank()) {
            display = race.getId();
        }

        RaceAscensionDefinition ascension = race.getAscension();
        if (ascension == null) {
            return display;
        }

        if (ascension.isFinalForm()) {
            return display + " (Final)";
        }

        String pathLabel = toDisplay(ascension.getPath());
        if (pathLabel.isBlank()) {
            return display;
        }
        return display + " (" + pathLabel + ")";
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        if (maxLength <= 3) {
            return normalized.substring(0, Math.max(0, maxLength));
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private static final class EvolutionNode {
        private final RaceDefinition race;
        private final int depth;
        private final String parentRaceId;

        private EvolutionNode(RaceDefinition race, int depth, String parentRaceId) {
            this.race = race;
            this.depth = Math.max(1, depth);
            this.parentRaceId = parentRaceId;
        }

        private RaceDefinition race() {
            return race;
        }

        private int depth() {
            return depth;
        }

        private String parentRaceId() {
            return parentRaceId;
        }
    }

    private record EvolutionCriteriaContent(String generalText, String skillText) {
    }

    private void clearAttributePreview(@Nonnull UICommandBuilder ui) {
        String unavailable = tr("hud.common.unavailable", "--");
        ui.set("#RaceAttributeLifeForceValue.Text", unavailable);
        ui.set("#RaceAttributeStrengthValue.Text", unavailable);
        ui.set("#RaceAttributeSorceryValue.Text", unavailable);
        ui.set("#RaceAttributeDefenseValue.Text", unavailable);
        ui.set("#RaceAttributeHasteValue.Text", unavailable);
        ui.set("#RaceAttributePrecisionValue.Text", unavailable);
        ui.set("#RaceAttributeFerocityValue.Text", unavailable);
        ui.set("#RaceAttributeStaminaValue.Text", unavailable);
        ui.set("#RaceAttributeFlowValue.Text", unavailable);
        ui.set("#RaceAttributeDisciplineValue.Text", unavailable);

        ui.set("#RaceAttributeLifeForceNote.Text", unavailable);
        ui.set("#RaceAttributeStrengthNote.Text", unavailable);
        ui.set("#RaceAttributeSorceryNote.Text", unavailable);
        ui.set("#RaceAttributeDefenseNote.Text", unavailable);
        ui.set("#RaceAttributeHasteNote.Text", unavailable);
        ui.set("#RaceAttributePrecisionNote.Text", unavailable);
        ui.set("#RaceAttributeFerocityNote.Text", unavailable);
        ui.set("#RaceAttributeStaminaNote.Text", unavailable);
        ui.set("#RaceAttributeFlowNote.Text", unavailable);
        ui.set("#RaceAttributeDisciplineNote.Text", unavailable);
    }

    private void applyAttributePreview(@Nonnull UICommandBuilder ui,
            @Nonnull RaceDefinition race,
            @Nonnull SkillAttributeType type,
            @Nonnull String selectorPrefix) {
        boolean hasAttribute = race.getBaseAttributes().containsKey(type);
        double value = race.getBaseAttribute(type, 0.0D);
        String formatted = hasAttribute ? formatRaceAttributeValue(type, value) : tr("hud.common.unavailable", "--");
        ui.set(selectorPrefix + "Value.Text", formatted);
        ui.set(selectorPrefix + "Note.Text",
                getAttributeTagline(type));
    }

    private String getAttributeTagline(@Nonnull SkillAttributeType type) {
        return switch (type) {
            case LIFE_FORCE -> tr("ui.races.attribute_tagline.life_force", "Base health");
            case STRENGTH -> tr("ui.races.attribute_tagline.strength", "Damage scaling");
            case SORCERY -> tr("ui.races.attribute_tagline.sorcery", "Magic damage");
            case DEFENSE -> tr("ui.races.attribute_tagline.defense", "Damage reduction");
            case HASTE -> tr("ui.races.attribute_tagline.haste", "Movement speed");
            case PRECISION -> tr("ui.races.attribute_tagline.precision", "Crit chance");
            case FEROCITY -> tr("ui.races.attribute_tagline.ferocity", "Crit damage");
            case STAMINA -> tr("ui.races.attribute_tagline.stamina", "Base stamina");
            case FLOW -> tr("ui.races.attribute_tagline.flow", "Base flow (mana)");
            case DISCIPLINE -> tr("ui.races.attribute_tagline.discipline", "XP gain");
        };
    }

    private RaceDefinition resolveSelection(RaceDefinition activeRace) {
        if (raceManager == null) {
            return null;
        }
        if (selectedRaceId != null) {
            RaceDefinition selected = raceManager.getRace(selectedRaceId);
            if (selected != null) {
                return selected;
            }
        }
        if (activeRace != null) {
            selectedRaceId = activeRace.getId();
            return activeRace;
        }
        RaceDefinition fallback = raceManager.getDefaultRace();
        if (fallback != null) {
            selectedRaceId = fallback.getId();
        }
        return fallback;
    }

    private boolean selectedRaceMatches(String raceId) {
        return selectedRaceId != null && selectedRaceId.equalsIgnoreCase(raceId);
    }

    private boolean isBaseRaceSelection(RaceDefinition race) {
        if (race == null) {
            return false;
        }
        String stage = race.getAscension() != null ? race.getAscension().getStage() : "base";
        return stage == null || stage.isBlank() || "base".equalsIgnoreCase(stage.trim());
    }

    private List<RaceDefinition> getSortedRaces() {
        Collection<RaceDefinition> loaded = raceManager.getLoadedRaces();
        List<RaceDefinition> races = new ArrayList<>();
        for (RaceDefinition race : loaded) {
            if (race == null) {
                continue;
            }
            String stage = race.getAscension() != null ? race.getAscension().getStage() : "base";
            if (stage == null || stage.isBlank() || "base".equalsIgnoreCase(stage.trim())) {
                races.add(race);
            }
        }
        races.sort(Comparator.comparing(r -> r.getDisplayName().toLowerCase(Locale.ROOT)));
        return races;
    }

    private PlayerData resolvePlayerData() {
        if (playerDataManager == null) {
            LOGGER.atSevere().log("RacesUIPage: PlayerDataManager unavailable");
            return null;
        }
        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(playerRef.getUuid(), playerRef.getUsername());
        }
        return data;
    }

    private String formatRaceAttributeValue(@Nonnull SkillAttributeType type, double value) {
        if (Double.isNaN(value)) {
            return tr("hud.common.unavailable", "--");
        }

        return switch (type) {
            case LIFE_FORCE -> formatResourceBase(value);
            case STAMINA -> formatResourceBase(value);
            case FLOW -> formatResourceBase(value);
            case STRENGTH -> formatDeltaPercent(value);
            case DEFENSE -> formatDeltaPercent(value);
            case HASTE -> formatDeltaPercent(value);
            case PRECISION -> formatAbsolutePercent(value);
            case FEROCITY -> formatAbsolutePercent(value);
            case SORCERY -> formatDeltaPercent(value);
            case DISCIPLINE -> formatAbsolutePercent(value);
            default -> formatNumber(value);
        };
    }

    private String formatResourceBase(double amount) {
        return formatNumber(amount);
    }

    private String formatDeltaPercent(double multiplier) {
        double percent = (multiplier - 1.0D) * 100.0D;
        return formatSignedPercent(percent);
    }

    private String formatAbsolutePercent(double percentValue) {
        return formatSignedPercent(percentValue);
    }

    private String formatSignedPercent(double percent) {
        if (Math.abs(percent) < 0.0001D) {
            return "0%";
        }
        String prefix = percent > 0 ? "+" : "-";
        return prefix + formatNumber(Math.abs(percent)) + "%";
    }

    private long computeCooldownRemaining(@Nonnull PlayerData data, long cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0;
        }
        long lastChange = data.getLastRaceChangeEpochSeconds();
        if (lastChange <= 0) {
            return 0;
        }
        long availableAt = lastChange + cooldownSeconds;
        long now = Instant.now().getEpochSecond();
        if (now >= availableAt) {
            return 0;
        }
        return availableAt - now;
    }

    private String buildPassiveLabel(@Nonnull RacePassiveDefinition passive) {
        ArchetypePassiveType type = passive.type();
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN && passive.attributeType() != null) {
            return localizeAttributeName(passive.attributeType());
        }
        if (passive.attributeType() != null) {
            return tr("ui.races.passive.label.with_attribute",
                    "{0} ({1})",
                    localizePassiveType(type),
                    localizeAttributeName(passive.attributeType()));
        }
        return localizePassiveType(type);
    }

    private String formatPassiveDescription(@Nonnull RacePassiveDefinition passive,
            @Nonnull PlayerData playerData) {
        ArchetypePassiveType type = passive.type();
        double value = passive.value();
        Map<String, Object> props = passive.properties() == null ? Map.of() : passive.properties();

        Double threshold = getDoubleProp(props, "threshold");
        Double duration = getDoubleProp(props, "duration");
        Double cooldown = getDoubleProp(props, "cooldown");
        Double window = getDoubleProp(props, "window");
        Double stacks = getDoubleProp(props, "max_stacks");
        Double slowPercent = getDoubleProp(props, "slow_percent");
        Double healingChance = getDoubleProp(props, "healing_chance");
        Double selfHealEffectiveness = getDoubleProp(props, "self_heal_effectiveness");
        if (selfHealEffectiveness == null) {
            selfHealEffectiveness = getDoubleProp(props, "self_heal_ratio");
        }
        Double selfShieldEffectiveness = getDoubleProp(props, "self_shield_effectiveness");
        if (selfShieldEffectiveness == null) {
            selfShieldEffectiveness = getDoubleProp(props, "self_shield_ratio");
        }
        Double maxBuffPerAlly = getDoubleProp(props, "max_buffed_value_per_ally");
        if (maxBuffPerAlly == null) {
            maxBuffPerAlly = getDoubleProp(props, "max_buff_per_ally");
        }
        Double selfBuffEffectiveness = getDoubleProp(props, "self_buff_effectiveness");
        if (selfBuffEffectiveness == null) {
            selfBuffEffectiveness = getDoubleProp(props, "self_buff_ratio");
        }
        String activation = getStringProp(props, "activation");
        String scalingStat = getStringProp(props, "scaling_stat");
        String sourceAttribute = getStringProp(props, "source_attribute");

        if (type == null) {
            return value == 0.0D ? tr("ui.races.passive.default_name", "Passive") : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> tr("ui.races.passive.desc.xp_bonus", "{0} XP gain", formatPercentValue(value));
            case HEALTH_REGEN -> tr("ui.races.passive.desc.health_regen", "{0} HP/5s", formatPercentValue(value));
            case MANA_REGEN -> tr("ui.races.passive.desc.mana_regen", "{0} mana/5s", formatPercentValue(value));
            case MANA_REGEN_FLAT -> tr("ui.races.passive.desc.mana_regen_flat", "{0} mana/s", formatSigned(value));
            case HEALING_TOUCH -> appendLines(
                    tr("ui.classes.passive.pretty.healing_touch.title", "On-hit burst heal"),
                    tr("ui.classes.passive.pretty.healing_touch.amount", "- Heal: {0} of source",
                            formatPercentValue(value)),
                    healingChance == null ? null
                            : tr("ui.classes.passive.pretty.healing_touch.chance", "- Trigger: {0}",
                                    formatPercentValue(healingChance)),
                    tr("ui.classes.passive.pretty.healing_touch.self",
                            "- Self effectiveness: {0}",
                            selfHealEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfHealEffectiveness)),
                    sourceAttribute == null || sourceAttribute.isBlank() ? null
                            : tr("ui.classes.passive.pretty.healing_touch.source", "- Source: {0}",
                                    localizeAttributeName(sourceAttribute)));
            case HEALING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.healing_aura.title", "Party healing pulse"),
                    tr("ui.classes.passive.pretty.healing_aura.effect", "- Heals from mana + stamina"),
                    tr("ui.classes.passive.pretty.healing_aura.scope", "- Party-only, radius-scaled"),
                    tr("ui.classes.passive.pretty.healing_aura.self",
                            "- Self effectiveness: {0}",
                            selfHealEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfHealEffectiveness)));
            case SHIELDING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.shielding_aura.title", "Party shielding aura"),
                    tr("ui.classes.passive.pretty.shielding_aura.effect", "- Shields from flat + stamina"),
                    tr("ui.classes.passive.pretty.shielding_aura.self",
                            "- Self effectiveness: {0}",
                            selfShieldEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfShieldEffectiveness)),
                    "on_hit".equalsIgnoreCase(activation)
                            ? tr("ui.classes.passive.pretty.shielding_aura.trigger_on_hit",
                                    "- On-hit trigger, then duration/cooldown")
                            : tr("ui.classes.passive.pretty.shielding_aura.trigger_always",
                                    "- Auto pulse with duration/cooldown"));
            case BUFFING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.buffing_aura.title", "Party buffing aura"),
                    tr("ui.classes.passive.pretty.buffing_aura.effect", "- Shares stamina-based damage"),
                    tr("ui.classes.passive.pretty.buffing_aura.cap",
                            "- Bonus damage cap: {0}",
                            maxBuffPerAlly == null ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(maxBuffPerAlly)),
                    tr("ui.classes.passive.pretty.buffing_aura.self",
                            "- Self effectiveness: {0}",
                            selfBuffEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfBuffEffectiveness)));
            case REGENERATION -> tr("ui.races.passive.desc.regeneration", "{0} HP/s", formatSigned(value));
            case HEALING_BONUS -> tr("ui.races.passive.desc.healing_bonus", "{0} healing", formatPercentValue(value));
            case LIFE_STEAL -> tr("ui.races.passive.desc.life_steal", "{0} life steal", formatPercentValue(value));
            case SPECIAL_CHARGE_BONUS ->
                tr("ui.races.passive.desc.charge_bonus", "{0} charge rate", formatPercentValue(value));
            case STAMINA_GAIN_BONUS ->
                tr("ui.races.passive.desc.stamina_gain", "{0} stamina gain", formatPercentValue(value));
            case LUCK -> tr("ui.races.passive.desc.luck", "{0} luck", formatPercentValue(value));
            case SECOND_WIND -> appendDetails(
                    tr("ui.races.passive.desc.second_wind", "{0} heal", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.hp", "HP")),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case FIRST_STRIKE -> appendDetails(
                    tr("ui.races.passive.desc.first_strike", "{0} opener", formatPercentValue(value)),
                    formatCooldownDetail(cooldown));
            case INNATE_ATTRIBUTE_GAIN -> formatInnatePreview(passive, playerData);
            case ADRENALINE -> appendDetails(
                    tr("ui.races.passive.desc.adrenaline", "{0} stamina", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.stamina", "stamina")),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case BERZERKER -> appendDetails(
                    tr("ui.races.passive.desc.berzerker", "{0} damage", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.hp", "HP")));
            case RETALIATION -> appendDetails(
                    tr("ui.races.passive.desc.retaliation", "{0} reflect", formatPercentValue(value)),
                    formatWindowDetail(window),
                    formatCooldownDetail(cooldown));
            case ABSORB -> appendDetails(
                    tr("ui.races.passive.desc.absorb", "{0} dmg reduction", formatPercentValue(value)),
                    formatCooldownDetail(cooldown));
            case EXECUTIONER -> appendDetails(
                    tr("ui.races.passive.desc.executioner", "{0} finisher", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.target_hp", "target HP")),
                    formatCooldownDetail(cooldown));
            case SWIFTNESS -> appendDetails(
                    tr("ui.races.passive.desc.swiftness", "{0} speed", formatPercentValue(value)),
                    formatDurationDetail(duration),
                    formatStacksDetail(stacks));
            case WITHER -> appendDetails(
                    tr("ui.races.passive.desc.wither", "{0} max HP/sec", formatPercentValue(value)),
                    formatDurationDetail(duration),
                    formatSlowDetail(slowPercent));
            case CRIT_DEFENSE -> appendDetails(
                    tr("ui.races.passive.desc.crit_defense", "{0} dmg reduction", formatPercentValue(value)),
                    formatScalingDetail(scalingStat));
            default -> formatSigned(value);
        };
    }

    private String formatSlowDetail(Double slowPercent) {
        if (slowPercent == null) {
            return null;
        }
        return tr("ui.races.passive.detail.slow", "{0} slow", formatPercentValue(slowPercent));
    }

    private String formatScalingDetail(String scalingStat) {
        if (scalingStat == null || scalingStat.isBlank()) {
            return null;
        }
        return tr("ui.races.passive.detail.scales_with", "scales with {0}", localizeAttributeName(scalingStat));
    }

    private String getStringProp(Map<String, Object> props, String key) {
        Object raw = props.get(key);
        if (raw instanceof String str) {
            String trimmed = str.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private String formatInnatePreview(@Nonnull RacePassiveDefinition passive,
            @Nonnull PlayerData playerData) {
        double perLevel = passive.value();
        String perLevelText = tr("ui.races.passive.detail.per_level", "{0} per level", formatSigned(perLevel));

        int level = playerData == null ? 1 : Math.max(1, playerData.getLevel());
        double total = perLevel * level;
        String totalText = formatSigned(total);
        return tr("ui.races.passive.detail.total_at_level", "{0} (Total {1} @ Lv {2})", perLevelText, totalText,
                level);
    }

    private Double getDoubleProp(@Nonnull Map<String, Object> props, @Nonnull String key) {
        Object raw = props.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String formatPercentValue(double ratio) {
        return formatSigned(ratio * 100.0D) + "%";
    }

    private String formatSigned(double number) {
        String prefix = number >= 0 ? "+" : "-";
        return prefix + formatNumber(Math.abs(number));
    }

    private String formatThresholdDetail(Double ratio, String scope) {
        if (ratio == null) {
            return null;
        }
        return tr("ui.races.passive.detail.threshold", "<{0}% {1}", formatNumber(ratio * 100.0D), scope);
    }

    private String formatDurationDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.duration", "{0}s duration", formatNumber(seconds));
    }

    private String formatCooldownDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.cooldown", "{0}s cd", formatNumber(seconds));
    }

    private String formatWindowDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.window", "{0}s window", formatNumber(seconds));
    }

    private String formatStacksDetail(Double stacks) {
        if (stacks == null) {
            return null;
        }
        return tr("ui.races.passive.detail.stacks", "{0} stacks", formatNumber(stacks));
    }

    private String appendDetails(String base, String... extra) {
        String detail = joinDetails(extra);
        if (detail.isEmpty()) {
            return base;
        }
        return base + " (" + detail + ")";
    }

    private String appendLines(String base, String... extra) {
        String detail = joinLines(extra);
        if (detail.isEmpty()) {
            return base;
        }
        return base + "\n" + detail;
    }

    private String joinDetails(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String joinLines(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String formatNumber(double value) {
        String formatted = String.format(Locale.US, "%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return tr("ui.time.seconds", "{0}s", 0);
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(tr("ui.time.hours", "{0}h", hours));
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tr("ui.time.minutes", "{0}m", minutes));
        }
        if (remainingSeconds > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tr("ui.time.seconds", "{0}s", remainingSeconds));
        }
        return builder.toString();
    }

    private String toDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.toLowerCase(Locale.ROOT).split("[_ ]");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String localizePassiveType(ArchetypePassiveType type) {
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        String keySuffix = type.name().toLowerCase(Locale.ROOT);
        return tr("ui.races.passive.type." + keySuffix, toDisplay(type.name()));
    }

    private String localizeAttributeName(SkillAttributeType type) {
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        return tr("ui.skills.label." + type.getConfigKey(), toDisplay(type.name()));
    }

    private String localizeAttributeName(String rawAttribute) {
        if (rawAttribute == null || rawAttribute.isBlank()) {
            return "";
        }
        SkillAttributeType attributeType = SkillAttributeType.fromConfigKey(rawAttribute);
        if (attributeType != null) {
            return localizeAttributeName(attributeType);
        }
        return toDisplay(rawAttribute);
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

        if (data.action == null || data.action.isBlank() || !data.action.startsWith("race:")) {
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.races.error.playerdata", "Unable to load your race info right now.")).color("#ff6666"));
            return;
        }

        boolean operatorBypass = OperatorHelper.isOperator(playerRef);

        if (data.action.startsWith("race:view:")) {
            String targetId = data.action.substring("race:view:".length());
            if (targetId != null && !targetId.isBlank()) {
                this.selectedRaceId = targetId.trim();
                refreshRaceUi(playerData, operatorBypass);
            }
            return;
        }

        if (data.action.equals("race:confirm")) {
            if (selectedRaceId == null || selectedRaceId.isBlank()) {
                playerRef.sendMessage(
                        Message.raw(tr("ui.races.error.select_first", "Select a race to swap into.")).color("#ff9900"));
                return;
            }
            handleRaceChoose(selectedRaceId, playerData, ref, store, operatorBypass);
            return;
        }

        if (data.action.equals("race:open_paths")) {
            openRacePathsPage(ref, store, raceManager == null ? null : raceManager.getPlayerRace(playerData));
            return;
        }
    }

    private void openRacePathsPage(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            RaceDefinition activeRace) {
        if (raceManager == null || !raceManager.isEnabled()) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.disabled", "Races are disabled.")).color("#ff6666"));
            return;
        }

        RaceDefinition selection = resolveSelection(activeRace);
        if (selection == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.races.error.select_first", "Select a race first.")).color("#ff9900"));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        player.getPageManager().openCustomPage(ref, store,
                new RacePathsUIPage(playerRef, CustomPageLifetime.CanDismiss, selection.getId()));
    }

    private void handleRaceChoose(String targetRaceId,
            @Nonnull PlayerData playerData,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            boolean operatorBypass) {
        if (targetRaceId == null || targetRaceId.isBlank()) {
            return;
        }
        if (raceManager == null || !raceManager.isEnabled()) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.disabled", "Races are disabled.")).color("#ff6666"));
            return;
        }

        RaceDefinition desired = raceManager.findRaceByUserInput(targetRaceId);
        if (desired == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.races.error.unknown", "Unknown race: {0}", targetRaceId)).color("#ff6666"));
            return;
        }
        if (!isBaseRaceSelection(desired)) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.base_only",
                    "Base race swapping only supports base races. Use Race Paths to evolve into higher forms."))
                    .color("#ff6666"));
            return;
        }

        RaceDefinition current = raceManager.getPlayerRace(playerData);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.already_selected", "You already belong to that race."))
                    .color("#ff9900"));
            this.selectedRaceId = current.getId();
            rebuild();
            return;
        }

        if (!operatorBypass && raceManager != null && !raceManager.hasRaceSwitchesRemaining(playerData)) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.no_changes_remaining", "No race changes remaining."))
                    .color("#ff6666"));
            return;
        }

        if (!operatorBypass) {
            long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
            long remaining = computeCooldownRemaining(playerData, cooldownSeconds);
            if (remaining > 0) {
                playerRef.sendMessage(Message.join(
                        Message.raw(tr("ui.races.cooldown.try_again_prefix", "You can swap again in "))
                                .color("#ffffff"),
                        Message.raw(formatDuration(remaining)).color("#ffc300"),
                        Message.raw(".").color("#ffffff")));
                return;
            }
        }

        if (current != null && raceManager != null) {
            playerData.addCompletedRaceForm(raceManager.resolveAscensionPathId(current.getId()));
        }

        playerData.setRaceId(desired.getId());
        if (raceManager != null) {
            playerData.addCompletedRaceForm(raceManager.resolveAscensionPathId(desired.getId()));
        }
        if (raceManager != null) {
            raceManager.markRaceChange(playerData);
        }
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

        if (raceManager != null) {
            raceManager.applyRaceModelIfEnabled(playerData);
        }

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(playerData);
        }

        var player = Universe.get().getPlayer(playerRef.getUuid());
        if (player != null) {
            String display = desired.getDisplayName() == null ? desired.getId() : desired.getDisplayName();
            player.sendMessage(Message.join(
                    Message.raw(tr("ui.races.chat.prefix", "[Races] ")).color("#4fd7f7"),
                    Message.raw(tr("ui.races.chat.changed_prefix", "You are now a ")).color("#ffffff"),
                    Message.raw(display).color("#ffc300"),
                    Message.raw("!").color("#ffffff")));
        }

        this.selectedRaceId = desired.getId();
        refreshRaceUi(playerData, operatorBypass);
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
    }
}
