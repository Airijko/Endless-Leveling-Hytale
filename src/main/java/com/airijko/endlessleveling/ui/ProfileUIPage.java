package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.PlayerProfile;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

public class ProfileUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final RaceManager raceManager;
    private final PassiveManager passiveManager;
    private final SkillManager skillManager;
    private final PlayerAttributeManager attributeManager;
    private Integer pendingDeleteSlot;

    public ProfileUIPage(@Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.passiveManager = plugin != null ? plugin.getPassiveManager() : null;
        this.skillManager = plugin != null ? plugin.getSkillManager() : null;
        this.attributeManager = plugin != null ? plugin.getPlayerAttributeManager() : null;
        this.pendingDeleteSlot = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Profile/ProfilePage.ui");
        NavUIHelper.bindNavEvents(events);

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#ProfilesSummary.Text", "Player data unavailable.");
            return;
        }

        events.addEventBinding(Activating, "#NewProfileButton", of("Action", "profile:new"), false);

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());

        updateSummary(ui, playerData);
        buildProfileList(ui, events, playerData);
        updateProfileDetailPanel(ui, playerData, statMap);
    }

    private PlayerData resolvePlayerData() {
        if (playerDataManager == null) {
            LOGGER.atSevere().log("ProfileUIPage: PlayerDataManager is not available");
            return null;
        }

        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            LOGGER.atWarning().log("ProfileUIPage: PlayerData missing for %s", playerRef.getUuid());
            return null;
        }
        return data;
    }

    private void updateSummary(@Nonnull UICommandBuilder ui, @Nonnull PlayerData data) {
        ui.set("#ProfileTitleLabel.Text",
                "Profiles " + data.getProfileCount() + "/" + PlayerData.MAX_PROFILES);
    }

    private void buildProfileList(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull PlayerData data) {
        ui.clear("#ProfileCards");

        if (pendingDeleteSlot != null
                && (!data.hasProfile(pendingDeleteSlot) || data.getProfileCount() <= 1)) {
            pendingDeleteSlot = null;
        }

        List<Map.Entry<Integer, PlayerProfile>> profiles = data.getProfiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        int index = 0;
        for (Map.Entry<Integer, PlayerProfile> entry : profiles) {
            int slot = entry.getKey();
            PlayerProfile profile = entry.getValue();
            boolean active = data.isProfileActive(slot);
            boolean canDelete = data.getProfileCount() > 1 && !active;
            boolean pending = canDelete && pendingDeleteSlot != null && pendingDeleteSlot == slot;

            ui.append("#ProfileCards", "Pages/Profile/ProfileRow.ui");
            String base = "#ProfileCards[" + index + "]";

            ui.set(base + " #SlotLabel.Text", "Slot " + slot);
            ui.set(base + " #ProfileName.Text", profile.getName());
            ui.set(base + " #LevelValue.Text", "Level " + profile.getLevel());
            ui.set(base + " #XpValue.Text", formatNumber(profile.getXp()) + " XP");
            ui.set(base + " #StatusBadge.Text", active ? "ACTIVE" : "");
            ui.set(base + " #ConfirmDeleteLabel.Text", "Delete " + profile.getName() + "?");
            ui.set(base + " #ActionButtons.Visible", !pending);
            ui.set(base + " #ConfirmButtons.Visible", pending);
            ui.set(base + " #DeleteButton.Visible", canDelete);

            ui.set(base + " #SelectButton.Text", active ? "ACTIVE" : "SELECT");
            if (!active) {
                events.addEventBinding(Activating, base + " #SelectButton",
                        of("Action", "profile:select:" + slot), false);
            }
            if (canDelete) {
                events.addEventBinding(Activating, base + " #DeleteButton",
                        of("Action", "profile:delete-prompt:" + slot), false);
                events.addEventBinding(Activating, base + " #ConfirmDeleteButton",
                        of("Action", "profile:delete:" + slot), false);
                events.addEventBinding(Activating, base + " #CancelDeleteButton",
                        of("Action", "profile:delete-cancel"), false);
            }

            index++;
        }
    }

    private void updateProfileDetailPanel(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            EntityStatMap statMap) {
        PlayerProfile profile = resolveActiveProfile(data);
        if (profile == null) {
            clearDetailPanel(ui, "Select a profile to view stats");
            return;
        }

        int slot = data.getActiveProfileIndex();
        ui.set("#DetailTitleLabel.Text", profile.getName());
        ui.set("#DetailSubtitleLabel.Text", "Slot " + slot);
        ui.set("#DetailLevelValue.Text", String.valueOf(profile.getLevel()));
        ui.set("#DetailXpValue.Text", formatNumber(profile.getXp()) + " XP");
        ui.set("#DetailRaceValue.Text", getRaceDisplay(profile));

        applyAttributeDisplay(ui, "#AttributeLifeForceValue", "#AttributeLifeForceLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.LIFE_FORCE, statMap));
        applyAttributeDisplay(ui, "#AttributeStrengthValue", "#AttributeStrengthLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.STRENGTH, statMap));
        applyAttributeDisplay(ui, "#AttributeDefenseValue", "#AttributeDefenseLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.DEFENSE, statMap));
        applyAttributeDisplay(ui, "#AttributeHasteValue", "#AttributeHasteLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.HASTE, statMap));
        applyAttributeDisplay(ui, "#AttributePrecisionValue", "#AttributePrecisionLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.PRECISION, statMap));
        applyAttributeDisplay(ui, "#AttributeFerocityValue", "#AttributeFerocityLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.FEROCITY, statMap));
        applyAttributeDisplay(ui, "#AttributeStaminaValue", "#AttributeStaminaLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.STAMINA, statMap));
        applyAttributeDisplay(ui, "#AttributeIntelligenceValue", "#AttributeIntelligenceLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.INTELLIGENCE, statMap));

        List<SkillPassiveEntry> skillEntries = collectSkillPassiveEntries(data, profile);
        if (skillEntries.isEmpty()) {
            ui.set("#SkillPassiveSummary.Text", "No skill passives selected");
            ui.set("#SkillPassiveSummary.Visible", true);
        } else {
            ui.set("#SkillPassiveSummary.Visible", false);
        }
        populateSkillPassiveEntries(ui, skillEntries);

        RacePassiveSummary raceSummary = buildRacePassiveSummary(profile);
        ui.set("#RacePassiveSummary.Text", raceSummary.summary());
        ui.set("#RacePassiveSummary.Visible", raceSummary.entries().isEmpty());
        populateRacePassiveEntries(ui,
                "#RacePassiveEntries",
                "Pages/Profile/ProfileRacePassiveEntry.ui",
                raceSummary.entries());
    }

    private PlayerProfile resolveActiveProfile(@Nonnull PlayerData data) {
        Map<Integer, PlayerProfile> profiles = data.getProfiles();
        if (profiles.isEmpty()) {
            return null;
        }
        PlayerProfile profile = profiles.get(data.getActiveProfileIndex());
        if (profile != null) {
            return profile;
        }
        return profiles.values().stream().findFirst().orElse(null);
    }

    private void clearDetailPanel(@Nonnull UICommandBuilder ui, @Nonnull String subtitle) {
        ui.set("#DetailTitleLabel.Text", "Selected Profile");
        ui.set("#DetailSubtitleLabel.Text", subtitle);
        ui.set("#DetailLevelValue.Text", "--");
        ui.set("#DetailXpValue.Text", "--");
        ui.set("#DetailRaceValue.Text", "--");
        applyAttributeDisplay(ui, "#AttributeLifeForceValue", "#AttributeLifeForceLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeStrengthValue", "#AttributeStrengthLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeDefenseValue", "#AttributeDefenseLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeHasteValue", "#AttributeHasteLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributePrecisionValue", "#AttributePrecisionLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeFerocityValue", "#AttributeFerocityLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeStaminaValue", "#AttributeStaminaLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeIntelligenceValue", "#AttributeIntelligenceLevel",
                emptyAttributeDisplay());
        ui.set("#SkillPassiveSummary.Text", "No skill passives unlocked");
        ui.set("#SkillPassiveSummary.Visible", true);
        ui.set("#RacePassiveSummary.Text", "Select a profile to view race bonuses");
        ui.set("#RacePassiveSummary.Visible", true);
        ui.clear("#SkillPassiveEntries");
        ui.clear("#RacePassiveEntries");
    }

    private AttributeDisplay getAttributeDisplay(@Nonnull PlayerData data,
            @Nonnull PlayerProfile profile,
            @Nonnull SkillAttributeType type,
            EntityStatMap statMap) {
        int level = profile.getAttributes().getOrDefault(type, 0);
        String levelText = "Lv " + level;
        String detail = "";

        if (skillManager != null) {
            if (isResourceAttribute(type)) {
                double total = resolveResourceTotal(type, data, statMap);
                detail = Double.isNaN(total) ? "--" : formatNumber(total) + " " + resourceLabel(type);
            } else {
                detail = switch (type) {
                    case STRENGTH -> {
                        SkillManager.StrengthBreakdown breakdown = skillManager.getStrengthBreakdown(data, level);
                        yield "+" + formatNumber(breakdown.totalValue()) + "% Damage";
                    }
                    case DEFENSE -> {
                        SkillManager.DefenseBreakdown breakdown = skillManager.getDefenseBreakdown(data, level);
                        double reduction = breakdown.resistance() * 100.0f;
                        yield formatNumber(reduction) + "% Reduction";
                    }
                    case HASTE -> {
                        SkillManager.HasteBreakdown breakdown = skillManager.getHasteBreakdown(data, level);
                        double percent = (breakdown.totalMultiplier() - 1.0f) * 100.0f;
                        yield "+" + formatNumber(percent) + "% Speed";
                    }
                    case PRECISION ->
                        formatNumber(level * skillManager.getSkillAttributeConfigValue(type)) + "% Crit Chance";
                    case FEROCITY ->
                        "+" + formatNumber(level * skillManager.getSkillAttributeConfigValue(type)) + "% Crit Damage";
                    default -> "--";
                };
            }
        }

        if (detail == null || detail.isBlank()) {
            detail = "--";
        }
        return new AttributeDisplay(detail, levelText);
    }

    private String getRaceDisplay(@Nonnull PlayerProfile profile) {
        String raceId = profile.getRaceId();
        if (raceManager == null) {
            return raceId == null || raceId.isBlank() ? PlayerData.DEFAULT_RACE_ID : raceId;
        }
        RaceDefinition definition = raceManager.getRace(raceId);
        if (definition != null) {
            return definition.getDisplayName();
        }
        return raceId == null || raceId.isBlank() ? raceManager.getDefaultRaceId() : raceId;
    }

    private List<SkillPassiveEntry> collectSkillPassiveEntries(@Nonnull PlayerData data,
            @Nonnull PlayerProfile profile) {
        List<SkillPassiveEntry> entries = new ArrayList<>();
        for (PassiveType type : PassiveType.values()) {
            int level = profile.getPassiveLevel(type);
            if (level <= 0) {
                continue;
            }
            String formattedValue = formatSkillPassiveValue(data, type);
            String effectText = formattedValue.isBlank() ? "--" : formattedValue;
            entries.add(new SkillPassiveEntry(type.getDisplayName(), effectText, "Lv " + level));
        }
        return entries;
    }

    private String formatSkillPassiveValue(@Nonnull PlayerData data, @Nonnull PassiveType type) {
        if (passiveManager == null) {
            return "";
        }
        PassiveManager.PassiveSnapshot snapshot = passiveManager.getSnapshot(data, type);
        if (snapshot == null || snapshot.value() <= 0.0D) {
            return "";
        }
        return type.formatValue(snapshot.value());
    }

    private RacePassiveSummary buildRacePassiveSummary(@Nonnull PlayerProfile profile) {
        List<PassiveEntry> entries = new ArrayList<>();
        if (raceManager == null || !raceManager.isEnabled()) {
            return new RacePassiveSummary("Race bonuses unavailable", entries);
        }
        RaceDefinition definition = raceManager.getRace(profile.getRaceId());
        if (definition == null) {
            String raceId = profile.getRaceId() == null ? PlayerData.DEFAULT_RACE_ID : profile.getRaceId();
            return new RacePassiveSummary("Race: " + raceId, entries);
        }
        List<RacePassiveDefinition> passives = definition.getPassiveDefinitions();
        if (passives != null) {
            for (RacePassiveDefinition passive : passives) {
                if (passive == null || passive.type() == null) {
                    continue;
                }
                String labelText;
                if (passive.type() == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN
                        && passive.attributeType() != null) {
                    labelText = toDisplay(passive.attributeType().name());
                } else {
                    StringBuilder label = new StringBuilder(toDisplay(passive.type().name()));
                    if (passive.attributeType() != null) {
                        label.append(" (" + toDisplay(passive.attributeType().name()) + ")");
                    }
                    labelText = label.toString();
                }
                String valueText = formatRacePassiveValue(passive, profile);
                entries.add(new PassiveEntry(labelText, valueText));
            }
        }
        String summary = entries.isEmpty()
                ? definition.getDisplayName() + " bonuses active"
                : definition.getDisplayName() + " passives";
        return new RacePassiveSummary(summary, entries);
    }

    private void populateRacePassiveEntries(@Nonnull UICommandBuilder ui,
            @Nonnull String containerSelector,
            @Nonnull String template,
            @Nonnull List<PassiveEntry> entries) {
        ui.clear(containerSelector);
        for (int i = 0; i < entries.size(); i++) {
            PassiveEntry entry = entries.get(i);
            ui.append(containerSelector, template);
            String base = containerSelector + "[" + i + "]";
            ui.set(base + " #PassiveName.Text", entry.label());
            ui.set(base + " #PassiveValue.Text", entry.value());
        }
    }

    private void populateSkillPassiveEntries(@Nonnull UICommandBuilder ui,
            @Nonnull List<SkillPassiveEntry> entries) {
        ui.clear("#SkillPassiveEntries");
        for (int i = 0; i < entries.size(); i++) {
            SkillPassiveEntry entry = entries.get(i);
            ui.append("#SkillPassiveEntries", "Pages/Profile/ProfileSkillPassiveEntry.ui");
            String base = "#SkillPassiveEntries[" + i + "]";
            ui.set(base + " #PassiveName.Text", entry.label());
            ui.set(base + " #PassiveValue.Text", entry.value());
            ui.set(base + " #PassiveLevel.Text", entry.level());
        }
    }

    private void applyAttributeDisplay(@Nonnull UICommandBuilder ui,
            @Nonnull String valueSelector,
            @Nonnull String levelSelector,
            @Nonnull AttributeDisplay display) {
        ui.set(valueSelector + ".Text", display.value());
        ui.set(levelSelector + ".Text", display.level());
    }

    private AttributeDisplay emptyAttributeDisplay() {
        return new AttributeDisplay("--", "--");
    }

    private String toDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
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

    private boolean isResourceAttribute(@Nonnull SkillAttributeType type) {
        return type == SkillAttributeType.LIFE_FORCE
                || type == SkillAttributeType.STAMINA
                || type == SkillAttributeType.INTELLIGENCE;
    }

    private String resourceLabel(@Nonnull SkillAttributeType type) {
        return switch (type) {
            case LIFE_FORCE -> "Health";
            case STAMINA -> "Stamina";
            case INTELLIGENCE -> "Mana";
            default -> type.name();
        };
    }

    private double resolveResourceTotal(@Nonnull SkillAttributeType type,
            @Nonnull PlayerData playerData,
            EntityStatMap statMap) {
        if (skillManager == null) {
            return Double.NaN;
        }
        PlayerAttributeManager.AttributeSlot slot = toAttributeSlot(type);
        if (slot == null) {
            return Double.NaN;
        }

        double liveStat = getStatMax(statMap, slot);
        if (!Double.isNaN(liveStat)) {
            return liveStat;
        }

        if (attributeManager == null) {
            return Double.NaN;
        }

        double skillBonus = switch (type) {
            case LIFE_FORCE -> skillManager.calculatePlayerHealth(playerData);
            case STAMINA -> skillManager.calculatePlayerStamina(playerData);
            case INTELLIGENCE -> skillManager.calculatePlayerIntelligence(playerData);
            default -> 0.0D;
        };

        double raceBase = attributeManager.getRaceAttribute(playerData, type, 0.0D);
        double total = raceBase + skillBonus;
        return total > 0.0D ? total : Double.NaN;
    }

    private PlayerAttributeManager.AttributeSlot toAttributeSlot(@Nonnull SkillAttributeType type) {
        return switch (type) {
            case LIFE_FORCE -> PlayerAttributeManager.AttributeSlot.LIFE_FORCE;
            case STAMINA -> PlayerAttributeManager.AttributeSlot.STAMINA;
            case INTELLIGENCE -> PlayerAttributeManager.AttributeSlot.INTELLIGENCE;
            default -> null;
        };
    }

    private double getStatMax(EntityStatMap statMap, PlayerAttributeManager.AttributeSlot slot) {
        if (statMap == null || slot == null) {
            return Double.NaN;
        }
        EntityStatValue statValue = statMap.get(slot.statIndex());
        if (statValue == null) {
            return Double.NaN;
        }
        double max = statValue.getMax();
        return max > 0.0D ? max : Double.NaN;
    }

    private record PassiveEntry(String label, String value) {
    }

    private record SkillPassiveEntry(String label, String value, String level) {
    }

    private record RacePassiveSummary(String summary, List<PassiveEntry> entries) {
    }

    private record AttributeDisplay(String value, String level) {
    }

    private String formatRacePassiveValue(@Nonnull RacePassiveDefinition passive,
            @Nonnull PlayerProfile profile) {
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
            case MANA_REGEN -> formatPercentValue(value) + " max mana/5s";
            case HEALING_BONUS -> formatPercentValue(value) + " healing";
            case SPECIAL_CHARGE_BONUS -> formatPercentValue(value) + " charge rate";
            case SECOND_WIND -> appendDetails(
                    formatPercentValue(value) + " heal",
                    formatThresholdDetail(threshold, "HP"),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case FIRST_STRIKE -> appendDetails(
                    formatPercentValue(value) + " first hit",
                    formatCooldownDetail(cooldown));
            case INNATE_ATTRIBUTE_GAIN -> formatInnateAttributeValue(passive, profile);
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

    private String formatInnateAttributeValue(@Nonnull RacePassiveDefinition passive,
            @Nonnull PlayerProfile profile) {
        SkillAttributeType attributeType = passive.attributeType();
        double gain = passive.value();
        String gainText = formatSigned(gain);
        if (attributeType == null) {
            return gainText;
        }
        double current = profile.getAttributes().getOrDefault(attributeType, 0);
        double total = current + gain;
        return gainText + " (Total " + formatNumber(total) + ")";
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

        if (data.action == null || data.action.isEmpty() || !data.action.startsWith("profile:")) {
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("Unable to load your profiles right now.").color("#ff0000"));
            return;
        }

        ProfileActionOutcome outcome = handleProfileAction(data.action, playerData);
        if (outcome.requiresSave() && playerDataManager != null) {
            playerDataManager.save(playerData);
        }
        if (outcome.requiresRebuild()) {
            rebuild();
        }
    }

    private ProfileActionOutcome handleProfileAction(@Nonnull String action,
            @Nonnull PlayerData playerData) {
        String payload = action.substring("profile:".length());

        try {
            if ("new".equalsIgnoreCase(payload)) {
                return handleNewProfile(playerData);
            }
            if (payload.startsWith("select:")) {
                return handleSelectProfile(playerData, payload);
            }
            if (payload.startsWith("delete-prompt:")) {
                return handleDeletePrompt(playerData, payload);
            }
            if ("delete-cancel".equalsIgnoreCase(payload)) {
                return handleDeleteCancel();
            }
            if (payload.startsWith("delete:")) {
                return handleDeleteRequest(playerData, payload);
            }
        } catch (Exception ex) {
            LOGGER.atSevere().withCause(ex).log("ProfileUIPage: error handling action %s", action);
            playerRef.sendMessage(Message.raw("Something went wrong handling that request.").color("#ff0000"));
        }

        return new ProfileActionOutcome(false, false);
    }

    private ProfileActionOutcome handleNewProfile(@Nonnull PlayerData playerData) {
        if (playerData.getProfileCount() >= PlayerData.MAX_PROFILES) {
            playerRef.sendMessage(Message.raw("All profile slots are already in use. Delete one first.")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        int nextSlot = playerData.findNextAvailableProfileSlot();
        if (!PlayerData.isValidProfileIndex(nextSlot)) {
            playerRef.sendMessage(Message.raw("Unable to find an open slot right now.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        boolean created = playerData.createProfile(nextSlot, PlayerData.defaultProfileName(nextSlot), false, true);
        if (!created) {
            playerRef.sendMessage(Message.raw("Could not create that profile slot.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        playerRef.sendMessage(Message.raw("Created and activated profile slot " + nextSlot + ".")
                .color("#4fd7f7"));
        return new ProfileActionOutcome(true, true);
    }

    private ProfileActionOutcome handleSelectProfile(@Nonnull PlayerData playerData, @Nonnull String payload) {
        int slot = parseSlot(payload, "select:");
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " has not been created yet.")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " is already active.").color("#4fd7f7"));
            return new ProfileActionOutcome(false, false);
        }

        PlayerData.ProfileSwitchResult result = playerData.switchProfile(slot);
        if (result == PlayerData.ProfileSwitchResult.SWITCHED_EXISTING) {
            playerRef.sendMessage(Message.raw(
                    "Switched to profile slot " + slot + " (" + playerData.getProfileName(slot) + ").")
                    .color("#00ff00"));
            return new ProfileActionOutcome(true, true);
        }

        playerRef.sendMessage(Message.raw("Unable to switch to that slot right now.").color("#ff0000"));
        return new ProfileActionOutcome(false, false);
    }

    private ProfileActionOutcome handleDeletePrompt(@Nonnull PlayerData playerData,
            @Nonnull String payload) {
        int slot = parseSlot(payload, "delete-prompt:");
        if (!canDeleteSlot(playerData, slot)) {
            return new ProfileActionOutcome(false, false);
        }

        pendingDeleteSlot = slot;
        playerRef.sendMessage(Message.raw("Press CONFIRM to delete profile slot " + slot + ".")
                .color("#ff9900"));
        return new ProfileActionOutcome(false, true);
    }

    private ProfileActionOutcome handleDeleteRequest(@Nonnull PlayerData playerData,
            @Nonnull String payload) {
        int slot = parseSlot(payload, "delete:");
        if (!canDeleteSlot(playerData, slot)) {
            return new ProfileActionOutcome(false, false);
        }
        if (pendingDeleteSlot == null || pendingDeleteSlot != slot) {
            playerRef.sendMessage(Message.raw("Please confirm the deletion of slot " + slot + " first.")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        boolean deleted = playerData.deleteProfile(slot);
        if (!deleted) {
            playerRef.sendMessage(Message.raw("Could not delete that profile slot right now.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        clearPendingDelete();
        playerRef.sendMessage(Message.raw("Deleted profile slot " + slot + ".").color("#4fd7f7"));
        return new ProfileActionOutcome(true, true);
    }

    private ProfileActionOutcome handleDeleteCancel() {
        if (pendingDeleteSlot == null) {
            return new ProfileActionOutcome(false, false);
        }
        clearPendingDelete();
        playerRef.sendMessage(Message.raw("Profile deletion canceled.").color("#4fd7f7"));
        return new ProfileActionOutcome(false, true);
    }

    private boolean canDeleteSlot(@Nonnull PlayerData playerData, int slot) {
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff0000"));
            return false;
        }
        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " is already empty.").color("#ff9900"));
            return false;
        }
        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(Message.raw("Switch to a different profile before deleting slot " + slot + ".")
                    .color("#ff9900"));
            return false;
        }
        if (playerData.getProfileCount() <= 1) {
            playerRef.sendMessage(Message.raw("You must keep at least one profile slot.").color("#ff0000"));
            return false;
        }
        return true;
    }

    private void clearPendingDelete() {
        pendingDeleteSlot = null;
    }

    private int parseSlot(@Nonnull String payload, @Nonnull String prefix) {
        try {
            return Integer.parseInt(payload.substring(prefix.length()));
        } catch (Exception ex) {
            LOGGER.atWarning().log("ProfileUIPage: invalid slot payload %s", payload);
            return -1;
        }
    }

    private String formatNumber(double value) {
        String formatted = String.format("%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    private record ProfileActionOutcome(boolean requiresSave, boolean requiresRebuild) {
    }
}
