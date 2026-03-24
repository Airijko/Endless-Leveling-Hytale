package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.LocalizationKey;
import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;
import com.airijko.endlessleveling.augments.types.CommonAugment;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Augments page that displays three random augment definitions.
 */
public class AugmentsChoosePage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final int CARD_COUNT = 3;
    private static final String[] CARD_SECTION_SUFFIXES = {
        "Buffs",
        "Debuffs",
        "SelfDamage",
        "Neutral",
        "Notes",
        "Duration",
        "Cooldown"
    };
    private static final String[] CARD_SECTION_DEFAULT_COLORS = {
        "#8adf9e",
        "#ff9a9a",
        "#ffb86b",
        "#e6edf5",
        "#c9d2de",
        "#9ecbff",
        "#ffd56b"
    };
    private static final int CARD_SECTION_LIMIT = CARD_SECTION_SUFFIXES.length;
    private static final String DURATION_TITLE = "duration";
    private static final String COOLDOWN_TITLE = "cooldown";

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;
    private final AugmentPresentationMapper augmentPresentationMapper;

    public AugmentsChoosePage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
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
        ui.append("Pages/Augments/AugmentsCards.ui");

        PlayerData playerData = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;
        List<AugmentDefinition> augments = pickPlayerAugments(playerData);
        List<AugmentChoice> choices = playerData != null ? collectChoices(playerData) : List.of();
        NoAugmentState noAugmentState = resolveNoAugmentState(playerData, augments);
        String tierTitle = resolveTierTitle(playerData, augments);
        ui.set("#AugmentsTierTitle.Text", tierTitle);
        ui.set("#AugmentsTierTitle.Visible", true);

        for (int i = 0; i < CARD_COUNT; i++) {
            String offerId = i < choices.size() ? choices.get(i).id() : null;
            AugmentDefinition augment = i < augments.size() ? augments.get(i) : null;
            applyCard(ui, i + 1, offerId, augment, noAugmentState);
        }

        String chooseText = tr("ui.augments.actions.choose", "Choose");
        ui.set("#AugmentCard1Choose.Text", chooseText);
        ui.set("#AugmentCard2Choose.Text", chooseText);
        ui.set("#AugmentCard3Choose.Text", chooseText);

        events.addEventBinding(Activating, "#AugmentCard1Choose", of("Action", "augment:choose:0"), false);
        events.addEventBinding(Activating, "#AugmentCard2Choose", of("Action", "augment:choose:1"), false);
        events.addEventBinding(Activating, "#AugmentCard3Choose", of("Action", "augment:choose:2"), false);
    }

    private String resolveTierTitle(PlayerData playerData, List<AugmentDefinition> augments) {
        PassiveTier activeTier = resolveActiveOfferTier(playerData);
        if (activeTier != null) {
            return activeTier.name();
        }

        if (augments == null || augments.isEmpty()) {
            return tr("ui.augments.tier.default", "AUGMENTS");
        }
        AugmentDefinition first = augments.get(0);
        if (first == null || first.getTier() == null) {
            return tr("ui.augments.tier.default", "AUGMENTS");
        }
        return first.getTier().name();
    }

    private List<AugmentDefinition> pickPlayerAugments(PlayerData playerData) {
        if (augmentManager == null) {
            return List.of();
        }

        if (playerData != null && augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(playerData);
        }

        List<String> offerIds = new ArrayList<>();
        if (playerData != null) {
            Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
            PassiveTier activeTier = resolveActiveOfferTier(playerData);
            if (activeTier != null) {
                offerIds.addAll(offers.getOrDefault(activeTier.name(), List.of()));
            }
        }

        List<AugmentDefinition> resolved = new ArrayList<>();
        for (String id : offerIds) {
            AugmentDefinition def = augmentManager.getAugment(id);
            if (def != null) {
                resolved.add(def);
            }
            if (resolved.size() >= CARD_COUNT) {
                break;
            }
        }

        return resolved;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || data.action.isBlank() || !data.action.startsWith("augment:choose:")) {
            return;
        }

        int index;
        try {
            index = Integer.parseInt(data.action.substring("augment:choose:".length()));
        } catch (NumberFormatException ex) {
            return;
        }

        PlayerData playerData = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;
        if (playerData == null || augmentManager == null || augmentUnlockManager == null) {
            playerRef.sendMessage(Message.raw(tr("ui.augments.error.data_unavailable", "Augment data unavailable."))
                    .color("#ff6666"));
            return;
        }

        // Make sure offers exist
        augmentUnlockManager.ensureUnlocks(playerData);

        List<AugmentChoice> choices = collectChoices(playerData);
        if (index < 0 || index >= choices.size()) {
            playerRef.sendMessage(Message.raw(tr("ui.augments.error.slot_empty", "No augment in that slot."))
                    .color("#ff9900"));
            return;
        }

        AugmentChoice choice = choices.get(index);
        AugmentDefinition def = augmentManager.getAugment(choice.id);
        PassiveTier tier = def != null ? def.getTier() : choice.tier;
        if (tier == null) {
            playerRef
                    .sendMessage(Message.raw(tr("ui.augments.error.tier_unresolved", "Unable to resolve augment tier."))
                            .color("#ff6666"));
            return;
        }

        String tierKey = tier.name();
        playerData.addSelectedAugmentForTier(tierKey, choice.id);

        List<String> tierOffers = new ArrayList<>(playerData.getAugmentOffersForTier(tierKey));
        int consumeCount = Math.min(CARD_COUNT, tierOffers.size());
        if (consumeCount > 0) {
            tierOffers.subList(0, consumeCount).clear();
        }
        playerData.setAugmentOffersForTier(tierKey, tierOffers);
        playerDataManager.save(playerData);

        playerRef.sendMessage(Message.raw(tr("ui.augments.selected",
                "Selected augment: {0} ({1})",
                choice.id,
                tierKey)).color("#4fd7f7"));

        List<PassiveTier> remainingTiers = augmentUnlockManager.getPendingOfferTiers(playerData);
        if (!remainingTiers.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            builder.append(tr(LocalizationKey.UI_AUGMENTS_REMAINING_HEADER.key(),
                    LocalizationKey.UI_AUGMENTS_REMAINING_HEADER.fallback())).append("\n");
            for (PassiveTier remainingTier : remainingTiers) {
                builder.append("- ").append(remainingTier.name()).append("\n");
            }
            builder.append(tr(LocalizationKey.UI_AUGMENTS_REMAINING_FOOTER.key(),
                    LocalizationKey.UI_AUGMENTS_REMAINING_FOOTER.fallback()));
            PlayerChatNotifier.send(playerRef,
                    Message.raw(PlayerChatNotifier.stripKnownPrefix(builder.toString())).color("#4fd7f7"));
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store,
                    new AugmentsChoosePage(playerRef, CustomPageLifetime.CanDismiss));
        }
    }

    private List<AugmentChoice> collectChoices(PlayerData playerData) {
        List<AugmentChoice> choices = new ArrayList<>();
        if (playerData == null) {
            return choices;
        }

        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        PassiveTier activeTier = resolveActiveOfferTier(playerData);
        if (activeTier == null) {
            return choices;
        }

        List<String> tierOffers = offers.getOrDefault(activeTier.name(), List.of());
        for (String id : tierOffers) {
            choices.add(new AugmentChoice(id, activeTier));
        }
        return choices;
    }

    private PassiveTier resolveActiveOfferTier(PlayerData playerData) {
        if (playerData == null) {
            return null;
        }
        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        PassiveTier[] priority = {
            PassiveTier.MYTHIC,
            PassiveTier.LEGENDARY,
            PassiveTier.ELITE,
            PassiveTier.COMMON
        };
        for (PassiveTier tier : priority) {
            if (!offers.getOrDefault(tier.name(), List.of()).isEmpty()) {
                return tier;
            }
        }
        return null;
    }

    private record AugmentChoice(String id, PassiveTier tier) {
    }

    private record CardSection(String title, String body, String color) {
    }

    private record NoAugmentState(String title, String body, String bodyColor) {
    }

    private void applyCard(@Nonnull UICommandBuilder ui,
            int slotIndex,
            String offerId,
            AugmentDefinition augment,
            NoAugmentState noAugmentState) {
        String titleSelector = "#AugmentCard" + slotIndex + "Title";
        String descriptionSelector = "#AugmentCard" + slotIndex + "Description";
        String iconSelector = "#AugmentCard" + slotIndex + "Icon";
        String chooseSelector = "#AugmentCard" + slotIndex + "Choose";
        String[] sectionSelectors = cardSectionSelectors(slotIndex);

        AugmentPresentationMapper.AugmentPresentationData presentation = augment != null
                ? augmentPresentationMapper.map(augment, offerId)
                : null;
        String iconItemId = presentation != null
                ? presentation.iconItemId()
                : augmentPresentationMapper.resolveIconItemId(null);
        ui.set(iconSelector + ".ItemId", iconItemId);
        ui.set(iconSelector + ".Visible", true);

        hideAllCardSectionRows(ui, sectionSelectors);

        if (augment == null) {
            NoAugmentState fallback = noAugmentState != null
                    ? noAugmentState
                    : new NoAugmentState(
                            tr("ui.augments.no_augment.title", "NO AUGMENT"),
                            tr("ui.augments.no_augment.body", "No augments are currently available."),
                            null);
            ui.set(titleSelector + ".Text", fallback.title());
            ui.set(descriptionSelector + ".Text", fallback.body());
            if (fallback.bodyColor() != null && !fallback.bodyColor().isBlank()) {
                ui.set(descriptionSelector + ".Style.TextColor", normalizeColor(fallback.bodyColor(), "#e6edf5"));
            } else {
                ui.set(descriptionSelector + ".Style.TextColor", "#e6edf5");
            }
            ui.set(chooseSelector + ".Visible", false);
            return;
        }

        ui.set(chooseSelector + ".Visible", true);

        String title = presentation != null ? presentation.displayName() : augment.getName();
        ui.set(titleSelector + ".Text", title.toUpperCase(Locale.ROOT));

            // If this is a common augment, show only the individual stat and its rolled value
            if (presentation != null && presentation.commonStatOffer() != null) {
                CommonAugment.CommonStatOffer statOffer = presentation.commonStatOffer();
                String statName = augmentPresentationMapper.formatCommonStatDisplayName(statOffer.attributeKey());
                double value = statOffer.rolledValue();
                double roundedVal = Math.round(value * 100.0D) / 100.0D;
                String statValueStr = (roundedVal == (long) roundedVal) ? String.format(Locale.ROOT, "%d", (long) roundedVal) : String.format(Locale.ROOT, "%.2f", roundedVal);
                String statLine = statName + ": " + statValueStr;
                String selector = sectionSelectors[0];
                ui.set(selector + ".Text", statLine);
                ui.set(selector + ".Style.TextColor", normalizeColor("#8adf9e", CARD_SECTION_DEFAULT_COLORS[0]));
                ui.set(selector + ".Visible", true);
                // Hide other sections
                for (int i = 1; i < sectionSelectors.length; i++) {
                    ui.set(sectionSelectors[i] + ".Visible", false);
                    ui.set(sectionSelectors[i] + ".Text", "");
                }
                ui.set(descriptionSelector + ".Text", "Small raw boost to your core stat.");
            } else {
                String description = augment.getDescription();
                if (description == null || description.isBlank()) {
                    description = tr("ui.augments.no_description", "No description provided.");
                }
                ui.set(descriptionSelector + ".Text", description);
                List<CardSection> sections = buildCardSections(augment);
                for (int index = 0; index < sections.size() && index < sectionSelectors.length; index++) {
                    CardSection section = sections.get(index);
                    String body = buildCardSectionText(section);
                    if (body.isBlank()) {
                        continue;
                    }
                    String selector = sectionSelectors[index];
                    ui.set(selector + ".Text", body);
                    ui.set(selector + ".Style.TextColor", normalizeColor(section.color(), CARD_SECTION_DEFAULT_COLORS[index]));
                    ui.set(selector + ".Visible", true);
                }
            }
    }

    private String[] cardSectionSelectors(int slotIndex) {
        String[] selectors = new String[CARD_SECTION_SUFFIXES.length];
        for (int i = 0; i < CARD_SECTION_SUFFIXES.length; i++) {
            selectors[i] = "#AugmentCard" + slotIndex + CARD_SECTION_SUFFIXES[i];
        }
        return selectors;
    }

    private void hideAllCardSectionRows(@Nonnull UICommandBuilder ui, @Nonnull String[] sectionSelectors) {
        for (String selector : sectionSelectors) {
            ui.set(selector + ".Visible", false);
            ui.set(selector + ".Text", "");
        }
    }

    private List<CardSection> buildCardSections(@Nonnull AugmentDefinition augment) {
        List<CardSection> sections = new ArrayList<>();
        for (AugmentDefinition.UiSection rawSection : augment.getUiSections()) {
            if (rawSection == null) {
                continue;
            }
            String rawTitle = rawSection.title() == null ? "" : rawSection.title().trim();
            String body = normalizeMultilineText(rawSection.body());
            String title = rawTitle;
            if (isCompactTimingSection(rawTitle)) {
                title = "";
                body = compactTimingBody(rawTitle, body);
            }
            if (title.isBlank() && body.isBlank()) {
                continue;
            }
            sections.add(new CardSection(title, body, rawSection.color()));
            if (sections.size() >= CARD_SECTION_LIMIT) {
                break;
            }
        }
        return sections;
    }

    private String buildCardSectionText(@Nonnull CardSection section) {
        String title = section.title() == null ? "" : section.title().trim();
        String body = normalizeMultilineText(section.body());
        if (title.isBlank()) {
            return body;
        }
        if (body.isBlank()) {
            return title;
        }
        return title + "\n" + body;
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

    private boolean isCompactTimingSection(String title) {
        String normalized = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        return DURATION_TITLE.equals(normalized) || COOLDOWN_TITLE.equals(normalized);
    }

    private String compactTimingBody(String title, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        boolean isDuration = DURATION_TITLE.equals(title == null ? "" : title.trim().toLowerCase(Locale.ROOT));
        List<String> compactLines = new ArrayList<>();
        for (String rawLine : body.split("\\n", -1)) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            compactLines.add(isDuration ? normalizeDurationLine(line) : line);
        }
        return String.join(" | ", compactLines);
    }

    private String normalizeDurationLine(String line) {
        String normalized = line.replaceFirst("(?i)^duration\\s+per\\s+stack\\s*:\\s*\\+?([0-9]+(?:\\.[0-9]+)?)x?$", "Duration: $1s");
        normalized = normalized.replaceFirst("(?i)^duration\\s*:\\s*\\+?([0-9]+(?:\\.[0-9]+)?)x$", "Duration: $1s");
        normalized = normalized.replaceFirst("(?i)^seconds\\s*:\\s*\\+?([0-9]+(?:\\.[0-9]+)?)$", "Duration: $1s");
        return normalized;
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

    private NoAugmentState resolveNoAugmentState(PlayerData playerData, List<AugmentDefinition> augments) {
        if (augments != null && !augments.isEmpty()) {
            return null;
        }

        if (augmentManager == null || augmentUnlockManager == null || playerDataManager == null) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.system_unavailable",
                        "Augment system is unavailable right now. Please contact an admin."),
                    null);
        }

        if (playerData == null) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.playerdata_unavailable",
                        "Unable to load your player data. Try reopening this page."),
                    null);
        }

        if (augmentManager.getAugments().isEmpty()) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.definitions_missing",
                        "No augment definitions are loaded. Check /mods/EndlessLeveling/augments."),
                    null);
        }

        int level = Math.max(1, playerData.getLevel());
        int eligibleMilestones = augmentUnlockManager.getEligibleMilestoneCount(playerData, level);
        AugmentUnlockManager.NextUnlockPreview nextUnlockPreview =
            augmentUnlockManager.getNextUnlockPreview(playerData, level);
        String nextUnlockSummary = formatNextUnlockPreview(nextUnlockPreview);
        String nextUnlockColor = resolveTierColor(nextUnlockPreview);

        if (eligibleMilestones <= 0) {
            if (!nextUnlockSummary.isBlank()) {
                return new NoAugmentState(
                        tr("ui.augments.no_augments_yet.title", "NO AUGMENTS YET"),
                tr("ui.augments.no_augments_yet.next_unlock_preview",
                    "No augments available yet.\n\nNext unlock\n{0}",
                    nextUnlockSummary),
                    nextUnlockColor);
            }
            return new NoAugmentState(
                    tr("ui.augments.no_augments.title", "NO AUGMENTS"),
                    tr("ui.augments.no_augments.body", "No augment unlock milestones are configured."),
                    null);
        }

        int grantedMilestones = augmentUnlockManager.getGrantedMilestoneCount(playerData, level);
        if (grantedMilestones >= eligibleMilestones) {
            if (!nextUnlockSummary.isBlank()) {
                return new NoAugmentState(
                        tr("ui.augments.all_claimed.title", "ALL CLAIMED"),
                tr("ui.augments.all_claimed.next_unlock_preview",
                "You have claimed all currently unlocked augments.\n\nNext unlock\n{0}",
                nextUnlockSummary),
                nextUnlockColor);
            }
            return new NoAugmentState(
                    tr("ui.augments.all_claimed.title", "ALL CLAIMED"),
                tr("ui.augments.all_claimed.done", "You have already claimed all configured augment unlocks."),
                null);
        }

        return new NoAugmentState(
                tr("ui.augments.error.title", "AUGMENT ERROR"),
                tr("ui.augments.error.offers_missing",
                "Unlocked augments are missing from your offers. Ask an admin to run /el augment refresh <player>."),
            null);
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
    }

    private String formatNextUnlockPreview(AugmentUnlockManager.NextUnlockPreview preview) {
        if (preview == null || preview.tier() == null) {
            return "";
        }

        int requiredLevel = Math.max(1, preview.requiredPlayerLevel());
        int requiredPrestige = Math.max(0, preview.requiredPrestigeLevel());
        String tier = preview.tier().name();

        if (requiredPrestige > 0) {
            return tier + "  •  Prestige " + requiredPrestige + "  •  Level " + requiredLevel;
        }

        return tier + "  •  Level " + requiredLevel;
    }

    private String resolveTierColor(AugmentUnlockManager.NextUnlockPreview preview) {
        PassiveTier tier = preview != null ? preview.tier() : null;
        return AugmentTheme.profileTierColor(tier);
    }
}