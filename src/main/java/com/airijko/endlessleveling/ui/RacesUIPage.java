package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page that lists all available races, their stats, and passive bonuses.
 */
public class RacesUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

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
        NavUIHelper.applyNavVersion(ui, playerRef);
        NavUIHelper.bindNavEvents(events);
        applyStaticLabels(ui);
        events.addEventBinding(Activating,
                "#ConfirmRaceButton",
                of("Action", "race:confirm"),
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
            ui.set(baseSelector + " #ChooseRaceButton.Text", tr("ui.races.actions.choose", "CHOOSE"));

            events.addEventBinding(Activating,
                    baseSelector + " #ViewRaceButton",
                    of("Action", "race:view:" + definition.getId()),
                    false);

            if (!isCurrent) {
                events.addEventBinding(Activating,
                        baseSelector + " #ChooseRaceButton",
                        of("Action", "race:choose:" + definition.getId()),
                        false);
            } else {
                ui.set(baseSelector + " #ChooseRaceButton.Visible", false);
            }
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
            ui.set(baseSelector + " #ChooseRaceButton.Text", tr("ui.races.actions.choose", "CHOOSE"));

            ui.set(baseSelector + " #ChooseRaceButton.Visible", !isCurrent);
        }
    }

    private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
        ui.set("#RacesTitleLabel.Text", tr("ui.races.page.title", "Race Archive"));
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
        ui.set("#RaceDetailCooldownWarning.Text",
                tr("ui.races.cooldown.default_warning",
                        "You will be locked for the remaining cooldown after swapping."));
        ui.set("#ConfirmRaceButton.Text", tr("ui.races.actions.swap", "SWAP"));

        ui.set("#RaceAttributeLifeForceLabel.Text", tr("ui.skills.label.life_force", "Life Force"));
        ui.set("#RaceAttributeStrengthLabel.Text", tr("ui.skills.label.strength", "Strength"));
        ui.set("#RaceAttributeSorceryLabel.Text", tr("ui.skills.label.sorcery", "Sorcery"));
        ui.set("#RaceAttributeDefenseLabel.Text", tr("ui.skills.label.defense", "Defense"));
        ui.set("#RaceAttributeHasteLabel.Text", tr("ui.skills.label.haste", "Haste"));
        ui.set("#RaceAttributePrecisionLabel.Text", tr("ui.skills.label.precision", "Precision"));
        ui.set("#RaceAttributeFerocityLabel.Text", tr("ui.skills.label.ferocity", "Ferocity"));
        ui.set("#RaceAttributeStaminaLabel.Text", tr("ui.skills.label.stamina", "Stamina"));
        ui.set("#RaceAttributeFlowLabel.Text", tr("ui.skills.label.flow", "Flow"));
        ui.set("#RaceAttributeDisciplineLabel.Text", tr("ui.skills.label.discipline", "Discipline"));
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
            clearAttributePreview(ui);
            return;
        }

        ui.set("#SelectedRaceLabel.Text", selection.getDisplayName());
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
                ui.append("#RacePassiveEntries", "Pages/Profile/ProfileRacePassiveEntry.ui");
                String base = "#RacePassiveEntries[" + index + "]";
                ui.set(base + " #PassiveName.Text", buildPassiveLabel(passive));
                ui.set(base + " #PassiveValue.Text", formatPassiveDescription(passive, data));
            }
        }

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

    private List<RaceDefinition> getSortedRaces() {
        Collection<RaceDefinition> loaded = raceManager.getLoadedRaces();
        List<RaceDefinition> races = new ArrayList<>(loaded);
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
        String scalingStat = getStringProp(props, "scaling_stat");

        if (type == null) {
            return value == 0.0D ? tr("ui.races.passive.default_name", "Passive") : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> tr("ui.races.passive.desc.xp_bonus", "{0} XP gain", formatPercentValue(value));
            case HEALTH_REGEN -> tr("ui.races.passive.desc.health_regen", "{0} HP/5s", formatPercentValue(value));
            case MANA_REGEN -> tr("ui.races.passive.desc.mana_regen", "{0} mana/5s", formatPercentValue(value));
            case MANA_REGEN_FLAT -> tr("ui.races.passive.desc.mana_regen_flat", "{0} mana/s", formatSigned(value));
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

        if (data.action.startsWith("race:choose:")) {
            String targetId = data.action.substring("race:choose:".length());
            handleRaceChoose(targetId, playerData, ref, store, operatorBypass);
        }
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

        RaceDefinition current = raceManager.getPlayerRace(playerData);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw(tr("ui.races.error.already_selected", "You already belong to that race."))
                    .color("#ff9900"));
            this.selectedRaceId = current.getId();
            rebuild();
            return;
        }

        if (!operatorBypass && raceManager != null) {
            RaceAscensionEligibility eligibility = raceManager.evaluateAscensionEligibility(playerData,
                    desired.getId());
            if (!eligibility.isEligible()) {
                if (!eligibility.getBlockers().isEmpty()) {
                    playerRef.sendMessage(Message.raw(tr("ui.races.error.cannot_ascend", "Cannot switch race yet:"))
                            .color("#ff6666"));
                    for (String blocker : eligibility.getBlockers()) {
                        playerRef.sendMessage(Message.join(
                                Message.raw(" - ").color("#ff6666"),
                                Message.raw(blocker).color("#ffc300")));
                    }
                } else {
                    playerRef.sendMessage(Message.raw(tr("ui.races.error.cannot_ascend_simple",
                            "Cannot switch race yet."))
                            .color("#ff6666"));
                }
                return;
            }
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
