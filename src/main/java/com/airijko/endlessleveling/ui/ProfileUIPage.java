package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.PlayerProfile;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.PartyManager;
import com.airijko.endlessleveling.managers.PassiveManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.util.Lang;
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
    private static final String PASSIVE_ENTRY_TEMPLATE = "Pages/Profile/ProfileRacePassiveEntry.ui";
    private static final String TIER_COLOR_MYTHIC = "#7851a9";
    private static final String TIER_COLOR_ELITE = "#89cff0";
    private static final String TIER_COLOR_DEFAULT = "#ffc300";

    private final PlayerDataManager playerDataManager;
    private final RaceManager raceManager;
    private final SkillManager skillManager;
    private final PlayerAttributeManager attributeManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final PassiveManager passiveManager;
    private final PlayerRaceStatSystem playerRaceStatSystem;
    private final PartyManager partyManager;
    private final AugmentManager augmentManager;
    private Integer pendingDeleteSlot;

    public ProfileUIPage(@Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.skillManager = plugin != null ? plugin.getSkillManager() : null;
        this.attributeManager = plugin != null ? plugin.getPlayerAttributeManager() : null;
        this.archetypePassiveManager = plugin != null ? plugin.getArchetypePassiveManager() : null;
        this.passiveManager = plugin != null ? plugin.getPassiveManager() : null;
        this.playerRaceStatSystem = plugin != null ? plugin.getPlayerRaceStatSystem() : null;
        this.partyManager = plugin != null ? plugin.getPartyManager() : null;
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.pendingDeleteSlot = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Profile/ProfilePage.ui");
        NavUIHelper.bindNavEvents(events);
        NavUIHelper.applyNavVersion(ui, playerRef);

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#ProfilesSummary.Text", tr("ui.profile.playerdata.unavailable", "Player data unavailable."));
            return;
        }

        applyStaticLabels(ui);

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
                tr("ui.profile.list.title", "Profiles {0}/{1}", data.getProfileCount(), PlayerData.MAX_PROFILES));
    }

    private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
        ui.set("#DetailTitleLabel.Text", tr("ui.profile.detail.title", "Selected Profile"));
        ui.set("#DetailSubtitleLabel.Text", tr("ui.profile.detail.subtitle.default", "Select a profile to view stats"));

        ui.set("#AttributeLifeForceLabel.Text", tr("ui.skills.label.life_force", "Life Force"));
        ui.set("#AttributeStrengthLabel.Text", tr("ui.skills.label.strength", "Strength"));
        ui.set("#AttributeSorceryLabel.Text", tr("ui.skills.label.sorcery", "Sorcery"));
        ui.set("#AttributeDefenseLabel.Text", tr("ui.skills.label.defense", "Defense"));
        ui.set("#AttributeHasteLabel.Text", tr("ui.skills.label.haste", "Haste"));
        ui.set("#AttributePrecisionLabel.Text", tr("ui.skills.label.precision", "Precision"));
        ui.set("#AttributeFerocityLabel.Text", tr("ui.skills.label.ferocity", "Ferocity"));
        ui.set("#AttributeStaminaLabel.Text", tr("ui.skills.label.stamina", "Stamina"));
        ui.set("#AttributeFlowLabel.Text", tr("ui.skills.label.flow", "Flow"));
        ui.set("#AttributeDisciplineLabel.Text", tr("ui.skills.label.discipline", "Discipline"));
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

            ui.set(base + " #SlotLabel.Text", tr("ui.profile.list.slot", "Slot {0}", slot));
            ui.set(base + " #ProfileName.Text", profile.getName());
            ui.set(base + " #LevelValue.Text", tr("ui.profile.list.level", "Level {0}", profile.getLevel()));
            ui.set(base + " #XpValue.Text", tr("ui.profile.list.xp", "{0} XP", formatNumber(profile.getXp())));
            ui.set(base + " #StatusBadge.Text", active ? tr("ui.profile.list.status.active", "ACTIVE") : "");
            ui.set(base + " #ConfirmDeleteLabel.Text",
                    tr("ui.profile.list.delete.confirm", "Delete {0}?", profile.getName()));
            ui.set(base + " #ActionButtons.Visible", !pending);
            ui.set(base + " #ConfirmButtons.Visible", pending);
            ui.set(base + " #DeleteButton.Visible", canDelete);

            ui.set(base + " #SelectButton.Text", active ? tr("ui.profile.list.status.active", "ACTIVE")
                    : tr("ui.profile.list.select", "SELECT"));
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
            clearDetailPanel(ui, tr("ui.profile.detail.subtitle.default", "Select a profile to view stats"));
            return;
        }

        int slot = data.getActiveProfileIndex();
        ui.set("#DetailTitleLabel.Text", profile.getName());
        ui.set("#DetailSubtitleLabel.Text", tr("ui.profile.list.slot", "Slot {0}", slot));
        ui.set("#DetailLevelValue.Text", String.valueOf(profile.getLevel()));
        ui.set("#DetailXpValue.Text", tr("ui.profile.list.xp", "{0} XP", formatNumber(profile.getXp())));
        ui.set("#DetailRaceValue.Text", getRaceDisplay(profile));

        applyAttributeDisplay(ui, "#AttributeLifeForceValue", "#AttributeLifeForceLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.LIFE_FORCE, statMap));
        applyAttributeDisplay(ui, "#AttributeStrengthValue", "#AttributeStrengthLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.STRENGTH, statMap));
        applyAttributeDisplay(ui, "#AttributeSorceryValue", "#AttributeSorceryLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.SORCERY, statMap));
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
        applyAttributeDisplay(ui, "#AttributeFlowValue", "#AttributeFlowLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.FLOW, statMap));
        applyAttributeDisplay(ui, "#AttributeDisciplineValue", "#AttributeDisciplineLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.DISCIPLINE, statMap));

        AggregatedPassiveSections passiveSections = buildAggregatedPassiveSections(data, profile);
        renderPassiveSection(ui,
                "#PassiveSummary",
                "#PassiveEntries",
                passiveSections.passiveSummary(),
                passiveSections.passiveEntries());
        renderPassiveSection(ui,
                "#InnatePassiveSummary",
                "#InnatePassiveEntries",
                passiveSections.innatePassiveSummary(),
                passiveSections.innatePassiveEntries());
        renderPassiveSection(ui,
                "#InnateAttributeSummary",
                "#InnateAttributeEntries",
                passiveSections.innateAttributeSummary(),
                passiveSections.innateAttributeEntries());

        renderAugmentSection(ui, buildAugmentEntries(data));
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
        ui.set("#DetailTitleLabel.Text", tr("ui.profile.detail.title", "Selected Profile"));
        ui.set("#DetailSubtitleLabel.Text", subtitle);
        ui.set("#DetailLevelValue.Text", tr("hud.common.unavailable", "--"));
        ui.set("#DetailXpValue.Text", tr("hud.common.unavailable", "--"));
        ui.set("#DetailRaceValue.Text", tr("hud.common.unavailable", "--"));
        applyAttributeDisplay(ui, "#AttributeLifeForceValue", "#AttributeLifeForceLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeStrengthValue", "#AttributeStrengthLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeSorceryValue", "#AttributeSorceryLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributePrecisionValue", "#AttributePrecisionLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeFerocityValue", "#AttributeFerocityLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeStaminaValue", "#AttributeStaminaLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeFlowValue", "#AttributeFlowLevel", emptyAttributeDisplay());
        applyAttributeDisplay(ui, "#AttributeDisciplineValue", "#AttributeDisciplineLevel",
                emptyAttributeDisplay());
        ui.set("#PassiveSummary.Text",
                tr("ui.profile.passives.select_prompt", "Select a profile to view passive bonuses"));
        ui.set("#PassiveSummary.Visible", true);
        ui.set("#InnatePassiveSummary.Text",
                tr("ui.profile.passives.select_innate_prompt", "Select a profile to view innate bonuses"));
        ui.set("#InnatePassiveSummary.Visible", true);
        ui.clear("#PassiveEntries");
        ui.clear("#InnatePassiveEntries");
    }

    private AttributeDisplay getAttributeDisplay(@Nonnull PlayerData data,
            @Nonnull PlayerProfile profile,
            @Nonnull SkillAttributeType type,
            EntityStatMap statMap) {
        int level = profile.getAttributes().getOrDefault(type, 0);
        String levelText = tr("ui.profile.level.short", "Lv {0}", level);
        String detail = "";

        if (skillManager != null) {
            if (isResourceAttribute(type)) {
                double total = resolveResourceTotal(type, data, statMap);
                detail = Double.isNaN(total)
                        ? tr("hud.common.unavailable", "--")
                        : tr("ui.skills.value.resource", "{0} {1}", formatNumber(total), resourceLabel(type));
            } else {
                detail = switch (type) {
                    case STRENGTH -> {
                        SkillManager.StrengthBreakdown breakdown = skillManager.getStrengthBreakdown(data, level);
                        yield tr("ui.skills.value.strength", "+{0}% Damage", formatNumber(breakdown.totalValue()));
                    }
                    case DEFENSE -> {
                        SkillManager.DefenseBreakdown breakdown = skillManager.getDefenseBreakdown(data, level);
                        double reduction = breakdown.resistance() * 100.0f;
                        yield tr("ui.skills.value.defense", "{0}% Reduction", formatNumber(reduction));
                    }
                    case HASTE -> {
                        SkillManager.HasteBreakdown breakdown = skillManager.getHasteBreakdown(data, level);
                        double percent = (breakdown.totalMultiplier() - 1.0f) * 100.0f;
                        yield tr("ui.skills.value.haste", "+{0}% Speed", formatNumber(percent));
                    }
                    case PRECISION -> {
                        SkillManager.PrecisionBreakdown breakdown = skillManager.getPrecisionBreakdown(data, level);
                        yield tr("ui.skills.value.precision", "{0}% Crit Chance",
                                formatNumber(breakdown.totalPercent()));
                    }
                    case SORCERY -> {
                        float bonus = skillManager.calculatePlayerSorcery(data);
                        yield tr("ui.skills.value.sorcery", "+{0}% Magic Damage", formatNumber(bonus));
                    }
                    case FEROCITY -> {
                        SkillManager.FerocityBreakdown breakdown = skillManager.getFerocityBreakdown(data);
                        yield tr("ui.skills.value.ferocity", "+{0}% Crit Damage", formatNumber(breakdown.totalValue()));
                    }
                    case DISCIPLINE -> {
                        double xpBonus = skillManager.getDisciplineXpBonusPercent(level);
                        yield tr("ui.skills.value.discipline", "+{0}% XP Gain", formatNumber(xpBonus));
                    }
                    default -> tr("hud.common.unavailable", "--");
                };
            }
        }

        if (detail == null || detail.isBlank()) {
            detail = tr("hud.common.unavailable", "--");
        }
        return new AttributeDisplay(detail, levelText);
    }

    private String getRaceDisplay(@Nonnull PlayerProfile profile) {
        String raceId = profile.getRaceId();
        if (raceId == null || raceId.isBlank() || raceId.equalsIgnoreCase("none")) {
            return tr("hud.race.none", "No Race");
        }
        if (raceManager == null) {
            return raceId;
        }
        RaceDefinition definition = raceManager.getRace(raceId);
        if (definition != null) {
            String displayName = definition.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            String id = definition.getId();
            return id != null && !id.isBlank() ? id : tr("hud.race.none", "No Race");
        }
        return raceId;
    }

    private AggregatedPassiveSections buildAggregatedPassiveSections(@Nonnull PlayerData playerData,
            @Nonnull PlayerProfile profile) {
        List<PassiveEntry> passiveEntries = new ArrayList<>();
        List<PassiveEntry> innatePassiveEntries = new ArrayList<>();
        List<PassiveEntry> innateAttributeEntries = new ArrayList<>();

        ArchetypePassiveSnapshot snapshot = archetypePassiveManager != null
                ? archetypePassiveManager.getSnapshot(playerData)
                : null;

        if (snapshot != null && !snapshot.isEmpty()) {
            for (ArchetypePassiveType type : ArchetypePassiveType.values()) {
                if (type == null) {
                    continue;
                }
                double totalValue = snapshot.getValue(type);
                if (Math.abs(totalValue) <= 1.0E-6D) {
                    continue;
                }
                if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                    innateAttributeEntries.addAll(buildInnateAttributeEntries(
                            snapshot.getDefinitions(type),
                            profile));
                    continue;
                }
                AggregatedPassiveProps props = aggregatePassiveProperties(snapshot.getDefinitions(type));
                String label = toDisplay(type.name());
                String valueText = formatAggregatedPassiveValue(type, totalValue, props);
                passiveEntries.add(new PassiveEntry(label, valueText));
            }
        }
        innatePassiveEntries.addAll(buildInnatePlayerPassiveEntries(playerData));

        passiveEntries.sort(Comparator.comparing(PassiveEntry::label));
        innatePassiveEntries.sort(Comparator.comparing(PassiveEntry::label));
        innateAttributeEntries.sort(Comparator.comparing(PassiveEntry::label));

        String passiveSummary = passiveEntries.isEmpty() ? tr("ui.profile.passives.none", "No passive bonuses active")
                : "";
        String innatePassiveSummary = innatePassiveEntries.isEmpty()
                ? tr("ui.profile.passives.innate_none", "No innate passives active")
                : "";
        String innateAttributeSummary = innateAttributeEntries.isEmpty()
                ? tr("ui.profile.passives.innate_attr_none", "No innate attribute bonuses")
                : "";

        return new AggregatedPassiveSections(List.copyOf(passiveEntries),
                List.copyOf(innatePassiveEntries),
                List.copyOf(innateAttributeEntries),
                passiveSummary,
                innatePassiveSummary,
                innateAttributeSummary);
    }

    private List<AugmentEntry> buildAugmentEntries(@Nonnull PlayerData playerData) {
        if (augmentManager == null) {
            return List.of();
        }

        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        if (selected.isEmpty()) {
            return List.of();
        }

        List<AugmentEntry> entries = new ArrayList<>();
        selected.forEach((tierKey, augmentId) -> {
            if (augmentId == null || augmentId.isBlank()) {
                return;
            }
            AugmentDefinition def = augmentManager.getAugment(augmentId);
            String name = def != null ? def.getName() : augmentId;
            String tierLabel = def != null && def.getTier() != null ? def.getTier().name()
                    : (tierKey == null ? "?" : tierKey);
            entries.add(new AugmentEntry(name, tierLabel, "", ""));
        });

        entries.sort(Comparator.comparing(AugmentEntry::tier).thenComparing(AugmentEntry::id));
        return entries;
    }

    private List<PassiveEntry> buildInnatePlayerPassiveEntries(@Nonnull PlayerData playerData) {
        if (passiveManager == null) {
            return List.of();
        }

        PassiveManager.PassiveSyncResult syncResult = passiveManager.syncPassives(playerData);
        if (syncResult == null || syncResult.snapshots() == null || syncResult.snapshots().isEmpty()) {
            return List.of();
        }

        List<PassiveEntry> entries = new ArrayList<>();
        for (PassiveType type : PassiveType.values()) {
            PassiveManager.PassiveSnapshot snapshot = syncResult.snapshots().get(type);
            if (snapshot == null || !snapshot.isUnlocked()) {
                continue;
            }
            String label = tr("ui.profile.passives.level_label", "{0} (Lv {1})", type.getDisplayName(),
                    snapshot.level());
            String valueText = type.formatValue(snapshot.value());
            entries.add(new PassiveEntry(label, valueText));
        }
        entries.sort(Comparator.comparing(PassiveEntry::label));
        return entries;
    }

    private List<PassiveEntry> buildInnateAttributeEntries(@Nonnull List<RacePassiveDefinition> definitions,
            @Nonnull PlayerProfile profile) {
        if (definitions.isEmpty()) {
            return List.of();
        }
        Map<SkillAttributeType, Double> totals = new EnumMap<>(SkillAttributeType.class);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null || definition.attributeType() == null) {
                continue;
            }
            double value = definition.value();
            if (Math.abs(value) <= 1.0E-6D) {
                continue;
            }
            totals.merge(definition.attributeType(), value, Double::sum);
        }
        if (totals.isEmpty()) {
            return List.of();
        }
        List<SkillAttributeType> attributes = new ArrayList<>(totals.keySet());
        attributes.sort(Comparator.comparing(Enum::name));
        List<PassiveEntry> entries = new ArrayList<>();
        for (SkillAttributeType attribute : attributes) {
            double gain = totals.getOrDefault(attribute, 0.0D);
            String label = toDisplay(attribute.name());
            String valueText = formatInnateAttributeValue(attribute, gain, profile);
            entries.add(new PassiveEntry(label, valueText));
        }
        return entries;
    }

    private AggregatedPassiveProps aggregatePassiveProperties(@Nonnull List<RacePassiveDefinition> definitions) {
        if (definitions.isEmpty()) {
            return new AggregatedPassiveProps(null, null, null, null, null, null, null);
        }
        Double threshold = averageProperty(definitions, "threshold");
        Double duration = averageProperty(definitions, "duration");
        Double cooldown = averageProperty(definitions, "cooldown");
        Double window = averageProperty(definitions, "window");
        Double stacks = averageProperty(definitions, "max_stacks");
        Double slowPercent = averageProperty(definitions, "slow_percent");
        String scalingStat = firstStringProperty(definitions, "scaling_stat");
        return new AggregatedPassiveProps(threshold, duration, cooldown, window, stacks, slowPercent, scalingStat);
    }

    private Double averageProperty(@Nonnull List<RacePassiveDefinition> definitions, @Nonnull String key) {
        double sum = 0.0D;
        int count = 0;
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null || definition.properties() == null) {
                continue;
            }
            Double value = getDoubleProp(definition.properties(), key);
            if (value == null) {
                continue;
            }
            sum += value;
            count++;
        }
        if (count == 0) {
            return null;
        }
        return sum / count;
    }

    private String firstStringProperty(@Nonnull List<RacePassiveDefinition> definitions, @Nonnull String key) {
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null || definition.properties() == null) {
                continue;
            }
            Object value = definition.properties().get(key);
            if (value instanceof String str && !str.isBlank()) {
                return str.trim();
            }
        }
        return null;
    }

    private void renderPassiveSection(@Nonnull UICommandBuilder ui,
            @Nonnull String summarySelector,
            @Nonnull String entriesSelector,
            @Nonnull String emptyText,
            @Nonnull List<PassiveEntry> entries) {
        if (entries.isEmpty()) {
            ui.set(summarySelector + ".Visible", true);
            ui.set(summarySelector + ".Text", emptyText);
            ui.clear(entriesSelector);
            return;
        }
        ui.set(summarySelector + ".Visible", false);
        populatePassiveEntries(ui, entriesSelector, PASSIVE_ENTRY_TEMPLATE, entries);
    }

    private void renderAugmentSection(@Nonnull UICommandBuilder ui,
            @Nonnull List<AugmentEntry> augments) {
        String summarySelector = "#AugmentSummary";
        String entriesSelector = "#AugmentEntries";
        if (augments.isEmpty()) {
            ui.set(summarySelector + ".Visible", true);
            ui.set(summarySelector + ".Text", tr("ui.profile.augments.none", "No augments active"));
            ui.clear(entriesSelector);
            return;
        }
        ui.set(summarySelector + ".Visible", false);
        ui.clear(entriesSelector);
        for (int i = 0; i < augments.size(); i++) {
            AugmentEntry entry = augments.get(i);
            ui.append(entriesSelector, PASSIVE_ENTRY_TEMPLATE);
            String base = entriesSelector + "[" + i + "]";
            ui.set(base + " #PassiveName.Text", entry.id());
            String tierLabel = entry.tier();
            ui.set(base + " #PassiveValue.Text", tierLabel == null ? "" : tierLabel);
            ui.set(base + " #PassiveValue.Style.TextColor", resolveTierColor(tierLabel));
        }
    }

    private String resolveTierColor(String tierLabel) {
        if (tierLabel == null || tierLabel.isBlank()) {
            return TIER_COLOR_DEFAULT;
        }
        return switch (tierLabel.trim().toUpperCase(Locale.ROOT)) {
            case "MYTHIC" -> TIER_COLOR_MYTHIC;
            case "ELITE" -> TIER_COLOR_ELITE;
            default -> TIER_COLOR_DEFAULT;
        };
    }

    private void populatePassiveEntries(@Nonnull UICommandBuilder ui,
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

    private void applyAttributeDisplay(@Nonnull UICommandBuilder ui,
            @Nonnull String valueSelector,
            @Nonnull String levelSelector,
            @Nonnull AttributeDisplay display) {
        ui.set(valueSelector + ".Text", display.value());
        ui.set(levelSelector + ".Text", display.level());
    }

    private AttributeDisplay emptyAttributeDisplay() {
        String unavailable = tr("hud.common.unavailable", "--");
        return new AttributeDisplay(unavailable, unavailable);
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
                || type == SkillAttributeType.FLOW;
    }

    private String resourceLabel(@Nonnull SkillAttributeType type) {
        return switch (type) {
            case LIFE_FORCE -> tr("ui.skills.resource.health", "Health");
            case STAMINA -> tr("ui.skills.resource.stamina", "Stamina");
            case FLOW -> tr("ui.skills.resource.flow", "Flow");
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
            case FLOW -> skillManager.calculatePlayerFlow(playerData);
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
            case FLOW -> PlayerAttributeManager.AttributeSlot.FLOW;
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

    private record AugmentEntry(String id, String tier, String value, String source) {
    }

    private record AggregatedPassiveSections(List<PassiveEntry> passiveEntries,
            List<PassiveEntry> innatePassiveEntries,
            List<PassiveEntry> innateAttributeEntries,
            String passiveSummary,
            String innatePassiveSummary,
            String innateAttributeSummary) {
    }

    private static final class AggregatedPassiveProps {
        private final Double threshold;
        private final Double duration;
        private final Double cooldown;
        private final Double window;
        private final Double stacks;
        private final Double slowPercent;
        private final String scalingStat;

        private AggregatedPassiveProps(Double threshold,
                Double duration,
                Double cooldown,
                Double window,
                Double stacks,
                Double slowPercent,
                String scalingStat) {
            this.threshold = threshold;
            this.duration = duration;
            this.cooldown = cooldown;
            this.window = window;
            this.stacks = stacks;
            this.slowPercent = slowPercent;
            this.scalingStat = scalingStat;
        }

        private Double threshold() {
            return threshold;
        }

        private Double duration() {
            return duration;
        }

        private Double cooldown() {
            return cooldown;
        }

        private Double window() {
            return window;
        }

        private Double stacks() {
            return stacks;
        }

        private Double slowPercent() {
            return slowPercent;
        }

        private String scalingStat() {
            return scalingStat;
        }
    }

    private record AttributeDisplay(String value, String level) {
    }

    private String formatAggregatedPassiveValue(@Nonnull ArchetypePassiveType type,
            double value,
            @Nonnull AggregatedPassiveProps props) {
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
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.hp", "HP")),
                    formatDurationDetail(props.duration()),
                    formatCooldownDetail(props.cooldown()));
            case FIRST_STRIKE -> appendDetails(
                    tr("ui.races.passive.desc.first_strike", "{0} opener", formatPercentValue(value)),
                    formatCooldownDetail(props.cooldown()));
            case ADRENALINE -> appendDetails(
                    tr("ui.races.passive.desc.adrenaline", "{0} stamina", formatPercentValue(value)),
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.stamina", "stamina")),
                    formatDurationDetail(props.duration()),
                    formatCooldownDetail(props.cooldown()));
            case BERZERKER -> appendDetails(
                    tr("ui.races.passive.desc.berzerker", "{0} damage", formatPercentValue(value)),
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.hp", "HP")));
            case RETALIATION -> appendDetails(
                    tr("ui.races.passive.desc.retaliation", "{0} reflect", formatPercentValue(value)),
                    formatWindowDetail(props.window()),
                    formatCooldownDetail(props.cooldown()));
            case EXECUTIONER -> appendDetails(
                    tr("ui.races.passive.desc.executioner", "{0} finisher", formatPercentValue(value)),
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.target_hp", "target HP")),
                    formatCooldownDetail(props.cooldown()));
            case SWIFTNESS -> appendDetails(
                    tr("ui.races.passive.desc.swiftness", "{0} speed", formatPercentValue(value)),
                    formatDurationDetail(props.duration()),
                    formatStacksDetail(props.stacks()));
            case WITHER -> appendDetails(
                    tr("ui.races.passive.desc.wither", "{0} max HP/sec", formatPercentValue(value)),
                    formatDurationDetail(props.duration()),
                    formatSlowDetail(props.slowPercent()));
            case CRIT_DEFENSE -> appendDetails(
                    tr("ui.races.passive.desc.crit_defense", "{0} dmg reduction", formatPercentValue(value)),
                    formatScalingDetail(props.scalingStat()));
            case INNATE_ATTRIBUTE_GAIN -> formatSigned(value);
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
        return tr("ui.races.passive.detail.scales_with", "scales with {0}", toDisplay(scalingStat));
    }

    private String formatInnateAttributeValue(SkillAttributeType attributeType,
            double perLevelGain,
            @Nonnull PlayerProfile profile) {
        String perLevelText = tr("ui.races.passive.detail.per_level", "{0} per level", formatSigned(perLevelGain));
        int level = Math.max(1, profile.getLevel());
        double totalGain = perLevelGain * level;
        String totalText = formatSigned(totalGain);
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
        return tr("ui.classes.passive.detail.threshold", "<{0}% {1}", formatNumber(ratio * 100.0D), scope);
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
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.load", "Unable to load your profiles right now."))
                            .color("#ff0000"));
            return;
        }

        ProfileActionOutcome outcome = handleProfileAction(ref, store, data.action, playerData);
        if (outcome.requiresSave() && playerDataManager != null) {
            playerDataManager.save(playerData);
        }
        if (outcome.requiresRebuild()) {
            rebuild();
        }
    }

    private ProfileActionOutcome handleProfileAction(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull String action,
            @Nonnull PlayerData playerData) {
        String payload = action.substring("profile:".length());

        try {
            if ("new".equalsIgnoreCase(payload)) {
                return handleNewProfile(ref, store, playerData);
            }
            if (payload.startsWith("select:")) {
                return handleSelectProfile(ref, store, playerData, payload);
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
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.generic", "Something went wrong handling that request."))
                            .color("#ff0000"));
        }

        return new ProfileActionOutcome(false, false);
    }

    private ProfileActionOutcome handleNewProfile(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerData playerData) {
        if (playerData.getProfileCount() >= PlayerData.MAX_PROFILES) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.profile.error.max_slots", "All profile slots are already in use. Delete one first."))
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        int nextSlot = playerData.findNextAvailableProfileSlot();
        if (!PlayerData.isValidProfileIndex(nextSlot)) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.no_open_slot", "Unable to find an open slot right now."))
                            .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        boolean created = playerData.createProfile(nextSlot, PlayerData.defaultProfileName(nextSlot), false, true);
        if (!created) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.create_failed", "Could not create that profile slot."))
                            .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        playerRef.sendMessage(
                Message.raw(tr("ui.profile.info.created", "Created and activated profile slot {0}.", nextSlot))
                        .color("#4fd7f7"));
        PlayerHud.refreshHud(playerData.getUuid());
        if (partyManager != null && partyManager.isAvailable()) {
            partyManager.updatePartyHudCustomText(playerData);
        }
        reapplyProfileModifiers(ref, store, playerData);
        return new ProfileActionOutcome(true, true);
    }

    private ProfileActionOutcome handleSelectProfile(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerData playerData,
            @Nonnull String payload) {
        int slot = parseSlot(payload, "select:");
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.profile.error.slot_range", "Profile slot must be between 1 and {0}.",
                            PlayerData.MAX_PROFILES))
                    .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.slot_missing", "Profile slot {0} has not been created yet.", slot))
                            .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.info.already_active", "Profile slot {0} is already active.", slot))
                            .color("#4fd7f7"));
            return new ProfileActionOutcome(false, false);
        }

        PlayerData.ProfileSwitchResult result = playerData.switchProfile(slot);
        if (result == PlayerData.ProfileSwitchResult.SWITCHED_EXISTING) {
            playerRef.sendMessage(Message.raw(
                    tr("ui.profile.info.switched", "Switched to profile slot {0} ({1}).", slot,
                            playerData.getProfileName(slot)))
                    .color("#00ff00"));
            PlayerHud.refreshHud(playerData.getUuid());
            if (partyManager != null && partyManager.isAvailable()) {
                partyManager.updatePartyHudCustomText(playerData);
            }
            reapplyProfileModifiers(ref, store, playerData);
            return new ProfileActionOutcome(true, true);
        }

        playerRef.sendMessage(
                Message.raw(tr("ui.profile.error.switch_failed", "Unable to switch to that slot right now."))
                        .color("#ff0000"));
        return new ProfileActionOutcome(false, false);
    }

    private void reapplyProfileModifiers(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PlayerData playerData) {
        if (skillManager == null) {
            return;
        }
        boolean applied = skillManager.applyAllSkillModifiers(ref, store, playerData);
        if (!applied && playerRaceStatSystem != null) {
            playerRaceStatSystem.scheduleRetry(playerData.getUuid());
        }
        if (raceManager != null) {
            raceManager.applyRaceModelIfEnabled(playerData);
        }
    }

    private ProfileActionOutcome handleDeletePrompt(@Nonnull PlayerData playerData,
            @Nonnull String payload) {
        int slot = parseSlot(payload, "delete-prompt:");
        if (!canDeleteSlot(playerData, slot)) {
            return new ProfileActionOutcome(false, false);
        }

        pendingDeleteSlot = slot;
        playerRef.sendMessage(
                Message.raw(tr("ui.profile.info.delete_prompt", "Press CONFIRM to delete profile slot {0}.", slot))
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
            playerRef.sendMessage(Message
                    .raw(tr("ui.profile.error.confirm_first", "Please confirm the deletion of slot {0} first.", slot))
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        boolean deleted = playerData.deleteProfile(slot);
        if (!deleted) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.delete_failed", "Could not delete that profile slot right now."))
                            .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        clearPendingDelete();
        playerRef.sendMessage(
                Message.raw(tr("ui.profile.info.deleted", "Deleted profile slot {0}.", slot)).color("#4fd7f7"));
        return new ProfileActionOutcome(true, true);
    }

    private ProfileActionOutcome handleDeleteCancel() {
        if (pendingDeleteSlot == null) {
            return new ProfileActionOutcome(false, false);
        }
        clearPendingDelete();
        playerRef.sendMessage(
                Message.raw(tr("ui.profile.info.delete_canceled", "Profile deletion canceled.")).color("#4fd7f7"));
        return new ProfileActionOutcome(false, true);
    }

    private boolean canDeleteSlot(@Nonnull PlayerData playerData, int slot) {
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.profile.error.slot_range", "Profile slot must be between 1 and {0}.",
                            PlayerData.MAX_PROFILES))
                    .color("#ff0000"));
            return false;
        }
        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.slot_empty", "Profile slot {0} is already empty.", slot))
                            .color("#ff9900"));
            return false;
        }
        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.profile.error.delete_active", "Switch to a different profile before deleting slot {0}.",
                            slot))
                    .color("#ff9900"));
            return false;
        }
        if (playerData.getProfileCount() <= 1) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.profile.error.keep_one", "You must keep at least one profile slot."))
                            .color("#ff0000"));
            return false;
        }
        return true;
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
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
