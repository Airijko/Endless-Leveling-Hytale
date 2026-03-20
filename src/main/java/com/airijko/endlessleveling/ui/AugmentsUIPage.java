package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.Lang;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.MouseEntered;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.MouseExited;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.ValueChanged;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Searchable augment browser page that uses a row-based item grid.
 */
public class AugmentsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final int GRID_ITEMS_PER_ROW = 5;
    private static final int INFO_SECTION_LIMIT = 5;
    private static final String[] INFO_SECTION_CONTAINERS = {
        "#AugmentInfoSection1",
        "#AugmentInfoSection2",
        "#AugmentInfoSection3",
        "#AugmentInfoSection4",
        "#AugmentInfoSection5"
    };
    private static final String[] INFO_SECTION_TITLES = {
        "#AugmentInfoSection1Title",
        "#AugmentInfoSection2Title",
        "#AugmentInfoSection3Title",
        "#AugmentInfoSection4Title",
        "#AugmentInfoSection5Title"
    };
    private static final String[] INFO_SECTION_BODIES = {
        "#AugmentInfoSection1Body",
        "#AugmentInfoSection2Body",
        "#AugmentInfoSection3Body",
        "#AugmentInfoSection4Body",
        "#AugmentInfoSection5Body"
    };

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;
    private final AugmentPresentationMapper augmentPresentationMapper;

    private String searchQuery = "";
    private String selectedAugmentId = null;

    public AugmentsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.playerRef = playerRef;
        this.augmentPresentationMapper = new AugmentPresentationMapper(this::tr);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Augments/AugmentsPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef, "augments",
            "Common/UI/Custom/Pages/Augments/AugmentsPage.ui",
            "#AugmentsPageTitle");
        applyStaticLabels(ui);
        ui.set("#SearchInput.Value", this.searchQuery);
        NavUIHelper.bindNavEvents(events);
        events.addEventBinding(ValueChanged, "#SearchInput", of("@SearchQuery", "#SearchInput.Value"), false);
        events.addEventBinding(Activating, "#OpenAugmentsChooseButton", of("Action", "augment:open_choose"),
                false);

        buildGrid(ui, events);
        applyInfoPanel(ui, selectedAugmentId);
    }

    private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
        ui.set("#OpenAugmentsChooseButton.Text", tr("ui.augments.page.choose_button", "CHOOSE AUGMENTS"));
        ui.set("#AugmentsOverviewDescription.Text", tr("ui.augments.page.left.description",
                "Augments are powerful passive enhancements that permanently strengthen your character. Earn them by reaching level milestones and through prestige."));
        ui.set("#AugmentsCollectionTitle.Text", tr("ui.augments.page.left.collection_title", "YOUR COLLECTION"));
        ui.set("#AugmentsRerollsTitle.Text", tr("ui.augments.page.left.rerolls_title", "REROLLS USED"));
        ui.set("#AugmentsInfoText.Text",
                tr("ui.augments.page.left.hover_hint", "Hover over an augment to preview it."));
        ui.set("#SearchInput.PlaceholderText", tr("ui.augments.page.search_placeholder", "Search augments..."));
        ui.set("#UnlockedHeader.Text", tr("ui.augments.page.section.unlocked", "UNLOCKED"));
        ui.set("#MythicHeader.Text", tr("ui.augments.page.section.mythic", "MYTHIC"));
        ui.set("#EliteHeader.Text", tr("ui.augments.page.section.elite", "ELITE"));
        ui.set("#CommonHeader.Text", tr("ui.augments.page.section.common", "COMMON"));
        ui.set("#AugmentInfoPanel.Text", tr("ui.augments.page.info_title", "AUGMENT INFO"));
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
        }

        if (data.action != null && !data.action.isBlank()) {
            String action = data.action.trim();
            if ("augment:open_choose".equalsIgnoreCase(action)) {
                openChoosePage(ref, store);
                return;
            }
            if (action.startsWith("augment:hover:")) {
                if (this.selectedAugmentId == null) {
                    String id = action.substring("augment:hover:".length());
                    UICommandBuilder commandBuilder = new UICommandBuilder();
                    UIEventBuilder eventBuilder = new UIEventBuilder();
                    applyInfoPanel(commandBuilder, id.isBlank() ? null : id);
                    this.sendUpdate(commandBuilder, eventBuilder, false);
                }
                return;
            }
            if ("augment:hoverend".equals(action)) {
                if (this.selectedAugmentId == null) {
                    UICommandBuilder commandBuilder = new UICommandBuilder();
                    UIEventBuilder eventBuilder = new UIEventBuilder();
                    applyInfoPanel(commandBuilder, null);
                    this.sendUpdate(commandBuilder, eventBuilder, false);
                }
                return;
            }
            if (action.startsWith("augment:select:")) {
                String id = action.substring("augment:select:".length());
                this.selectedAugmentId = id.isBlank() ? null : id;
                UICommandBuilder commandBuilder = new UICommandBuilder();
                UIEventBuilder eventBuilder = new UIEventBuilder();
                applyInfoPanel(commandBuilder, this.selectedAugmentId);
                this.sendUpdate(commandBuilder, eventBuilder, false);
                return;
            }
        }

        if (data.searchQuery != null) {
            this.searchQuery = data.searchQuery.trim().toLowerCase(Locale.ROOT);
            UICommandBuilder commandBuilder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            commandBuilder.set("#SearchInput.Value", data.searchQuery);
            buildGrid(commandBuilder, eventBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        }
    }

    private void buildGrid(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        // Clear all section grids
        ui.clear("#UnlockedCards");
        ui.clear("#MythicCards");
        ui.clear("#EliteCards");
        ui.clear("#CommonCards");

        if (augmentManager == null) {
            ui.set("#AugmentsResultLabel.Text", tr("ui.augments.page.results", "Results: {0}", 0));
            ui.set("#AugmentsChooseAvailabilityLabel.Text",
                    tr("ui.augments.page.choose_unavailable", "No augments available to choose."));
            ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", AugmentTheme.chooseAvailabilityColor(false));
            ui.set("#UnlockedSection.Visible", false);
            ui.set("#MythicSection.Visible", false);
            ui.set("#EliteSection.Visible", false);
            ui.set("#CommonSection.Visible", false);
            return;
        }

        PlayerData playerData = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;
        if (playerData != null && augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(playerData);
        }
        applyChooseAvailability(ui, playerData);
        applyLeftPanel(ui, playerData);

        Set<String> ownedIds = resolveOwnedIds(playerData);
        List<OwnedAugmentCard> unlockedCards = applySearchOwned(buildOwnedCards(playerData));
        Collection<AugmentDefinition> all = augmentManager.getAugments().values();

        // Organize augments into sections
        List<AugmentDefinition> mythicAugments = new java.util.ArrayList<>();
        List<AugmentDefinition> eliteAugments = new java.util.ArrayList<>();
        List<AugmentDefinition> commonAugments = new java.util.ArrayList<>();

        for (AugmentDefinition def : all) {
            boolean owned = ownedIds.contains(def.getId());
            if (!owned) {
                switch (def.getTier()) {
                    case MYTHIC:
                        mythicAugments.add(def);
                        break;
                    case ELITE:
                        eliteAugments.add(def);
                        break;
                    case COMMON:
                        commonAugments.add(def);
                        break;
                }
            }
        }

        // Sort each section
        mythicAugments.sort(Comparator.comparing(AugmentDefinition::getName));
        eliteAugments.sort(Comparator.comparing(AugmentDefinition::getName));
        commonAugments.sort(Comparator.comparing(AugmentDefinition::getName));

        // Apply search filter to all sections
        mythicAugments = applySearch(mythicAugments);
        eliteAugments = applySearch(eliteAugments);
        commonAugments = applySearch(commonAugments);

        int totalResults = unlockedCards.size() + mythicAugments.size() + eliteAugments.size()
                + commonAugments.size();
        ui.set("#AugmentsResultLabel.Text", tr("ui.augments.page.results", "Results: {0}", totalResults));

        // Build UNLOCKED section
        buildOwnedSection(ui, events, unlockedCards, "#UnlockedCards", "#UnlockedSection");

        // Build MYTHIC section
        buildSection(ui, events, mythicAugments, "#MythicCards", "#MythicSection", ownedIds);

        // Build ELITE section
        buildSection(ui, events, eliteAugments, "#EliteCards", "#EliteSection", ownedIds);

        // Build COMMON section
        buildSection(ui, events, commonAugments, "#CommonCards", "#CommonSection", ownedIds);
    }

    private void applyInfoPanel(@Nonnull UICommandBuilder ui, String augmentId) {
        AugmentDefinition def = (augmentId != null && augmentManager != null)
                ? augmentManager.getAugment(augmentId)
                : null;

        if (def == null) {
            ui.set("#AugmentInfoIcon.Visible", false);
            ui.set("#AugmentInfoName.Text", tr("ui.augments.page.info_select_prompt", "Select an augment"));
            ui.set("#AugmentInfoName.Style.TextColor", "#9fb6d3");
            ui.set("#AugmentInfoTier.Visible", false);
            ui.set("#AugmentInfoDivider.Visible", false);
            ui.set("#AugmentInfoDescription.Visible", false);
            applyInfoSections(ui, List.of());
            return;
        }

        AugmentPresentationMapper.AugmentPresentationData presentation = augmentPresentationMapper.map(def, augmentId);
        ui.set("#AugmentInfoIcon.ItemId", presentation.iconItemId());
        ui.set("#AugmentInfoIcon.Visible", true);

        ui.set("#AugmentInfoName.Text", presentation.displayName());
        ui.set("#AugmentInfoName.Style.TextColor", tierColor(def.getTier()));

        String tierName = def.getTier() != null ? def.getTier().name() : "";
        ui.set("#AugmentInfoTier.Text", tierName);
        ui.set("#AugmentInfoTier.Style.TextColor", tierColor(def.getTier()));
        ui.set("#AugmentInfoTier.Visible", !tierName.isBlank());

        ui.set("#AugmentInfoDivider.Visible", true);

        String desc = def.getDescription();
        boolean hasDesc = desc != null && !desc.isBlank();
        ui.set("#AugmentInfoDescription.Text", hasDesc ? desc : "");
        ui.set("#AugmentInfoDescription.Visible", hasDesc);

        applyInfoSections(ui, buildYamlInfoSections(def));
    }

    private List<InfoSection> buildYamlInfoSections(@Nonnull AugmentDefinition definition) {
        List<InfoSection> sections = new ArrayList<>();
        for (AugmentDefinition.UiSection section : definition.getUiSections()) {
            if (section == null) {
                continue;
            }
            String title = section.title() == null ? "" : section.title().trim();
            String body = normalizeMultilineText(section.body());
            if (title.isBlank() && body.isBlank()) {
                continue;
            }
            sections.add(new InfoSection(title, body, normalizeColor(section.color(), "#c5d4e8")));
            if (sections.size() >= INFO_SECTION_LIMIT) {
                break;
            }
        }
        return sections;
    }

    private void applyInfoSections(@Nonnull UICommandBuilder ui, @Nonnull List<InfoSection> sections) {
        List<InfoSection> safeSections = sections == null ? List.of() : sections;
        boolean hasAny = false;

        for (int index = 0; index < INFO_SECTION_LIMIT; index++) {
            String containerSelector = INFO_SECTION_CONTAINERS[index];
            String titleSelector = INFO_SECTION_TITLES[index];
            String bodySelector = INFO_SECTION_BODIES[index];

            if (index >= safeSections.size()) {
                ui.set(containerSelector + ".Visible", false);
                ui.set(titleSelector + ".Visible", false);
                ui.set(bodySelector + ".Visible", false);
                continue;
            }

            InfoSection section = safeSections.get(index);
            String title = section.title() == null ? "" : section.title().trim();
            String body = normalizeMultilineText(section.body());
            String color = normalizeColor(section.color(), "#c5d4e8");
            boolean hasTitle = !title.isBlank();
            boolean hasBody = !body.isBlank();
            boolean visible = hasTitle || hasBody;

            ui.set(containerSelector + ".Visible", visible);
            ui.set(titleSelector + ".Text", title);
            ui.set(titleSelector + ".Visible", hasTitle);
            ui.set(bodySelector + ".Text", body);
            ui.set(bodySelector + ".Visible", hasBody);
            ui.set(bodySelector + ".Style.TextColor", color);

            if (visible) {
                hasAny = true;
            }
        }

        ui.set("#AugmentInfoDivider2.Visible", hasAny);
        ui.set("#AugmentInfoValues.Visible", hasAny);
    }

    private String normalizeColor(String color, String fallback) {
        if (color == null) {
            return fallback;
        }
        String trimmed = color.trim();
        if (trimmed.matches("#?[0-9a-fA-F]{6}")) {
            return trimmed.startsWith("#") ? trimmed : "#" + trimmed;
        }
        return fallback;
    }

    private String normalizeMultilineText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        boolean lastWasBlank = true;
        for (String line : text.split("\\n", -1)) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                if (!lastWasBlank) {
                    lines.add("");
                    lastWasBlank = true;
                }
                continue;
            }
            lines.add(trimmed);
            lastWasBlank = false;
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    private record InfoSection(String title, String body, String color) {
    }

    private void openChoosePage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store,
                new AugmentsChoosePage(playerRef, CustomPageLifetime.CanDismiss));
    }

    private void applyChooseAvailability(@Nonnull UICommandBuilder ui, PlayerData playerData) {
        boolean available = hasPendingAugmentChoices(playerData);
        if (available) {
            ui.set("#AugmentsChooseAvailabilityLabel.Text",
                    tr("ui.augments.page.choose_available", "Augments available to choose."));
            ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", AugmentTheme.chooseAvailabilityColor(true));
            return;
        }

        ui.set("#AugmentsChooseAvailabilityLabel.Text",
                tr("ui.augments.page.choose_unavailable", "No augments available to choose."));
        ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", AugmentTheme.chooseAvailabilityColor(false));
    }

    private boolean hasPendingAugmentChoices(PlayerData playerData) {
        if (playerData == null) {
            return false;
        }

        Map<String, List<String>> offersByTier = playerData.getAugmentOffersSnapshot();
        for (List<String> offers : offersByTier.values()) {
            if (offers != null && !offers.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void applyLeftPanel(@Nonnull UICommandBuilder ui, PlayerData playerData) {
        Set<String> ownedIds = resolveOwnedIds(playerData);
        Collection<AugmentDefinition> allDefs = augmentManager != null ? augmentManager.getAugments().values()
                : List.of();

        ui.set("#AugmentStatCommon.Style.TextColor", AugmentTheme.gridOwnedColor(PassiveTier.COMMON));
        ui.set("#AugmentRerollCommon.Style.TextColor", AugmentTheme.gridOwnedColor(PassiveTier.COMMON));

        long totalMythic = allDefs.stream().filter(d -> d.getTier() == PassiveTier.MYTHIC).count();
        long totalElite = allDefs.stream().filter(d -> d.getTier() == PassiveTier.ELITE).count();

        int mythicOwned = countSelectedForTier(playerData, PassiveTier.MYTHIC);
        int eliteOwned = countSelectedForTier(playerData, PassiveTier.ELITE);
        int commonOwned = countSelectedForTier(playerData, PassiveTier.COMMON);
        int totalOwned = mythicOwned + eliteOwned + commonOwned;

        ui.set("#AugmentStatTotal.Text",
                tr("ui.augments.page.stats.total", "Total: {0} / {1}", totalOwned, allDefs.size()));
        ui.set("#AugmentStatMythic.Text",
                tr("ui.augments.page.stats.mythic", "Mythic: {0} / {1}", mythicOwned, totalMythic));
        ui.set("#AugmentStatElite.Text",
                tr("ui.augments.page.stats.elite", "Elite: {0} / {1}", eliteOwned, totalElite));
        ui.set("#AugmentStatCommon.Text", tr("ui.augments.page.stats.common", "Common: {0}", commonOwned));

        Map<String, Integer> rerolls = playerData != null ? playerData.getAugmentRerollsUsedSnapshot() : Map.of();
        ui.set("#AugmentRerollMythic.Text",
                tr("ui.augments.page.rerolls.mythic", "Mythic: {0}", rerolls.getOrDefault("MYTHIC", 0)));
        ui.set("#AugmentRerollElite.Text",
                tr("ui.augments.page.rerolls.elite", "Elite: {0}", rerolls.getOrDefault("ELITE", 0)));
        ui.set("#AugmentRerollCommon.Text",
                tr("ui.augments.page.rerolls.common", "Common: {0}", rerolls.getOrDefault("COMMON", 0)));
    }

    private int countSelectedForTier(PlayerData playerData, PassiveTier tier) {
        if (playerData == null || tier == null) {
            return 0;
        }

        String normalizedTier = tier.name().toUpperCase(Locale.ROOT);
        int count = 0;
        for (String key : playerData.getSelectedAugmentsSnapshot().keySet()) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String normalizedKey = key.trim().toUpperCase(Locale.ROOT);
            if (normalizedKey.equals(normalizedTier) || normalizedKey.startsWith(normalizedTier + "#")) {
                count++;
            }
        }
        return count;
    }

    private void buildSection(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull List<AugmentDefinition> augments,
            @Nonnull String cardsSelector,
            @Nonnull String sectionSelector,
            @Nonnull Set<String> ownedIds) {
        boolean hasContent = !augments.isEmpty();
        ui.set(sectionSelector + ".Visible", hasContent);

        if (!hasContent) {
            return;
        }

        int rowIndex = 0;
        int cardsInCurrentRow = 0;

        for (AugmentDefinition def : augments) {
            if (cardsInCurrentRow == 0) {
                ui.appendInline(cardsSelector, "Group { LayoutMode: Left; Anchor: (Bottom: 0); }");
            }

            AugmentPresentationMapper.AugmentPresentationData presentation = augmentPresentationMapper.map(def,
                    def.getId());

            ui.append(cardsSelector + "[" + rowIndex + "]", "Pages/Augments/AugmentGridEntry.ui");
            String base = cardsSelector + "[" + rowIndex + "][" + cardsInCurrentRow + "]";

            ui.set(base + " #ItemIcon.ItemId", presentation.iconItemId());
            ui.set(base + " #ItemName.Text", presentation.displayName());
            ui.set(base + " #ItemName.Style.TextColor", tierColor(def.getTier()));

            events.addEventBinding(Activating, base, of("Action", "augment:select:" + def.getId()), false);
            events.addEventBinding(MouseEntered, base, of("Action", "augment:hover:" + def.getId()), false);
            events.addEventBinding(MouseExited, base, of("Action", "augment:hoverend"), false);

            cardsInCurrentRow++;
            if (cardsInCurrentRow >= GRID_ITEMS_PER_ROW) {
                cardsInCurrentRow = 0;
                rowIndex++;
            }
        }
    }

    private void buildOwnedSection(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull List<OwnedAugmentCard> cards,
            @Nonnull String cardsSelector,
            @Nonnull String sectionSelector) {
        boolean hasContent = !cards.isEmpty();
        ui.set(sectionSelector + ".Visible", hasContent);

        if (!hasContent) {
            return;
        }

        List<OwnedAugmentCard> mythicCards = new ArrayList<>();
        List<OwnedAugmentCard> eliteCards = new ArrayList<>();
        List<OwnedAugmentCard> commonCards = new ArrayList<>();
        List<OwnedAugmentCard> uncategorizedCards = new ArrayList<>();

        for (OwnedAugmentCard card : cards) {
            AugmentDefinition definition = augmentManager != null ? augmentManager.getAugment(card.id()) : null;
            PassiveTier tier = definition != null ? definition.getTier() : null;
            if (tier == PassiveTier.MYTHIC) {
                mythicCards.add(card);
            } else if (tier == PassiveTier.ELITE) {
                eliteCards.add(card);
            } else if (tier == PassiveTier.COMMON) {
                commonCards.add(card);
            } else {
                uncategorizedCards.add(card);
            }
        }

        int rowIndex = 0;
        boolean hasPreviousTierGroup = false;

        rowIndex = appendOwnedTierGroup(ui, events, cardsSelector, mythicCards, rowIndex, hasPreviousTierGroup);
        hasPreviousTierGroup = hasPreviousTierGroup || !mythicCards.isEmpty();

        rowIndex = appendOwnedTierGroup(ui, events, cardsSelector, eliteCards, rowIndex, hasPreviousTierGroup);
        hasPreviousTierGroup = hasPreviousTierGroup || !eliteCards.isEmpty();

        rowIndex = appendOwnedTierGroup(ui, events, cardsSelector, commonCards, rowIndex, hasPreviousTierGroup);
        hasPreviousTierGroup = hasPreviousTierGroup || !commonCards.isEmpty();

        appendOwnedTierGroup(ui, events, cardsSelector, uncategorizedCards, rowIndex, hasPreviousTierGroup);
    }

    private int appendOwnedTierGroup(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull String cardsSelector,
            @Nonnull List<OwnedAugmentCard> tierCards,
            int rowIndex,
            boolean addTopGap) {
        if (tierCards.isEmpty()) {
            return rowIndex;
        }

        int cardsInCurrentRow = 0;
        boolean firstRowInGroup = true;

        for (OwnedAugmentCard card : tierCards) {
            if (cardsInCurrentRow == 0) {
                String rowLayout = (addTopGap && firstRowInGroup)
                        ? "Group { LayoutMode: Left; Anchor: (Bottom: 0); Padding: (Top: 14); }"
                        : "Group { LayoutMode: Left; Anchor: (Bottom: 0); }";
                ui.appendInline(cardsSelector, rowLayout);
                firstRowInGroup = false;
            }

            ui.append(cardsSelector + "[" + rowIndex + "]", "Pages/Augments/AugmentGridEntry.ui");
            String base = cardsSelector + "[" + rowIndex + "][" + cardsInCurrentRow + "]";

            ui.set(base + " #ItemIcon.ItemId", card.iconItemId());
            ui.set(base + " #ItemName.Text", card.displayName());

            AugmentDefinition definition = augmentManager != null ? augmentManager.getAugment(card.id()) : null;
            ui.set(base + " #ItemName.Style.TextColor",
                    definition != null ? tierColor(definition.getTier()) : AugmentTheme.gridUnownedColor());

            events.addEventBinding(Activating, base, of("Action", "augment:select:" + card.id()), false);
            events.addEventBinding(MouseEntered, base, of("Action", "augment:hover:" + card.id()), false);
            events.addEventBinding(MouseExited, base, of("Action", "augment:hoverend"), false);

            cardsInCurrentRow++;
            if (cardsInCurrentRow >= GRID_ITEMS_PER_ROW) {
                cardsInCurrentRow = 0;
                rowIndex++;
            }
        }

        if (cardsInCurrentRow > 0) {
            rowIndex++;
        }
        return rowIndex;
    }

    private List<AugmentDefinition> applySearch(List<AugmentDefinition> source) {
        if (searchQuery == null || searchQuery.isBlank()) {
            return source;
        }
        return source.stream()
                .filter(def -> matchesSearch(def, searchQuery))
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(AugmentDefinition def, String query) {
        String id = def.getId() != null ? def.getId().toLowerCase(Locale.ROOT) : "";
        String name = def.getName() != null ? def.getName().toLowerCase(Locale.ROOT) : "";
        return id.contains(query) || name.contains(query);
    }

    private List<OwnedAugmentCard> buildOwnedCards(PlayerData playerData) {
        if (playerData == null || augmentManager == null) {
            return List.of();
        }

        Map<String, OwnedAugmentCard> firstCardByGroup = new java.util.LinkedHashMap<>();
        Map<String, Integer> countByGroup = new HashMap<>();
        Map<String, Double> totalCommonValueByGroup = new HashMap<>();

        for (String id : playerData.getSelectedAugmentsSnapshot().values()) {
            if (id != null && !id.isBlank()) {
                String rawId = id;
                AugmentDefinition definition = augmentManager.getAugment(rawId);
                if (definition == null) {
                    continue;
                }

                AugmentPresentationMapper.AugmentPresentationData presentation = augmentPresentationMapper.map(
                        definition,
                        rawId);
                String displayName = presentation.displayName();
                String icon = presentation.iconItemId();
                String groupKey;

                CommonAugment.CommonStatOffer offer = presentation.commonStatOffer();
                if (offer != null && CommonAugment.ID.equalsIgnoreCase(definition.getId())) {
                    String attributeKey = offer.attributeKey() == null ? "" : offer.attributeKey().trim();
                    groupKey = "common_stat:" + attributeKey.toLowerCase(Locale.ROOT);
                    totalCommonValueByGroup.merge(groupKey, offer.rolledValue(), Double::sum);
                } else {
                    String canonicalId = definition.getId();
                    if (canonicalId == null || canonicalId.isBlank()) {
                        canonicalId = rawId;
                    }
                    groupKey = canonicalId.toLowerCase(Locale.ROOT);
                }

                firstCardByGroup.putIfAbsent(groupKey, new OwnedAugmentCard(rawId, displayName, icon));
                countByGroup.merge(groupKey, 1, Integer::sum);
            }
        }

        List<OwnedAugmentCard> cards = new ArrayList<>(firstCardByGroup.size());
        for (Map.Entry<String, OwnedAugmentCard> entry : firstCardByGroup.entrySet()) {
            String groupKey = entry.getKey();
            OwnedAugmentCard baseCard = entry.getValue();
            int count = Math.max(1, countByGroup.getOrDefault(groupKey, 1));

            String infoId = baseCard.id();
            if (groupKey.startsWith("common_stat:")) {
                String attributeKey = groupKey.substring("common_stat:".length());
                double totalValue = totalCommonValueByGroup.getOrDefault(groupKey, 0.0D);
                infoId = CommonAugment.buildStatOfferId(attributeKey, totalValue);
            }

            String displayName = baseCard.displayName();
            if (count > 1) {
                displayName = tr("ui.augments.unlocked.count_suffix", "{0} x{1}", displayName, count);
            }

            cards.add(new OwnedAugmentCard(infoId, displayName, baseCard.iconItemId()));
        }

        return cards;
    }

    private List<OwnedAugmentCard> applySearchOwned(List<OwnedAugmentCard> source) {
        if (searchQuery == null || searchQuery.isBlank()) {
            return source;
        }
        return source.stream()
                .filter(card -> {
                    String id = card.id() == null ? "" : card.id().toLowerCase(Locale.ROOT);
                    String name = card.displayName() == null ? "" : card.displayName().toLowerCase(Locale.ROOT);
                    return id.contains(searchQuery) || name.contains(searchQuery);
                })
                .collect(Collectors.toList());
    }

    private Set<String> resolveOwnedIds(PlayerData playerData) {
        if (playerData == null) {
            return Set.of();
        }
        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        Set<String> ids = new HashSet<>();
        for (String id : selected.values()) {
            if (id != null && !id.isBlank()) {
                AugmentDefinition definition = augmentManager != null ? augmentManager.getAugment(id) : null;
                if (definition != null && definition.getId() != null && !definition.getId().isBlank()) {
                    ids.add(definition.getId());
                } else {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private record OwnedAugmentCard(String id, String displayName, String iconItemId) {
    }

    private List<AugmentDefinition> buildSortedList(Set<String> ownedIds) {
        Collection<AugmentDefinition> all = augmentManager.getAugments().values();

        Comparator<AugmentDefinition> comparator = Comparator
                .<AugmentDefinition, Integer>comparing(d -> ownedIds.contains(d.getId()) ? 0 : 1)
                .thenComparingInt(d -> AugmentTheme.tierSortOrder(d.getTier()))
                .thenComparing(AugmentDefinition::getName);

        return all.stream().sorted(comparator).collect(Collectors.toList());
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
    }

    private String tierColor(PassiveTier tier) {
        return AugmentTheme.gridOwnedColor(tier);
    }
}
