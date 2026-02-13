package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
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
import java.util.EnumMap;
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

    private static final EnumMap<SkillAttributeType, String> ATTRIBUTE_TAGLINES = new EnumMap<>(
            SkillAttributeType.class);

    static {
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.LIFE_FORCE, "Base health");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.STRENGTH, "Damage scaling");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.SORCERY, "Magic damage");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.DEFENSE, "Damage reduction");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.HASTE, "Movement speed");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.PRECISION, "Crit chance");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.FEROCITY, "Crit damage");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.STAMINA, "Base stamina");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.INTELLIGENCE, "Base mana");
        ATTRIBUTE_TAGLINES.put(SkillAttributeType.DISCIPLINE, "XP gain");
    }

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
        NavUIHelper.applyNavVersion(ui);
        NavUIHelper.bindNavEvents(events);
        events.addEventBinding(Activating,
                "#ConfirmRaceButton",
                of("Action", "race:confirm"),
                false);

        if (raceManager == null || !raceManager.isEnabled()) {
            ui.set("#SelectedRaceLabel.Text", "Races Offline");
            ui.set("#SelectedRaceSubtitle.Text", "Races are currently disabled in config.yml.");
            ui.set("#RaceSwapCooldownValue.Text", "Disabled");
            ui.set("#RaceSwapCooldownHint.Text", "Enable races to manage identities.");
            ui.set("#RaceCountLabel.Text", "0 available");
            ui.set("#CurrentRaceValue.Text", "--");
            ui.clear("#RaceRows");
            ui.clear("#RacePassiveEntries");
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#SelectedRaceLabel.Text", "Player data unavailable");
            ui.set("#SelectedRaceSubtitle.Text", "Unable to load your race information right now.");
            ui.set("#RaceSwapCooldownValue.Text", "--");
            ui.set("#RaceSwapCooldownHint.Text", "Try reopening this page in a few moments.");
            ui.set("#CurrentRaceValue.Text", "--");
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

    private void updateStatusCard(@Nonnull UICommandBuilder ui, RaceDefinition activeRace) {
        String current = activeRace != null ? activeRace.getDisplayName() : "None selected";
        ui.set("#CurrentRaceValue.Text", current);
    }

    private void updateCooldownCard(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            boolean operatorBypass) {
        if (operatorBypass) {
            ui.set("#RaceSwapCooldownValue.Text", "Bypassed");
            ui.set("#RaceSwapCooldownHint.Text", "Operator bypass active.");
            return;
        }
        long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
        long remaining = computeCooldownRemaining(data, cooldownSeconds);
        if (remaining > 0) {
            ui.set("#RaceSwapCooldownValue.Text", formatDuration(remaining));
            ui.set("#RaceSwapCooldownHint.Text", "Swap in cooldown");
        } else {
            ui.set("#RaceSwapCooldownValue.Text", "Ready");
            if (cooldownSeconds > 0) {
                ui.set("#RaceSwapCooldownHint.Text",
                        "Swapping will trigger a " + formatDuration(cooldownSeconds) + " cooldown.");
            } else {
                ui.set("#RaceSwapCooldownHint.Text", "Race swapping is unrestricted right now.");
            }
        }
    }

    private void buildRaceList(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull PlayerData data,
            RaceDefinition activeRace) {
        ui.clear("#RaceRows");

        List<RaceDefinition> races = getSortedRaces();
        ui.set("#RaceCountLabel.Text", races.size() + (races.size() == 1 ? " race" : " races"));

        for (int index = 0; index < races.size(); index++) {
            RaceDefinition definition = races.get(index);
            ui.append("#RaceRows", "Pages/Races/RaceRow.ui");
            String baseSelector = "#RaceRows[" + index + "]";

            String displayName = definition.getDisplayName();
            boolean isCurrent = activeRace != null && activeRace.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedRaceMatches(definition.getId());

            ui.set(baseSelector + " #RaceName.Text", displayName);

            String selectionStatus = isCurrent ? "CURRENT" : (isSelected ? "VIEWING" : "");
            boolean hasStatus = !selectionStatus.isEmpty();
            ui.set(baseSelector + " #RaceSelectionStatus.Visible", hasStatus);
            ui.set(baseSelector + " #RaceSelectionStatus.Text", selectionStatus);

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

    private void updateRaceDetailPanel(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            RaceDefinition activeRace,
            boolean operatorBypass) {
        RaceDefinition selection = resolveSelection(activeRace);
        if (selection == null) {
            ui.set("#SelectedRaceLabel.Text", "Select a Race");
            ui.set("#SelectedRaceSubtitle.Text", "Choose a race on the left to preview its identity.");
            ui.set("#RaceLoreText.Text", "Lore unavailable.");
            ui.clear("#RacePassiveEntries");
            ui.set("#RacePassiveSummary.Visible", true);
            ui.set("#RacePassiveSummary.Text", "No race selected.");
            clearAttributePreview(ui);
            return;
        }

        ui.set("#SelectedRaceLabel.Text", selection.getDisplayName());
        ui.set("#SelectedRaceSubtitle.Text",
                selection == activeRace ? "Currently active" : "Preview only");
        String lore = selection.getDescription();
        ui.set("#RaceLoreText.Text",
                lore == null || lore.isBlank() ? "No lore provided for this race." : lore);

        applyAttributePreview(ui, selection, SkillAttributeType.LIFE_FORCE, "#RaceAttributeLifeForce");
        applyAttributePreview(ui, selection, SkillAttributeType.STRENGTH, "#RaceAttributeStrength");
        applyAttributePreview(ui, selection, SkillAttributeType.SORCERY, "#RaceAttributeSorcery");
        applyAttributePreview(ui, selection, SkillAttributeType.DEFENSE, "#RaceAttributeDefense");
        applyAttributePreview(ui, selection, SkillAttributeType.HASTE, "#RaceAttributeHaste");
        applyAttributePreview(ui, selection, SkillAttributeType.PRECISION, "#RaceAttributePrecision");
        applyAttributePreview(ui, selection, SkillAttributeType.FEROCITY, "#RaceAttributeFerocity");
        applyAttributePreview(ui, selection, SkillAttributeType.STAMINA, "#RaceAttributeStamina");
        applyAttributePreview(ui, selection, SkillAttributeType.INTELLIGENCE, "#RaceAttributeIntelligence");
        applyAttributePreview(ui, selection, SkillAttributeType.DISCIPLINE, "#RaceAttributeDiscipline");

        List<RacePassiveDefinition> passives = selection.getPassiveDefinitions();
        if (passives == null || passives.isEmpty()) {
            ui.set("#RacePassiveSummary.Visible", true);
            ui.set("#RacePassiveSummary.Text", "This race does not define passive bonuses.");
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
            ui.set("#RaceDetailCooldownWarning.Text", "Operator bypass active. Swapping is immediate.");
            return;
        }

        long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
        long remaining = computeCooldownRemaining(data, cooldownSeconds);
        if (remaining > 0) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    "Swap unlocks in " + formatDuration(remaining) + ".");
        } else if (cooldownSeconds > 0) {
            ui.set("#RaceDetailCooldownWarning.Text",
                    "Swapping will trigger a " + formatDuration(cooldownSeconds) + " cooldown.");
        } else {
            ui.set("#RaceDetailCooldownWarning.Text", "Swapping is unrestricted right now.");
        }
    }

    private void clearAttributePreview(@Nonnull UICommandBuilder ui) {
        ui.set("#RaceAttributeLifeForceValue.Text", "--");
        ui.set("#RaceAttributeStrengthValue.Text", "--");
        ui.set("#RaceAttributeSorceryValue.Text", "--");
        ui.set("#RaceAttributeDefenseValue.Text", "--");
        ui.set("#RaceAttributeHasteValue.Text", "--");
        ui.set("#RaceAttributePrecisionValue.Text", "--");
        ui.set("#RaceAttributeFerocityValue.Text", "--");
        ui.set("#RaceAttributeStaminaValue.Text", "--");
        ui.set("#RaceAttributeIntelligenceValue.Text", "--");
        ui.set("#RaceAttributeDisciplineValue.Text", "--");

        ui.set("#RaceAttributeLifeForceNote.Text", "--");
        ui.set("#RaceAttributeStrengthNote.Text", "--");
        ui.set("#RaceAttributeSorceryNote.Text", "--");
        ui.set("#RaceAttributeDefenseNote.Text", "--");
        ui.set("#RaceAttributeHasteNote.Text", "--");
        ui.set("#RaceAttributePrecisionNote.Text", "--");
        ui.set("#RaceAttributeFerocityNote.Text", "--");
        ui.set("#RaceAttributeStaminaNote.Text", "--");
        ui.set("#RaceAttributeIntelligenceNote.Text", "--");
        ui.set("#RaceAttributeDisciplineNote.Text", "--");
    }

    private void applyAttributePreview(@Nonnull UICommandBuilder ui,
            @Nonnull RaceDefinition race,
            @Nonnull SkillAttributeType type,
            @Nonnull String selectorPrefix) {
        boolean hasAttribute = race.getBaseAttributes().containsKey(type);
        double value = race.getBaseAttribute(type, 0.0D);
        String formatted = hasAttribute ? formatRaceAttributeValue(type, value) : "--";
        ui.set(selectorPrefix + "Value.Text", formatted);
        ui.set(selectorPrefix + "Note.Text",
                ATTRIBUTE_TAGLINES.getOrDefault(type, toDisplay(type.name())));
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
            return "--";
        }

        return switch (type) {
            case LIFE_FORCE -> formatResourceBase(value);
            case STAMINA -> formatResourceBase(value);
            case INTELLIGENCE -> formatResourceBase(value);
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
            return "Passive";
        }
        if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN && passive.attributeType() != null) {
            return toDisplay(passive.attributeType().name());
        }
        if (passive.attributeType() != null) {
            return toDisplay(type.name()) + " (" + toDisplay(passive.attributeType().name()) + ")";
        }
        return toDisplay(type.name());
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

        if (type == null) {
            return value == 0.0D ? "Passive" : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> formatPercentValue(value) + " XP gain";
            case HEALTH_REGEN -> formatPercentValue(value) + " HP/5s";
            case MANA_REGEN -> formatPercentValue(value) + " mana/5s";
            case HEALING_BONUS -> formatPercentValue(value) + " healing";
            case SPECIAL_CHARGE_BONUS -> formatPercentValue(value) + " charge rate";
            case SECOND_WIND -> appendDetails(
                    formatPercentValue(value) + " heal",
                    formatThresholdDetail(threshold, "HP"),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case FIRST_STRIKE -> appendDetails(
                    formatPercentValue(value) + " opener",
                    formatCooldownDetail(cooldown));
            case INNATE_ATTRIBUTE_GAIN -> formatInnatePreview(passive, playerData);
            case ADRENALINE -> appendDetails(
                    formatPercentValue(value) + " stamina",
                    formatThresholdDetail(threshold, "stamina"),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case BERZERKER -> appendDetails(
                    formatPercentValue(value) + " damage",
                    formatThresholdDetail(threshold, "HP"));
            case RETALIATION -> appendDetails(
                    formatPercentValue(value) + " reflect",
                    formatWindowDetail(window),
                    formatCooldownDetail(cooldown));
            case EXECUTIONER -> appendDetails(
                    formatPercentValue(value) + " finisher",
                    formatThresholdDetail(threshold, "target HP"),
                    formatCooldownDetail(cooldown));
            case SWIFTNESS -> appendDetails(
                    formatPercentValue(value) + " speed",
                    formatDurationDetail(duration),
                    formatStacksDetail(stacks));
        };
    }

    private String formatInnatePreview(@Nonnull RacePassiveDefinition passive,
            @Nonnull PlayerData playerData) {
        double perLevel = passive.value();
        String perLevelText = formatSigned(perLevel) + " per level";

        int level = playerData == null ? 1 : Math.max(1, playerData.getLevel());
        double total = perLevel * level;
        String totalText = formatSigned(total);
        return perLevelText + " (Total " + totalText + " @ Lv " + level + ")";
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
        return "<" + formatNumber(ratio * 100.0D) + "% " + scope;
    }

    private String formatDurationDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s duration";
    }

    private String formatCooldownDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s cd";
    }

    private String formatWindowDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s window";
    }

    private String formatStacksDetail(Double stacks) {
        if (stacks == null) {
            return null;
        }
        return formatNumber(stacks) + " stacks";
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
            return "0s";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h");
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(minutes).append("m");
        }
        if (remainingSeconds > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(remainingSeconds).append("s");
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
            playerRef.sendMessage(Message.raw("Unable to load your race info right now.").color("#ff6666"));
            return;
        }

        boolean operatorBypass = OperatorHelper.isOperator(playerRef);

        if (data.action.startsWith("race:view:")) {
            String targetId = data.action.substring("race:view:".length());
            if (targetId != null && !targetId.isBlank()) {
                this.selectedRaceId = targetId.trim();
                rebuild();
            }
            return;
        }

        if (data.action.equals("race:confirm")) {
            if (selectedRaceId == null || selectedRaceId.isBlank()) {
                playerRef.sendMessage(Message.raw("Select a race to swap into.").color("#ff9900"));
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
            playerRef.sendMessage(Message.raw("Races are disabled.").color("#ff6666"));
            return;
        }

        RaceDefinition desired = raceManager.findRaceByUserInput(targetRaceId);
        if (desired == null) {
            playerRef.sendMessage(Message.raw("Unknown race: " + targetRaceId).color("#ff6666"));
            return;
        }

        RaceDefinition current = raceManager.getPlayerRace(playerData);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw("You already belong to that race.").color("#ff9900"));
            this.selectedRaceId = current.getId();
            rebuild();
            return;
        }

        if (!operatorBypass) {
            long cooldownSeconds = raceManager.getChooseRaceCooldownSeconds();
            long remaining = computeCooldownRemaining(playerData, cooldownSeconds);
            if (remaining > 0) {
                playerRef.sendMessage(Message.join(
                        Message.raw("You can swap again in ").color("#ffffff"),
                        Message.raw(formatDuration(remaining)).color("#ffc300"),
                        Message.raw(".").color("#ffffff")));
                return;
            }
        }

        if (!operatorBypass && raceManager != null && !raceManager.hasRaceSwitchesRemaining(playerData)) {
            int remaining = raceManager.getRemainingRaceSwitches(playerData);
            playerRef.sendMessage(Message.raw("No race changes remaining.").color("#ff6666"));
            return;
        }

        playerData.setRaceId(desired.getId());
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

        var player = Universe.get().getPlayer(playerRef.getUuid());
        if (player != null) {
            String display = desired.getDisplayName() == null ? desired.getId() : desired.getDisplayName();
            player.sendMessage(Message.join(
                    Message.raw("[Races] ").color("#4fd7f7"),
                    Message.raw("You are now a ").color("#ffffff"),
                    Message.raw(display).color("#ffc300"),
                    Message.raw("!").color("#ffffff")));
        }

        this.selectedRaceId = desired.getId();
        rebuild();
    }
}
