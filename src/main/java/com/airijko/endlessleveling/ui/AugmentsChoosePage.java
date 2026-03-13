package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.util.Lang;
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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Augments page that displays three random augment definitions.
 */
public class AugmentsChoosePage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final int CARD_COUNT = 3;
    private static final String COLOR_BUFF = "#8adf9e";
    private static final String COLOR_DEBUFF = "#ff9a9a";
    private static final String COLOR_COOLDOWN = "#ffd56b";
    private static final String COLOR_DURATION = "#9ecbff";
    private static final String COLOR_BRACKET_NOTES = "#c9d2de";
    private static final String COLOR_SELF_DAMAGE = "#ffb86b";
    private static final String COLOR_NEUTRAL = "#e6edf5";

    private static final Map<String, String> BUFF_NAME_OVERRIDES = createBuffNameOverrides();

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;
    private final AugmentValueFormatter valueFormatter;
    private final AugmentPresentationMapper augmentPresentationMapper;

    public AugmentsChoosePage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.playerRef = playerRef;
        this.valueFormatter = new AugmentValueFormatter(this::tr);
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
            builder.append(tr("ui.augments.remaining.header",
                    "[EndlessLeveling] You still have more augments to choose from:")).append("\n");
            for (PassiveTier remainingTier : remainingTiers) {
                builder.append("- ").append(remainingTier.name()).append("\n");
            }
            builder.append(tr("ui.augments.remaining.footer", "Choose again now."));
            playerRef.sendMessage(Message.raw(builder.toString()).color("#4fd7f7"));
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
        PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON };
        for (PassiveTier tier : priority) {
            if (!offers.getOrDefault(tier.name(), List.of()).isEmpty()) {
                return tier;
            }
        }
        return null;
    }

    private record AugmentChoice(String id, PassiveTier tier) {
    }

    private record FormattedSection(String body, List<String> notes) {
    }

    private record NoAugmentState(String title, String body) {
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
        String cooldownSelector = "#AugmentCard" + slotIndex + "Cooldown";
        String durationSelector = "#AugmentCard" + slotIndex + "Duration";
        String buffsSelector = "#AugmentCard" + slotIndex + "Buffs";
        String debuffsSelector = "#AugmentCard" + slotIndex + "Debuffs";
        String selfDamageSelector = "#AugmentCard" + slotIndex + "SelfDamage";
        String neutralSelector = "#AugmentCard" + slotIndex + "Neutral";
        String notesSelector = "#AugmentCard" + slotIndex + "Notes";

        AugmentPresentationMapper.AugmentPresentationData presentation = augment != null
                ? augmentPresentationMapper.map(augment, offerId)
                : null;
        CommonAugment.CommonStatOffer commonOffer = presentation != null ? presentation.commonStatOffer() : null;
        String iconItemId = presentation != null
                ? presentation.iconItemId()
                : augmentPresentationMapper.resolveIconItemId(null);
        ui.set(iconSelector + ".ItemId", iconItemId);
        ui.set(iconSelector + ".Visible", true);

        if (augment == null) {
            NoAugmentState fallback = noAugmentState != null
                    ? noAugmentState
                    : new NoAugmentState(
                            tr("ui.augments.no_augment.title", "NO AUGMENT"),
                            tr("ui.augments.no_augment.body", "No augments are currently available."));
            ui.set(titleSelector + ".Text", fallback.title());
            ui.set(descriptionSelector + ".Text", fallback.body());
            ui.set(chooseSelector + ".Visible", false);
            ui.set(cooldownSelector + ".Visible", false);
            ui.set(durationSelector + ".Visible", false);
            ui.set(buffsSelector + ".Visible", false);
            ui.set(debuffsSelector + ".Visible", false);
            ui.set(selfDamageSelector + ".Visible", false);
            ui.set(neutralSelector + ".Visible", false);
            ui.set(notesSelector + ".Visible", false);
            return;
        }

        ui.set(chooseSelector + ".Visible", true);

        String title = presentation != null ? presentation.displayName() : augment.getName();
        ui.set(titleSelector + ".Text", title.toUpperCase(Locale.ROOT));

        if (commonOffer != null && CommonAugment.ID.equalsIgnoreCase(augment.getId())) {
            String description = augment.getDescription();
            if (description == null || description.isBlank()) {
                description = tr("ui.augments.no_description", "No description provided.");
            }
            ui.set(descriptionSelector + ".Text", description);

            ui.set(cooldownSelector + ".Visible", false);
            ui.set(durationSelector + ".Visible", false);
            ui.set(debuffsSelector + ".Visible", false);
            ui.set(selfDamageSelector + ".Visible", false);
            ui.set(neutralSelector + ".Visible", false);
            ui.set(notesSelector + ".Visible", false);

            String line = valueFormatter.formatSingleValueLine(commonOffer.attributeKey(),
                    commonOffer.rolledValue(),
                    commonOffer.attributeKey());
            ui.set(buffsSelector + ".Text", line);
            ui.set(buffsSelector + ".Style.TextColor", COLOR_BUFF);
            ui.set(buffsSelector + ".Visible", true);
            return;
        }

        String description = augment.getDescription();
        if (description == null || description.isBlank()) {
            description = tr("ui.augments.no_description", "No description provided.");
        }
        ui.set(descriptionSelector + ".Text", description);

        Map<String, Object> passives = augment.getPassives();
        List<String> bracketNotes = new ArrayList<>();
        List<String> allSelfDamageLines = new ArrayList<>();
        List<String> allNeutralLines = new ArrayList<>();

        String cooldownText = formatCooldown(passives);
        if (cooldownText == null) {
            ui.set(cooldownSelector + ".Visible", false);
        } else {
            FormattedSection formatted = splitBracketNotes(cooldownText);
            if (formatted.body().isBlank()) {
                ui.set(cooldownSelector + ".Visible", false);
            } else {
                ui.set(cooldownSelector + ".Text", formatted.body());
                ui.set(cooldownSelector + ".Style.TextColor", COLOR_COOLDOWN);
                ui.set(cooldownSelector + ".Visible", true);
            }
            bracketNotes.addAll(formatted.notes());
        }

        String durationText = formatDuration(passives);
        if (durationText == null) {
            ui.set(durationSelector + ".Visible", false);
        } else {
            FormattedSection formatted = splitBracketNotes(durationText);
            if (formatted.body().isBlank()) {
                ui.set(durationSelector + ".Visible", false);
            } else {
                ui.set(durationSelector + ".Text", formatted.body());
                ui.set(durationSelector + ".Style.TextColor", COLOR_DURATION);
                ui.set(durationSelector + ".Visible", true);
            }
            bracketNotes.addAll(formatted.notes());
        }

        String buffsText = formatBuffs(passives);
        if (buffsText == null || buffsText.isBlank()) {
            ui.set(buffsSelector + ".Visible", false);
        } else {
            List<String> buffLines = new ArrayList<>();
            List<String> selfDamageLines = new ArrayList<>();
            List<String> neutralLines = new ArrayList<>();
            splitSpecialLines(buffsText, buffLines, selfDamageLines, neutralLines);
            allSelfDamageLines.addAll(selfDamageLines);
            allNeutralLines.addAll(neutralLines);

            String filteredBuffText = String.join("\n", buffLines);
            FormattedSection filtered = splitBracketNotes(filteredBuffText);

            if (filtered.body().isBlank()) {
                ui.set(buffsSelector + ".Visible", false);
            } else {
                ui.set(buffsSelector + ".Text", filtered.body());
                ui.set(buffsSelector + ".Style.TextColor", COLOR_BUFF);
                ui.set(buffsSelector + ".Visible", true);
            }
            bracketNotes.addAll(filtered.notes());
        }

        String debuffsText = formatDebuffs(passives);
        if (debuffsText == null || debuffsText.isBlank()) {
            ui.set(debuffsSelector + ".Visible", false);
        } else {
            List<String> debuffLines = new ArrayList<>();
            List<String> selfDamageLines = new ArrayList<>();
            List<String> neutralLines = new ArrayList<>();
            splitSpecialLines(debuffsText, debuffLines, selfDamageLines, neutralLines);
            allSelfDamageLines.addAll(selfDamageLines);
            allNeutralLines.addAll(neutralLines);

            String filteredDebuffText = String.join("\n", debuffLines);
            FormattedSection formatted = splitBracketNotes(filteredDebuffText);
            if (formatted.body().isBlank()) {
                ui.set(debuffsSelector + ".Visible", false);
            } else {
                ui.set(debuffsSelector + ".Text", formatted.body());
                ui.set(debuffsSelector + ".Style.TextColor", COLOR_DEBUFF);
                ui.set(debuffsSelector + ".Visible", true);
            }
            bracketNotes.addAll(formatted.notes());
        }

        applySpecialRows(ui, selfDamageSelector, neutralSelector, allSelfDamageLines, allNeutralLines);

        if (bracketNotes.isEmpty()) {
            ui.set(notesSelector + ".Visible", false);
        } else {
            ui.set(notesSelector + ".Text", String.join("\n", bracketNotes));
            ui.set(notesSelector + ".Style.TextColor", COLOR_BRACKET_NOTES);
            ui.set(notesSelector + ".Visible", true);
        }
    }

    private void splitSpecialLines(String source,
            List<String> normalLines,
            List<String> selfDamageLines,
            List<String> neutralLines) {
        if (source == null || source.isBlank()) {
            return;
        }
        for (String line : source.split("\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (startsWithLabel(trimmed, "self_damage", "Self Damage")) {
                selfDamageLines.add(trimmed);
            } else if (startsWithLabel(trimmed, "trigger_threshold", "Trigger Threshold")) {
                neutralLines.add(trimmed);
            } else {
                normalLines.add(trimmed);
            }
        }
    }

    private boolean startsWithLabel(String line, String labelKey, String fallback) {
        String translated = tr("ui.augments.effect.label." + labelKey, fallback);
        return line.toLowerCase(Locale.ROOT)
                .startsWith((translated + ":").toLowerCase(Locale.ROOT));
    }

    private void applySpecialRows(UICommandBuilder ui,
            String selfDamageSelector,
            String neutralSelector,
            List<String> selfDamageLines,
            List<String> neutralLines) {
        if (selfDamageLines == null || selfDamageLines.isEmpty()) {
            ui.set(selfDamageSelector + ".Visible", false);
        } else {
            ui.set(selfDamageSelector + ".Text", String.join("\n", selfDamageLines));
            ui.set(selfDamageSelector + ".Style.TextColor", COLOR_SELF_DAMAGE);
            ui.set(selfDamageSelector + ".Visible", true);
        }

        if (neutralLines == null || neutralLines.isEmpty()) {
            ui.set(neutralSelector + ".Visible", false);
        } else {
            ui.set(neutralSelector + ".Text", String.join("\n", neutralLines));
            ui.set(neutralSelector + ".Style.TextColor", COLOR_NEUTRAL);
            ui.set(neutralSelector + ".Visible", true);
        }
    }

    private FormattedSection splitBracketNotes(String text) {
        if (text == null || text.isBlank()) {
            return new FormattedSection("", List.of());
        }

        List<String> bodyLines = new ArrayList<>();
        List<String> noteLines = new ArrayList<>();
        for (String rawLine : text.split("\\n")) {
            String working = rawLine == null ? "" : rawLine.trim();
            while (true) {
                int open = working.indexOf('(');
                int close = open >= 0 ? working.indexOf(')', open + 1) : -1;
                if (open < 0 || close < 0) {
                    break;
                }
                String note = working.substring(open, close + 1).trim();
                if (!note.isBlank()) {
                    noteLines.add(note);
                }
                working = (working.substring(0, open) + working.substring(close + 1))
                        .replaceAll("\\s{2,}", " ")
                        .trim();
            }
            if (!working.isBlank()) {
                bodyLines.add(working);
            }
        }
        return new FormattedSection(String.join("\n", bodyLines), noteLines);
    }

    private NoAugmentState resolveNoAugmentState(PlayerData playerData, List<AugmentDefinition> augments) {
        if (augments != null && !augments.isEmpty()) {
            return null;
        }

        if (augmentManager == null || augmentUnlockManager == null || playerDataManager == null) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.system_unavailable",
                            "Augment system is unavailable right now. Please contact an admin."));
        }

        if (playerData == null) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.playerdata_unavailable",
                            "Unable to load your player data. Try reopening this page."));
        }

        if (augmentManager.getAugments().isEmpty()) {
            return new NoAugmentState(
                    tr("ui.augments.error.title", "AUGMENT ERROR"),
                    tr("ui.augments.error.definitions_missing",
                            "No augment definitions are loaded. Check /mods/EndlessLeveling/augments."));
        }

        int level = Math.max(1, playerData.getLevel());
        int eligibleMilestones = augmentUnlockManager.getEligibleMilestoneCount(playerData, level);
        int nextUnlockLevel = augmentUnlockManager.getNextUnlockLevel(playerData, level);

        if (eligibleMilestones <= 0) {
            if (nextUnlockLevel > 0) {
                return new NoAugmentState(
                        tr("ui.augments.no_augments_yet.title", "NO AUGMENTS YET"),
                        tr("ui.augments.no_augments_yet.body",
                                "No augments available yet. Next unlock at level {0}.",
                                nextUnlockLevel));
            }
            return new NoAugmentState(
                    tr("ui.augments.no_augments.title", "NO AUGMENTS"),
                    tr("ui.augments.no_augments.body", "No augment unlock milestones are configured."));
        }

        int grantedMilestones = augmentUnlockManager.getGrantedMilestoneCount(playerData, level);
        if (grantedMilestones >= eligibleMilestones) {
            if (nextUnlockLevel > 0) {
                return new NoAugmentState(
                        tr("ui.augments.all_claimed.title", "ALL CLAIMED"),
                        tr("ui.augments.all_claimed.next_unlock",
                                "You have claimed all currently unlocked augments. Next unlock at level {0}.",
                                nextUnlockLevel));
            }
            return new NoAugmentState(
                    tr("ui.augments.all_claimed.title", "ALL CLAIMED"),
                    tr("ui.augments.all_claimed.done", "You have already claimed all configured augment unlocks."));
        }

        return new NoAugmentState(
                tr("ui.augments.error.title", "AUGMENT ERROR"),
                tr("ui.augments.error.offers_missing",
                        "Unlocked augments are missing from your offers. Ask an admin to run /el augment refresh <player>."));
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
    }

    private static Map<String, String> createBuffNameOverrides() {
        Map<String, String> map = new HashMap<>();
        map.put("life_force", "Life Force");
        map.put("strength", "Strength");
        map.put("sorcery", "Sorcery");
        map.put("haste", "Haste");
        map.put("haste_bonus", "Haste");
        map.put("crit_damage", "Critical Damage");
        map.put("crit_damage_bonus", "Critical Damage");
        map.put("critical_damage", "Critical Damage");
        map.put("crit_chance", "Critical Chance");
        map.put("critical_chance", "Critical Chance");
        map.put("life_steal", "Life Steal");
        map.put("life_steal_scaling", "Life Steal");
        map.put("heal_on_crit", "Life Steal (Crit)");
        map.put("heal_on_kill", "Heal on Kill");
        map.put("heal_over_time", "Deferred Damage");
        map.put("heal_to_damage", "Heal to Damage");
        map.put("bonus_damage_on_hit", "Bonus Damage");
        map.put("bonus_damage", "Bonus Damage");
        map.put("max_bonus_damage", "Bonus Damage");
        map.put("max_bonus_ferocity", "Ferocity");
        map.put("strength_from_max_health", "Strength");
        map.put("sorcery_from_max_health", "Sorcery");
        map.put("sorcery_bonus_high", "Sorcery");
        map.put("sorcery_penalty_low", "Sorcery");
        map.put("crit_defense", "Damage Reduction");
        map.put("taunt_radius", "Taunt Radius");
        map.put("bonus_damage_by_distance", "Bonus Damage");
        map.put("bonus_damage_vs_hp_ratio", "Bonus Damage");
        map.put("execution_heal", "Execute Heal");
        map.put("self_damage", "Self Damage");
        map.put("percent_of_current_hp", "Self Damage");
        map.put("movement_speed_bonus", "Move Speed");
        map.put("movement_speed", "Move Speed");
        map.put("resistance_bonus", "Resistance");
        map.put("resistance", "Resistance");
        map.put("precision", "Critical Chance");
        map.put("defense", "Defense");
        map.put("ferocity", "Ferocity");
        map.put("stamina", "Stamina");
        map.put("flow", "Flow");
        map.put("discipline", "Discipline");
        map.put("wither", "Wither");
        map.put("slow_percent", "Slow");
        map.put("mana", "Mana");
        map.put("mana_from_sorcery", "Mana");
        map.put("sorcery_from_mana", "Sorcery");
        map.put("health_threshold", "Health Threshold");
        map.put("trigger_threshold", "Trigger Threshold");
        map.put("full_value_at_health_percent", "Full Value Threshold");
        map.put("max_distance", "Max distance (full bonus)");
        return map;
    }

    private String formatCooldown(Map<String, Object> passives) {
        return valueFormatter.formatCooldown(passives);
    }

    private String formatDuration(Map<String, Object> passives) {
        return valueFormatter.formatDuration(passives);
    }

    private Double findNumericField(Map<String, Object> passives, String... keys) {
        if (passives == null || passives.isEmpty()) {
            return null;
        }
        for (Object passiveObj : passives.values()) {
            Map<String, Object> passive = asMap(passiveObj);
            if (passive == null) {
                continue;
            }
            for (String key : keys) {
                Object val = passive.get(key);
                Double number = toDouble(val);
                if (number != null) {
                    return number;
                }
            }
        }
        return null;
    }

    private String formatBuffs(Map<String, Object> passives) {
        return valueFormatter.formatBuffs(passives);
    }

    private String formatDebuffs(Map<String, Object> passives) {
        return valueFormatter.formatDebuffs(passives);
    }

    private boolean isTimingKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("duration") || lower.contains("cooldown");
    }

    private boolean isMetadataOnlyKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("full_value_at_health_percent")
                || lower.equals("max_ratio")
                || lower.equals("min_distance")
                || lower.equals("max_distance")
                || lower.equals("scaling_stat")
                || lower.equals("reference_stat")
                || lower.equals("scaling_type")
                || lower.equals("calculation")
                || lower.equals("attack_type")
                || lower.equals("target_stat")
                || lower.equals("resource")
                || lower.equals("condition")
                || lower.equals("category")
                || lower.equals("pve_only")
                || lower.equals("reset_on_kill")
                || lower.equals("active_until_max_stacks")
                || lower.equals("trigger_cooldown")
                || lower.equals("cooldown_per_target")
                || lower.equals("target_debuff")
                || lower.equals("heal_to_damage")
                || lower.equals("break_conditions")
                || lower.equals("on_miss")
                || lower.equals("on_damage_taken");
    }

    private String formatEffects(Map<String, Object> passives, boolean positives) {
        if (passives == null || passives.isEmpty()) {
            return null;
        }

        Set<String> uniqueLines = new LinkedHashSet<>();

        // Directly handle top-level buffs/debuffs maps if present.
        String directKey = positives ? "buffs" : "debuffs";
        Map<String, Object> direct = asMap(passives.get(directKey));
        if (direct != null && !direct.isEmpty()) {
            String rendered = renderBuffMap(direct, positives);
            if (!rendered.isBlank()) {
                for (String line : rendered.split("\\n")) {
                    if (!line.isBlank()) {
                        uniqueLines.add(line);
                    }
                }
            }
        }

        // Priority: explicit buffs/debuffs map on any passive (use passive name as
        // fallback label), but aggregate across ALL passives.
        for (Map.Entry<String, Object> passiveEntry : passives.entrySet()) {
            String passiveName = passiveEntry.getKey();
            Object passiveObj = passiveEntry.getValue();
            Map<String, Object> passive = asMap(passiveObj);
            if (passive == null) {
                continue;
            }
            String primaryKey = positives ? "buffs" : "debuffs";
            Map<String, Object> effects = asMap(passive.get(primaryKey));
            if (effects == null && positives) {
                effects = asMap(passive.get("buffs")); // fallback if only buffs map exists
            }
            if (effects != null && !effects.isEmpty()) {
                String rendered = renderBuffMap(effects, positives, passiveName);
                if (!rendered.isBlank()) {
                    for (String line : rendered.split("\\n")) {
                        if (!line.isBlank()) {
                            uniqueLines.add(line);
                        }
                    }
                }
            }
        }

        // Fallback: collect numeric fields that look like effects, using passive name
        // as label when needed.
        for (Map.Entry<String, Object> passiveEntry : passives.entrySet()) {
            String passiveName = passiveEntry.getKey();
            Object passiveObj = passiveEntry.getValue();
            Map<String, Object> passive = asMap(passiveObj);
            if (passive == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : passive.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                if (isTimingKey(key)) {
                    continue; // skip timing fields
                }
                Object val = entry.getValue();
                collectEffect(uniqueLines, key, val, positives, passiveName, passive);
            }
        }

        if (uniqueLines.isEmpty()) {
            return null;
        }
        return String.join("\n", uniqueLines);
    }

    private String renderBuffMap(Map<String, Object> buffs, boolean positives) {
        return renderBuffMap(buffs, positives, null);
    }

    private String renderBuffMap(Map<String, Object> buffs, boolean positives, String fallbackLabel) {
        Set<String> parts = new LinkedHashSet<>();
        Integer maxStacks = null;
        for (Map.Entry<String, Object> entry : buffs.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            Object val = entry.getValue();

            if (isTimingKey(key)) {
                continue; // timing belongs in footer
            }

            // Capture standalone max_stacks entries.
            if (key.equalsIgnoreCase("max_stacks")) {
                Integer stacks = toInteger(val);
                if (stacks != null) {
                    maxStacks = maxStacks == null ? stacks : Math.max(maxStacks, stacks);
                }
                continue; // do not render as a buff line
            }

            Map<String, Object> nested = asMap(val);
            if (nested != null && nested.containsKey("max_stacks")) {
                Integer stacks = toInteger(nested.get("max_stacks"));
                if (stacks != null) {
                    maxStacks = maxStacks == null ? stacks : Math.max(maxStacks, stacks);
                }
            }
            collectEffect(parts,
                    key,
                    val,
                    positives,
                    fallbackLabel == null ? key : fallbackLabel,
                    nested != null ? nested : buffs);
        }

        if (maxStacks != null) {
            parts.add(tr("ui.augments.effect.max_stacks", "Max stacks: {0}", maxStacks));
        }
        return String.join("\n", parts);
    }

    private void collectEffect(Set<String> parts,
            String key,
            Object val,
            boolean positives,
            String fallbackLabel,
            Map<String, Object> parentPassive) {
        if (isTimingKey(key) || isMetadataOnlyKey(key)) {
            return; // timing values render in the footer, not the buff list
        }

        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String thresholdSuffix = resolveThresholdSuffix(parentPassive);

        Double scalar = toDouble(val);
        if (scalar != null) {
            if (normalizedKey.startsWith("max_")) {
                if ((positives && scalar > 0) || (!positives && scalar < 0)) {
                    parts.add(formatRangeEntry(normalizedKey, 0.0D, scalar, fallbackLabel, thresholdSuffix));
                }
                return;
            }
            if ((positives && scalar > 0) || (!positives && scalar < 0)) {
                parts.add(formatBuffEntry(key, scalar, null, fallbackLabel, thresholdSuffix));
            }
            return;
        }

        Map<String, Object> nested = asMap(val);
        if (nested == null) {
            return;
        }

        String nestedThresholdSuffix = resolveThresholdSuffix(nested);
        if (nestedThresholdSuffix == null || nestedThresholdSuffix.isBlank()) {
            nestedThresholdSuffix = thresholdSuffix;
        }

        Double minValue = toDouble(nested.get("min_value"));
        Double maxValue = toDouble(nested.get("max_value"));
        if (minValue != null && maxValue != null) {
            if ((positives && maxValue > 0) || (!positives && minValue < 0)) {
                parts.add(formatRangeEntry(key, minValue, maxValue, fallbackLabel, nestedThresholdSuffix));
            }
            return;
        }

        Double baseValue = toDouble(nested.get("value"));
        if (maxValue != null && (baseValue == null || Math.abs(baseValue) < 0.0001D)) {
            if ((positives && maxValue > 0) || (!positives && maxValue < 0)) {
                parts.add(formatRangeEntry(key, 0.0D, maxValue, fallbackLabel, nestedThresholdSuffix));
            }
            return;
        }

        Double chosen = firstNumber(nested, "value", "max_value", "value_per_stack", key);
        String forcedSuffix = null;
        if (chosen == null) {
            return;
        }

        if ((positives && chosen > 0) || (!positives && chosen < 0)) {
            parts.add(formatBuffEntry(key, chosen, forcedSuffix, fallbackLabel, nestedThresholdSuffix));
        }
    }

    private String resolveThresholdSuffix(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }

        Double fullValueAtHealth = toDouble(context.get("full_value_at_health_percent"));
        if (fullValueAtHealth != null) {
            return tr("ui.augments.effect.note.full_at_health", " (full at <= {0}% health)",
                    formatNumber(fullValueAtHealth * 100.0D));
        }

        Double maxRatio = toDouble(context.get("max_ratio"));
        if (maxRatio != null) {
            return tr("ui.augments.effect.note.full_at_ratio", " (full at {0}x ratio)",
                    formatNumber(maxRatio));
        }

        Double maxDistance = toDouble(context.get("max_distance"));
        if (maxDistance != null) {
            Double minDistance = toDouble(context.get("min_distance"));
            if (minDistance != null) {
                return tr("ui.augments.effect.note.range", " (range {0}-{1})",
                        formatNumber(minDistance),
                        formatNumber(maxDistance));
            }
            return tr("ui.augments.effect.note.full_at_distance", " (full at {0} distance)",
                    formatNumber(maxDistance));
        }

        return null;
    }

    private String formatRangeEntry(String key,
            double minValue,
            double maxValue,
            String fallbackLabel,
            String suffixNote) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String baseKey = normalizedKey.startsWith("max_") ? normalizedKey.substring(4) : normalizedKey;
        String canonicalBaseKey = baseKey.replace(' ', '_');
        String fallbackName = BUFF_NAME_OVERRIDES.getOrDefault(canonicalBaseKey,
                key == null ? "" : key.replace('_', ' '));
        String label = translateEffectLabel(canonicalBaseKey, fallbackName);
        String semanticKeyForUnit = canonicalBaseKey;
        if ((baseKey.isBlank() || baseKey.equals("value") || baseKey.equals("value_per_stack"))
                && fallbackLabel != null && !fallbackLabel.isBlank()) {
            String normalizedFallbackKey = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
            label = translateEffectLabel(normalizedFallbackKey, fallbackLabel.replace('_', ' '));
            semanticKeyForUnit = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
        }

        double normalizedMin = minValue;
        double normalizedMax = maxValue;
        if (normalizedMin > normalizedMax) {
            double swap = normalizedMin;
            normalizedMin = normalizedMax;
            normalizedMax = swap;
        }

        double unitSource = Math.abs(normalizedMax) >= Math.abs(normalizedMin) ? normalizedMax : normalizedMin;
        String unit = inferSuffix(semanticKeyForUnit, unitSource);
        double displayMin = "%".equals(unit) ? toDisplayPercent(normalizedMin) : normalizedMin;
        double displayMax = "%".equals(unit) ? toDisplayPercent(normalizedMax) : normalizedMax;

        String rendered = capitalize(label) + ": "
                + formatSignedRangeValue(displayMin, unit)
                + " to "
                + formatSignedRangeValue(displayMax, unit);
        if (suffixNote != null && !suffixNote.isBlank()) {
            rendered += suffixNote;
        }
        return rendered;
    }

    private String formatSignedRangeValue(double value, String suffix) {
        String sign = value > 0 ? "+" : "";
        return sign + formatNumber(value) + (suffix == null ? "" : suffix);
    }

    private Double firstNumber(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = map.get(key);
            Double d = toDouble(v);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private String formatBuffEntry(String key,
            double value,
            String forcedSuffix,
            String fallbackLabel,
            String suffixNote) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String canonicalKey = normalizedKey.replace(' ', '_');
        String semanticKeyForUnit = canonicalKey;
        String fallbackName = BUFF_NAME_OVERRIDES.getOrDefault(canonicalKey, key == null ? "" : key.replace('_', ' '));
        String label = translateEffectLabel(canonicalKey, fallbackName);

        // If the key is generic, prefer the parent/fallback label.
        if ((normalizedKey.isBlank() || normalizedKey.equals("value")
                || normalizedKey.equals("max_value")
                || normalizedKey.equals("value_per_stack")) && fallbackLabel != null && !fallbackLabel.isBlank()) {
            String normalizedFallbackKey = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
            label = translateEffectLabel(normalizedFallbackKey, fallbackLabel.replace('_', ' '));
            semanticKeyForUnit = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
        }

        String suffix = forcedSuffix;
        double displayValue = value;

        if (suffix == null) {
            suffix = inferSuffix(semanticKeyForUnit, value);
            if ("%".equals(suffix)) {
                displayValue = toDisplayPercent(value);
            } else if (normalizedKey.contains("ratio")) {
                suffix = "x";
                displayValue = Math.abs(value);
            }
        } else if ("%".equals(suffix)) {
            displayValue = toDisplayPercent(value); // support both fractional and whole-number percent inputs
        }

        // Display decay-related values as losses.
        if (normalizedKey.contains("decay")) {
            displayValue = -Math.abs(displayValue);
        }

        String sign;
        if ("x".equals(suffix)) {
            sign = displayValue < 0 ? "-" : ""; // ratios show magnitude without leading plus
        } else {
            sign = displayValue > 0 ? "+" : "";
        }
        String rendered = capitalize(label) + ": " + sign + formatNumber(displayValue) + suffix;
        if (suffixNote != null && !suffixNote.isBlank()) {
            rendered += suffixNote;
        }
        return rendered;
    }

    private double toDisplayPercent(double value) {
        return Math.abs(value) >= 10.0D ? value : value * 100.0D;
    }

    private String translateEffectLabel(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        return tr("ui.augments.effect.label." + key, fallback);
    }

    private String inferSuffix(String normalizedKey, double value) {
        if (normalizedKey == null) {
            return "";
        }
        if (normalizedKey.contains("ratio")) {
            return "x";
        }
        if (normalizedKey.contains("percent")
                || normalizedKey.contains("max_value")
                || normalizedKey.contains("chance")
                || normalizedKey.contains("crit")
                || normalizedKey.contains("ferocity")
                || normalizedKey.contains("life_steal")
                || normalizedKey.contains("heal")
                || normalizedKey.contains("damage")
                || normalizedKey.contains("strength")
                || normalizedKey.contains("sorcery")
                || normalizedKey.contains("haste")
                || normalizedKey.contains("resistance")
                || normalizedKey.contains("defense")
                || normalizedKey.contains("precision")
                || Math.abs(value) <= 1.0D) {
            return "%";
        }
        return "";
    }

    private Double toDouble(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer toInteger(Object val) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                Object k = entry.getKey();
                if (k != null) {
                    map.put(k.toString(), entry.getValue());
                }
            }
            return map;
        }
        return null;
    }

    private String formatSeconds(double seconds) {
        return tr("ui.time.seconds", "{0}s", formatNumber(seconds));
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String capitalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1);
    }
}