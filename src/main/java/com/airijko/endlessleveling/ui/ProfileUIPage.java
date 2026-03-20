package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.WeaponConfig;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerData.PlayerProfile;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;
import com.airijko.endlessleveling.enums.themes.AttributeTheme;
import com.airijko.endlessleveling.enums.themes.ProfileSectionTheme;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerAttributeManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.player.SkillManager;
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

    private final PlayerDataManager playerDataManager;
    private final RaceManager raceManager;
    private final SkillManager skillManager;
    private final PlayerAttributeManager attributeManager;
    private final ArchetypePassiveManager archetypePassiveManager;
    private final PassiveManager passiveManager;
    private final PlayerRaceStatSystem playerRaceStatSystem;
    private final PartyManager partyManager;
    private final AugmentManager augmentManager;
    private final ClassManager classManager;
    private final LevelingManager levelingManager;
    private final AugmentValueFormatter augmentValueFormatter;
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
        this.classManager = plugin != null ? plugin.getClassManager() : null;
        this.levelingManager = plugin != null ? plugin.getLevelingManager() : null;
        this.augmentValueFormatter = new AugmentValueFormatter(this::tr);
        this.pendingDeleteSlot = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        boolean partnerAuthorized = EndlessLeveling.getInstance() != null
            && EndlessLeveling.getInstance().isPartnerAddonAuthorized();
        ui.append(partnerAuthorized
            ? "Pages/Profile/ProfilePagePartner.ui"
            : "Pages/Profile/ProfilePage.ui");
        NavUIHelper.bindNavEvents(events);
        NavUIHelper.applyNavVersion(ui, playerRef, "profile",
            "Common/UI/Custom/Pages/Profile/ProfilePage.ui",
            "#ProfileTitle");

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
        for (AttributeTheme theme : AttributeTheme.values()) {
            ui.set(theme.profileLabelSelector() + ".Text", tr(theme.labelKey(), theme.labelFallback()));
            ui.set(theme.profileLabelSelector() + ".Style.TextColor", theme.labelColor());
            ui.set(theme.profileValueSelector() + ".Style.TextColor", theme.valueColor());
            ui.set(theme.profileLevelSelector() + ".Style.TextColor", theme.profileLevelColor());
        }
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
        ui.set("#DetailLevelValue.Text", String.valueOf(profile.getLevel()));
        ui.set("#DetailPrestigeValue.Text", String.valueOf(profile.getPrestigeLevel()));
        ui.set("#DetailXpValue.Text", tr("ui.profile.list.xp", "{0} XP", formatNumber(profile.getXp())));
        ui.set("#DetailRaceValue.Text", getRaceDisplay(profile));
        ui.set("#DetailPrimaryClassValue.Text", getPrimaryClassDisplay(profile));
        ui.set("#DetailSecondaryClassValue.Text", getSecondaryClassDisplay(profile));
        ui.set("#DetailProgressBar.Value", resolveXpProgress(data, profile));

        applyAttributeDisplay(ui,
                SkillAttributeType.LIFE_FORCE,
                "#AttributeLifeForceLabel",
                "#AttributeLifeForceValue",
                "#AttributeLifeForceLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.LIFE_FORCE, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.STRENGTH,
                "#AttributeStrengthLabel",
                "#AttributeStrengthValue",
                "#AttributeStrengthLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.STRENGTH, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.SORCERY,
                "#AttributeSorceryLabel",
                "#AttributeSorceryValue",
                "#AttributeSorceryLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.SORCERY, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.DEFENSE,
                "#AttributeDefenseLabel",
                "#AttributeDefenseValue",
                "#AttributeDefenseLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.DEFENSE, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.HASTE,
                "#AttributeHasteLabel",
                "#AttributeHasteValue",
                "#AttributeHasteLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.HASTE, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.PRECISION,
                "#AttributePrecisionLabel",
                "#AttributePrecisionValue",
                "#AttributePrecisionLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.PRECISION, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.FEROCITY,
                "#AttributeFerocityLabel",
                "#AttributeFerocityValue",
                "#AttributeFerocityLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.FEROCITY, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.STAMINA,
                "#AttributeStaminaLabel",
                "#AttributeStaminaValue",
                "#AttributeStaminaLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.STAMINA, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.FLOW,
                "#AttributeFlowLabel",
                "#AttributeFlowValue",
                "#AttributeFlowLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.FLOW, statMap));
        applyAttributeDisplay(ui,
                SkillAttributeType.DISCIPLINE,
                "#AttributeDisciplineLabel",
                "#AttributeDisciplineValue",
                "#AttributeDisciplineLevel",
                getAttributeDisplay(data, profile, SkillAttributeType.DISCIPLINE, statMap));

        AggregatedPassiveSections passiveSections = buildAggregatedPassiveSections(data, profile);
        renderPassiveSection(ui,
                "#PassiveSummary",
                "#PassiveEntries",
                passiveSections.passiveSummary(),
                passiveSections.passiveEntries(),
                ProfileSectionTheme.PASSIVE);
        renderPassiveSection(ui,
                "#InnatePassiveSummary",
                "#InnatePassiveEntries",
                passiveSections.innatePassiveSummary(),
                passiveSections.innatePassiveEntries(),
                ProfileSectionTheme.INNATE_PASSIVE);
        renderPassiveSection(ui,
                "#InnateAttributeSummary",
                "#InnateAttributeEntries",
                passiveSections.innateAttributeSummary(),
                passiveSections.innateAttributeEntries(),
                ProfileSectionTheme.INNATE_ATTRIBUTE);

        renderPassiveSection(ui,
                "#PrimaryWeaponSummary",
                "#PrimaryWeaponEntries",
                tr("ui.profile.classes.primary_weapon_none", "No primary class weapon bonuses"),
                buildPrimaryWeaponEntries(profile),
                ProfileSectionTheme.PRIMARY_WEAPON);
        renderAugmentSection(ui, buildAugmentEntries(data));
    }

    private PlayerProfile resolveActiveProfile(@Nonnull PlayerData data) {
        Map<Integer, PlayerProfile> profiles = data.getProfiles();
        PlayerProfile profile = profiles.get(data.getActiveProfileIndex());
        if (profile != null) {
            return profile;
        }
        return profiles.values().iterator().next();
    }

    private String getPrimaryClassDisplay(@Nonnull PlayerProfile profile) {
        CharacterClassDefinition primary = getClassDefinition(profile.getPrimaryClassId());
        if (primary != null) {
            return primary.getDisplayName();
        }
        String classId = profile.getPrimaryClassId();
        return classId == null || classId.isBlank() ? tr("ui.profile.classes.none", "None") : classId;
    }

    private String getSecondaryClassDisplay(@Nonnull PlayerProfile profile) {
        CharacterClassDefinition secondary = getClassDefinition(profile.getSecondaryClassId());
        if (secondary != null) {
            return secondary.getDisplayName();
        }
        String classId = profile.getSecondaryClassId();
        return classId == null || classId.isBlank() ? tr("ui.profile.classes.none", "None") : classId;
    }

    private CharacterClassDefinition getClassDefinition(String classId) {
        if (classManager == null || classId == null || classId.isBlank()) {
            return null;
        }
        return classManager.getClass(classId);
    }

    private List<PassiveEntry> buildPrimaryWeaponEntries(@Nonnull PlayerProfile profile) {
        CharacterClassDefinition primary = getClassDefinition(profile.getPrimaryClassId());
        if (primary == null || primary.getWeaponMultipliers().isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, Double>> weaponEntries = new ArrayList<>(primary.getWeaponMultipliers().entrySet());
        weaponEntries.sort(Comparator.comparing(entry -> localizeWeaponType(entry.getKey())));

        List<PassiveEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Double> entry : weaponEntries) {
            entries.add(new PassiveEntry(localizeWeaponType(entry.getKey()), formatWeaponMultiplier(entry.getValue())));
        }
        return List.copyOf(entries);
    }

    private double resolveXpProgress(@Nonnull PlayerData data, @Nonnull PlayerProfile profile) {
        if (levelingManager == null) {
            return 0.0D;
        }

        int effectiveCap = levelingManager.getLevelCap(data);
        if (profile.getLevel() >= effectiveCap) {
            return 1.0D;
        }

        double xpNeeded = levelingManager.getXpForNextLevel(data, profile.getLevel());
        if (!Double.isFinite(xpNeeded) || xpNeeded <= 0.0D) {
            return 0.0D;
        }

        double currentXp = Math.max(0.0D, profile.getXp());
        double ratio = currentXp / xpNeeded;
        return Math.max(0.0D, Math.min(1.0D, ratio));
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
                        : formatWholeNumber(total);
            } else {
                detail = switch (type) {
                    case STRENGTH -> {
                        SkillManager.StrengthBreakdown breakdown = skillManager.getStrengthBreakdown(data, level);
                        yield formatPercent(breakdown.totalValue(), 0, true);
                    }
                    case DEFENSE -> {
                        SkillManager.DefenseBreakdown breakdown = skillManager.getDefenseBreakdown(data, level);
                        double reduction = breakdown.resistance() * 100.0f;
                        yield formatPercent(reduction, 0, false);
                    }
                    case HASTE -> {
                        SkillManager.HasteBreakdown breakdown = skillManager.getHasteBreakdown(data, level);
                        double percent = (breakdown.totalMultiplier() - 1.0f) * 100.0f;
                        yield formatPercent(percent, 1, true);
                    }
                    case PRECISION -> {
                        SkillManager.PrecisionBreakdown breakdown = skillManager.getPrecisionBreakdown(data, level);
                        yield formatPercent(breakdown.totalPercent(), 1, false);
                    }
                    case SORCERY -> {
                        float bonus = skillManager.calculatePlayerSorcery(data);
                        yield formatPercent(bonus, 0, true);
                    }
                    case FEROCITY -> {
                        SkillManager.FerocityBreakdown breakdown = skillManager.getFerocityBreakdown(data);
                        yield formatPercent(breakdown.totalValue(), 1, true);
                    }
                    case DISCIPLINE -> {
                        double xpBonus = skillManager.getDisciplineXpBonusPercent(level);
                        yield formatPercent(xpBonus, 0, true);
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
                String label = props.displayName() != null && !props.displayName().isBlank()
                        ? props.displayName()
                        : toDisplay(type.name());
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

        Map<String, AugmentGroupMeta> firstByGroup = new LinkedHashMap<>();
        Map<String, Integer> countByGroup = new HashMap<>();
        Map<String, Double> totalCommonValueByGroup = new HashMap<>();

        selected.forEach((tierKey, augmentId) -> {
            if (augmentId == null || augmentId.isBlank()) {
                return;
            }

            AugmentDefinition def = augmentManager.getAugment(augmentId);
            String name = def != null ? def.getName() : augmentId;
            String tierLabel = def != null && def.getTier() != null ? def.getTier().name()
                    : (tierKey == null ? "?" : tierKey);
            String groupKey;
            String attributeKey = null;

            CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(augmentId);
            if (offer != null && def != null && CommonAugment.ID.equalsIgnoreCase(def.getId())) {
                attributeKey = offer.attributeKey() == null ? "" : offer.attributeKey().trim();
                name = tr("ui.augments.common_stat.card_name", "{0}", toDisplay(attributeKey));
                groupKey = "common_stat:" + attributeKey.toLowerCase(Locale.ROOT);
                totalCommonValueByGroup.merge(groupKey, offer.rolledValue(), Double::sum);
            } else {
                String canonicalId = def != null ? def.getId() : null;
                if (canonicalId == null || canonicalId.isBlank()) {
                    canonicalId = augmentId;
                }
                groupKey = canonicalId.toLowerCase(Locale.ROOT);
            }

            firstByGroup.putIfAbsent(groupKey, new AugmentGroupMeta(name, tierLabel));
            countByGroup.merge(groupKey, 1, Integer::sum);
        });

        List<AugmentEntry> entries = new ArrayList<>(firstByGroup.size());
        for (Map.Entry<String, AugmentGroupMeta> grouped : firstByGroup.entrySet()) {
            String groupKey = grouped.getKey();
            AugmentGroupMeta meta = grouped.getValue();

            String displayName = meta.name();
            int count = Math.max(1, countByGroup.getOrDefault(groupKey, 1));
            if (count > 1) {
                displayName = tr("ui.augments.unlocked.count_suffix", "{0} x{1}", displayName, count);
            }

            String valueText = meta.tierLabel();
            if (groupKey.startsWith("common_stat:")) {
                String commonAttributeKey = groupKey.substring("common_stat:".length());
                double totalValue = totalCommonValueByGroup.getOrDefault(groupKey, 0.0D);
                valueText = formatCommonAugmentTotal(commonAttributeKey, totalValue);
            }

            entries.add(new AugmentEntry(displayName, meta.tierLabel(), valueText, ""));
        }

        entries.sort(Comparator
                .comparingInt((AugmentEntry entry) -> tierSortPriority(entry.tier()))
                .thenComparing(AugmentEntry::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    private int tierSortPriority(String tierLabel) {
        if (tierLabel == null || tierLabel.isBlank()) {
            return 3;
        }
        return switch (tierLabel.trim().toUpperCase(Locale.ROOT)) {
            case "MYTHIC" -> 0;
            case "ELITE" -> 1;
            case "COMMON" -> 2;
            default -> 3;
        };
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
        Map<SkillAttributeType, Double> uncappedPerLevelTotals = new EnumMap<>(SkillAttributeType.class);
        Map<SkillAttributeType, Double> classPerLevelTotals = new EnumMap<>(SkillAttributeType.class);
        for (RacePassiveDefinition definition : definitions) {
            if (definition == null || definition.attributeType() == null) {
                continue;
            }
            double value = definition.value();
            if (Math.abs(value) <= 1.0E-6D) {
                continue;
            }
            if (isClassInnateDefinition(definition)) {
                classPerLevelTotals.merge(definition.attributeType(), value, Double::sum);
            } else {
                uncappedPerLevelTotals.merge(definition.attributeType(), value, Double::sum);
            }
        }
        if (uncappedPerLevelTotals.isEmpty() && classPerLevelTotals.isEmpty()) {
            return List.of();
        }

        List<SkillAttributeType> attributes = new ArrayList<>(uncappedPerLevelTotals.keySet());
        for (SkillAttributeType attributeType : classPerLevelTotals.keySet()) {
            if (!attributes.contains(attributeType)) {
                attributes.add(attributeType);
            }
        }
        attributes.sort(Comparator.comparing(Enum::name));

        int currentLevel = Math.max(1, profile.getLevel());
        List<PassiveEntry> entries = new ArrayList<>();
        for (SkillAttributeType attribute : attributes) {
            double uncappedPerLevel = uncappedPerLevelTotals.getOrDefault(attribute, 0.0D);
            double classPerLevel = classPerLevelTotals.getOrDefault(attribute, 0.0D);
            double gain = uncappedPerLevel + classPerLevel;
            int classEffectiveLevel = skillManager != null
                    ? skillManager.applyClassInnateAttributeLevelCap(attribute, currentLevel)
                    : currentLevel;
            double totalGain = (uncappedPerLevel * currentLevel) + (classPerLevel * classEffectiveLevel);
            String label = toDisplay(attribute.name());
            String valueText = formatInnateAttributeValue(gain, totalGain, currentLevel);
            entries.add(new PassiveEntry(label, valueText));
        }
        return entries;
    }

    private boolean isClassInnateDefinition(@Nonnull RacePassiveDefinition definition) {
        if (definition.properties() == null || definition.properties().isEmpty()) {
            return false;
        }
        Object source = definition.properties().get(ArchetypePassiveManager.PASSIVE_SOURCE_PROPERTY);
        if (!(source instanceof String sourceText)) {
            return false;
        }
        return ArchetypePassiveManager.PASSIVE_SOURCE_CLASS.equalsIgnoreCase(sourceText.trim());
    }

    private AggregatedPassiveProps aggregatePassiveProperties(@Nonnull List<RacePassiveDefinition> definitions) {
        if (definitions.isEmpty()) {
            return new AggregatedPassiveProps(null, null, null, null, null, null, null, null);
        }
        Double threshold = averageProperty(definitions, "threshold");
        Double duration = averageProperty(definitions, "duration");
        if (duration == null) {
            duration = averageProperty(definitions, "target_haste_slow_duration");
        }
        Double cooldown = averageProperty(definitions, "cooldown");
        Double window = averageProperty(definitions, "window");
        Double stacks = averageProperty(definitions, "max_stacks");
        Double slowPercent = averageProperty(definitions, "slow_percent");
        if (slowPercent == null) {
            slowPercent = averageProperty(definitions, "target_haste_slow_on_hit");
        }
        String scalingStat = firstStringProperty(definitions, "scaling_stat");
        String displayName = firstStringProperty(definitions, "display_name");
        if (displayName == null || displayName.isBlank()) {
            displayName = firstStringProperty(definitions, "name");
        }
        return new AggregatedPassiveProps(threshold,
                duration,
                cooldown,
                window,
                stacks,
                slowPercent,
                scalingStat,
                displayName);
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
            @Nonnull List<PassiveEntry> entries,
            @Nonnull ProfileSectionTheme sectionTheme) {
        if (entries.isEmpty()) {
            ui.set(summarySelector + ".Visible", true);
            ui.set(summarySelector + ".Text", emptyText);
            ui.clear(entriesSelector);
            return;
        }
        ui.set(summarySelector + ".Visible", false);
        populatePassiveEntries(ui, entriesSelector, PASSIVE_ENTRY_TEMPLATE, entries, sectionTheme);
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
            ui.set(base + " #PassiveName.Style.TextColor", ProfileSectionTheme.AUGMENT.nameColor());
            String tierLabel = entry.tier();
            String valueText = entry.value();
            if (valueText == null || valueText.isBlank()) {
                valueText = tierLabel;
            }
            ui.set(base + " #PassiveValue.Text", valueText == null ? "" : valueText);
            ui.set(base + " #PassiveValue.Style.TextColor", resolveTierColor(tierLabel));
        }
    }

    private String formatCommonAugmentTotal(String attributeKey, double totalValue) {
        String line = augmentValueFormatter.formatSingleValueLine(attributeKey, totalValue, attributeKey);
        if (line == null || line.isBlank()) {
            return tr("ui.augments.tier.common", "COMMON");
        }

        int separatorIndex = line.indexOf(':');
        if (separatorIndex >= 0 && separatorIndex + 1 < line.length()) {
            String valuePart = line.substring(separatorIndex + 1).trim();
            if (!valuePart.isBlank()) {
                return valuePart;
            }
        }
        return line.trim();
    }

    private String resolveTierColor(String tierLabel) {
        if (tierLabel == null || tierLabel.isBlank()) {
            return AugmentTheme.PROFILE_COMMON.color();
        }
        return switch (tierLabel.trim().toUpperCase(Locale.ROOT)) {
            case "MYTHIC" -> AugmentTheme.PROFILE_MYTHIC.color();
            case "ELITE" -> AugmentTheme.PROFILE_ELITE.color();
            case "COMMON" -> AugmentTheme.PROFILE_COMMON.color();
            default -> AugmentTheme.PROFILE_COMMON.color();
        };
    }

    private void populatePassiveEntries(@Nonnull UICommandBuilder ui,
            @Nonnull String containerSelector,
            @Nonnull String template,
            @Nonnull List<PassiveEntry> entries,
            @Nonnull ProfileSectionTheme sectionTheme) {
        ui.clear(containerSelector);
        for (int i = 0; i < entries.size(); i++) {
            PassiveEntry entry = entries.get(i);
            ui.append(containerSelector, template);
            String base = containerSelector + "[" + i + "]";
            ui.set(base + " #PassiveName.Text", entry.label());
            ui.set(base + " #PassiveName.Style.TextColor", sectionTheme.nameColor());
            ui.set(base + " #PassiveValue.Text", entry.value());
            ui.set(base + " #PassiveValue.Style.TextColor", sectionTheme.valueColor());
        }
    }

    private void applyAttributeDisplay(@Nonnull UICommandBuilder ui,
            @Nonnull SkillAttributeType type,
            @Nonnull String labelSelector,
            @Nonnull String valueSelector,
            @Nonnull String levelSelector,
            @Nonnull AttributeDisplay display) {
        AttributeTheme theme = AttributeTheme.fromType(type);
        String baseLabel = theme != null ? tr(theme.labelKey(), theme.labelFallback()) : toDisplay(type.name());

        ui.set(labelSelector + ".Text", display.level() + " " + baseLabel);
        ui.set(valueSelector + ".Text", "");
        ui.set(levelSelector + ".Text", display.value());
    }

    private String formatWholeNumber(double value) {
        if (!Double.isFinite(value)) {
            return tr("hud.common.unavailable", "--");
        }
        return String.valueOf(Math.round(value));
    }

    private String formatPercent(double value, int decimals, boolean forcePlus) {
        if (!Double.isFinite(value)) {
            return tr("hud.common.unavailable", "--");
        }

        double rounded = roundTo(value, decimals);
        StringBuilder builder = new StringBuilder();
        if (forcePlus && rounded > 0.0D) {
            builder.append('+');
        }
        builder.append(formatWithDecimals(rounded, decimals));
        builder.append('%');
        return builder.toString();
    }

    private double roundTo(double value, int decimals) {
        if (decimals <= 0) {
            return Math.rint(value);
        }
        double scale = Math.pow(10.0D, decimals);
        return Math.round(value * scale) / scale;
    }

    private String formatWithDecimals(double value, int decimals) {
        String pattern = "%1$." + Math.max(0, decimals) + "f";
        return String.format(Locale.ROOT, pattern, value);
    }

    private String formatWeaponMultiplier(double multiplier) {
        double delta = (multiplier - 1.0D) * 100.0D;
        if (Math.abs(delta) < 0.0001D) {
            return tr("ui.classes.value.weapon_damage", "+{0}% dmg", 0);
        }
        if (delta > 0) {
            return tr("ui.classes.value.weapon_damage", "+{0}% dmg", formatNumber(delta));
        }
        return tr("ui.classes.value.weapon_damage_negative", "-{0}% dmg", formatNumber(Math.abs(delta)));
    }

    private String localizeWeaponType(String typeKey) {
        String normalized = WeaponConfig.normalizeCategoryKey(typeKey);
        if (normalized == null) {
            return tr("hud.class.none", "None");
        }
        return tr("ui.classes.weapon." + normalized, toDisplay(normalized));
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

    private record AugmentGroupMeta(String name, String tierLabel) {
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
        private final String displayName;

        private AggregatedPassiveProps(Double threshold,
                Double duration,
                Double cooldown,
                Double window,
                Double stacks,
                Double slowPercent,
                String scalingStat,
                String displayName) {
            this.threshold = threshold;
            this.duration = duration;
            this.cooldown = cooldown;
            this.window = window;
            this.stacks = stacks;
            this.slowPercent = slowPercent;
            this.scalingStat = scalingStat;
            this.displayName = displayName;
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

        private String displayName() {
            return displayName;
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
                case ARCANE_WISDOM -> appendDetails(
                    tr("ui.races.passive.desc.arcane_wisdom", "{0} max mana", formatPercentValue(value)),
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.mana", "mana")));
            case HEALING_TOUCH -> tr("ui.races.passive.desc.healing_touch", "on-hit heal: {0} of source",
                    formatPercentValue(value));
            case HEALING_AURA -> appendDetails(
                    tr("ui.races.passive.desc.party_mending_aura", "Party healing pulse"),
                    tr("ui.races.passive.detail.party_mending_heal", "heal: 10% mana + 20% stamina"),
                    tr("ui.races.passive.detail.party_mending_radius", "radius: 5 +1/75 mana"));
            case SHIELDING_AURA -> appendDetails(
                    tr("ui.races.passive.desc.shielding_aura", "Party shielding aura"),
                    tr("ui.races.passive.detail.shielding_aura_effect", "shield: flat + stamina scaling"),
                    tr("ui.races.passive.detail.shielding_aura_duration", "duration + cooldown, party/range only"));
            case BUFFING_AURA -> appendDetails(
                    tr("ui.races.passive.desc.buffing_aura", "Party buffing aura"),
                    tr("ui.races.passive.detail.buffing_aura_effect", "damage bonus from stamina (shared)"),
                    tr("ui.races.passive.detail.buffing_aura_cap", "up to 100% per ally, self at 25%"));
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
                case TRUE_BOLTS -> appendDetails(
                    tr("ui.races.passive.desc.first_strike", "{0} opener", formatPercentValue(value)),
                    formatCooldownDetail(props.cooldown()));
                case FOCUSED_STRIKE -> appendDetails(
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
                case PRIMAL_DOMINANCE -> appendDetails(
                    tr("ui.profile.passive.desc.primal_dominance",
                        "{0} Strength from total health",
                        formatPercentValue(value)),
                    formatSlowDetail(props.slowPercent()),
                    formatDurationDetail(props.duration()));
                case ARCANE_DOMINANCE -> appendDetails(
                    tr("ui.profile.passive.desc.arcane_dominance",
                        "{0} Sorcery from total health",
                        formatPercentValue(value)),
                    formatSlowDetail(props.slowPercent()),
                    formatDurationDetail(props.duration()));
            case FINAL_INCANTATION -> appendDetails(
                    tr("ui.races.passive.desc.executioner", "Final Incantation: +{0} bonus damage",
                        formatPercentValue(value)),
                    formatThresholdDetail(props.threshold(), tr("ui.races.passive.scope.target_hp", "target HP")),
                    formatCooldownDetail(props.cooldown()));
            case SWIFTNESS -> appendDetails(
                    tr("ui.races.passive.desc.swiftness", "{0} speed", formatPercentValue(value)),
                    formatDurationDetail(props.duration()),
                    formatStacksDetail(props.stacks()));
                case BLADE_DANCE -> appendDetails(
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

    private String formatInnateAttributeValue(double perLevelGain,
            double totalGain,
            int level) {
        String perLevelText = tr("ui.races.passive.detail.per_level", "{0} per level", formatSigned(perLevelGain));
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

        if (playerDataManager != null) {
            playerDataManager.initializeSwapDefaultsForNewProfile(playerData, nextSlot);
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
        skillManager.clearAugmentAttributeBonuses(playerData);
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
