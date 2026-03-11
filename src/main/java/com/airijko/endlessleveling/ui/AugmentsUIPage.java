package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.ValueChanged;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Searchable augment browser page that uses a row-based item grid.
 */
public class AugmentsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final int GRID_ITEMS_PER_ROW = 7;
    private static final String COLOR_MYTHIC_OWNED = "#b084e0";
    private static final String COLOR_ELITE_OWNED = "#7ec8f5";
    private static final String COLOR_COMMON_OWNED = "#e6c168";
    private static final String COLOR_UNOWNED = "#d4d9df";
    private static final String COLOR_CHOOSE_AVAILABLE = "#8adf9e";
    private static final String COLOR_CHOOSE_UNAVAILABLE = "#ff9f9f";

    private static final Map<PassiveTier, Integer> TIER_ORDER = Map.of(
            PassiveTier.MYTHIC, 0,
            PassiveTier.ELITE, 1,
            PassiveTier.COMMON, 2);

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;

    private String searchQuery = "";

    public AugmentsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.playerRef = playerRef;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Augments/AugmentsPage.ui");
        ui.set("#SearchInput.Value", this.searchQuery);
        ui.set("#OpenAugmentsChooseButton.Text", "OPEN CHOOSE PAGE");
        events.addEventBinding(ValueChanged, "#SearchInput", of("@SearchQuery", "#SearchInput.Value"), false);
        events.addEventBinding(Activating, "#OpenAugmentsChooseButton", of("Action", "augment:open_choose"),
                false);

        buildGrid(ui);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isBlank()) {
            String action = data.action.trim().toLowerCase(Locale.ROOT);
            if ("augment:open_choose".equals(action)) {
                openChoosePage(ref, store);
                return;
            }
        }

        if (data.searchQuery != null) {
            this.searchQuery = data.searchQuery.trim().toLowerCase(Locale.ROOT);
            UICommandBuilder commandBuilder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            commandBuilder.set("#SearchInput.Value", data.searchQuery);
            buildGrid(commandBuilder);
            this.sendUpdate(commandBuilder, eventBuilder, false);
        }
    }

    private void buildGrid(@Nonnull UICommandBuilder ui) {
        // Clear all section grids
        ui.clear("#UnlockedCards");
        ui.clear("#MythicCards");
        ui.clear("#EliteCards");
        ui.clear("#CommonCards");

        if (augmentManager == null) {
            ui.set("#AugmentsResultLabel.Text", "Results: 0");
            ui.set("#AugmentsChooseAvailabilityLabel.Text", "No augments available to choose.");
            ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", COLOR_CHOOSE_UNAVAILABLE);
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

        Set<String> ownedIds = resolveOwnedIds(playerData);
        Collection<AugmentDefinition> all = augmentManager.getAugments().values();

        // Organize augments into sections
        List<AugmentDefinition> unlockedAugments = new java.util.ArrayList<>();
        List<AugmentDefinition> mythicAugments = new java.util.ArrayList<>();
        List<AugmentDefinition> eliteAugments = new java.util.ArrayList<>();
        List<AugmentDefinition> commonAugments = new java.util.ArrayList<>();

        for (AugmentDefinition def : all) {
            boolean owned = ownedIds.contains(def.getId());
            if (owned) {
                unlockedAugments.add(def);
            } else {
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
        unlockedAugments.sort(Comparator.<AugmentDefinition>comparingInt(d -> TIER_ORDER.getOrDefault(d.getTier(), 3))
                .thenComparing(AugmentDefinition::getName));
        mythicAugments.sort(Comparator.comparing(AugmentDefinition::getName));
        eliteAugments.sort(Comparator.comparing(AugmentDefinition::getName));
        commonAugments.sort(Comparator.comparing(AugmentDefinition::getName));

        // Apply search filter to all sections
        unlockedAugments = applySearch(unlockedAugments);
        mythicAugments = applySearch(mythicAugments);
        eliteAugments = applySearch(eliteAugments);
        commonAugments = applySearch(commonAugments);

        int totalResults = unlockedAugments.size() + mythicAugments.size() + eliteAugments.size()
                + commonAugments.size();
        ui.set("#AugmentsResultLabel.Text", "Results: " + totalResults);

        // Build UNLOCKED section
        buildSection(ui, unlockedAugments, "#UnlockedCards", "#UnlockedSection", ownedIds);

        // Build MYTHIC section
        buildSection(ui, mythicAugments, "#MythicCards", "#MythicSection", ownedIds);

        // Build ELITE section
        buildSection(ui, eliteAugments, "#EliteCards", "#EliteSection", ownedIds);

        // Build COMMON section
        buildSection(ui, commonAugments, "#CommonCards", "#CommonSection", ownedIds);
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
            ui.set("#AugmentsChooseAvailabilityLabel.Text", "Augments available to choose.");
            ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", COLOR_CHOOSE_AVAILABLE);
            return;
        }

        ui.set("#AugmentsChooseAvailabilityLabel.Text", "No augments available to choose.");
        ui.set("#AugmentsChooseAvailabilityLabel.Style.TextColor", COLOR_CHOOSE_UNAVAILABLE);
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

    private void buildSection(@Nonnull UICommandBuilder ui,
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

            ui.append(cardsSelector + "[" + rowIndex + "]", "Augments/AugmentGridEntry.ui");
            String base = cardsSelector + "[" + rowIndex + "][" + cardsInCurrentRow + "]";

            ui.set(base + " #ItemIcon.ItemId", resolveIconItemId(def));
            ui.set(base + " #ItemName.Text", def.getName());

            boolean owned = ownedIds.contains(def.getId());
            ui.set(base + " #ItemName.Style.TextColor", owned ? tierColor(def.getTier()) : COLOR_UNOWNED);

            cardsInCurrentRow++;
            if (cardsInCurrentRow >= GRID_ITEMS_PER_ROW) {
                cardsInCurrentRow = 0;
                rowIndex++;
            }
        }
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

    private Set<String> resolveOwnedIds(PlayerData playerData) {
        if (playerData == null) {
            return Set.of();
        }
        Map<String, String> selected = playerData.getSelectedAugmentsSnapshot();
        Set<String> ids = new HashSet<>();
        for (String id : selected.values()) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<AugmentDefinition> buildSortedList(Set<String> ownedIds) {
        Collection<AugmentDefinition> all = augmentManager.getAugments().values();

        Comparator<AugmentDefinition> comparator = Comparator
                .<AugmentDefinition, Integer>comparing(d -> ownedIds.contains(d.getId()) ? 0 : 1)
                .thenComparingInt(d -> TIER_ORDER.getOrDefault(d.getTier(), 3))
                .thenComparing(AugmentDefinition::getName);

        return all.stream().sorted(comparator).collect(Collectors.toList());
    }

    private String resolveIconItemId(AugmentDefinition def) {
        PassiveCategory category = def != null ? def.getCategory() : PassiveCategory.PASSIVE_STAT;
        if (category == null) {
            category = PassiveCategory.PASSIVE_STAT;
        }
        String id = category.getIconItemId();
        return id == null || id.isBlank() ? "Ingredient_Ice_Essence" : id;
    }

    private String tierColor(PassiveTier tier) {
        if (tier == null) {
            return COLOR_COMMON_OWNED;
        }
        return switch (tier) {
            case MYTHIC -> COLOR_MYTHIC_OWNED;
            case ELITE -> COLOR_ELITE_OWNED;
            default -> COLOR_COMMON_OWNED;
        };
    }
}
