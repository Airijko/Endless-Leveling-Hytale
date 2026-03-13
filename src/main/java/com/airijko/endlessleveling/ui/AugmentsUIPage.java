package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
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
import java.util.LinkedHashSet;
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
    private static final String COLOR_MYTHIC_OWNED = "#b084e0";
    private static final String COLOR_ELITE_OWNED = "#7ec8f5";
    private static final String COLOR_COMMON_OWNED = "#b8bec9";
    private static final String COLOR_UNOWNED = "#d4d9df";
    private static final String COLOR_CHOOSE_AVAILABLE = "#8adf9e";
    private static final String COLOR_CHOOSE_UNAVAILABLE = "#ff9f9f";

    private static final Map<String, String> BUFF_NAME_OVERRIDES = createBuffNameOverrides();

    private static final Map<PassiveTier, Integer> TIER_ORDER = Map.of(
            PassiveTier.MYTHIC, 0,
            PassiveTier.ELITE, 1,
            PassiveTier.COMMON, 2);

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;
    private final AugmentValueFormatter valueFormatter;

    private String searchQuery = "";
    private String selectedAugmentId = null;

    public AugmentsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
        this.augmentUnlockManager = plugin != null ? plugin.getAugmentUnlockManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.playerRef = playerRef;
        this.valueFormatter = new AugmentValueFormatter(this::tr);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Augments/AugmentsPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef);
        ui.set("#SearchInput.Value", this.searchQuery);
        ui.set("#OpenAugmentsChooseButton.Text", "CHOOSE AUGMENTS");
        NavUIHelper.bindNavEvents(events);
        events.addEventBinding(ValueChanged, "#SearchInput", of("@SearchQuery", "#SearchInput.Value"), false);
        events.addEventBinding(Activating, "#OpenAugmentsChooseButton", of("Action", "augment:open_choose"),
                false);

        buildGrid(ui, events);
        applyInfoPanel(ui, selectedAugmentId);
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
        ui.set("#AugmentsResultLabel.Text", "Results: " + totalResults);

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
        CommonAugment.CommonStatOffer commonStatOffer = CommonAugment.parseStatOfferId(augmentId);

        if (def == null) {
            ui.set("#AugmentInfoIcon.Visible", false);
            ui.set("#AugmentInfoName.Text", "Select an augment");
            ui.set("#AugmentInfoName.Style.TextColor", "#9fb6d3");
            ui.set("#AugmentInfoTier.Visible", false);
            ui.set("#AugmentInfoDivider.Visible", false);
            ui.set("#AugmentInfoDescription.Visible", false);
            ui.set("#AugmentInfoDivider2.Visible", false);
            ui.set("#AugmentInfoValues.Visible", false);
            return;
        }

        String iconId = resolveIconItemId(def);
        if (commonStatOffer != null && CommonAugment.ID.equalsIgnoreCase(def.getId())) {
            iconId = SkillAttributeIconResolver.resolveByConfigKey(commonStatOffer.attributeKey(), iconId);
        }
        ui.set("#AugmentInfoIcon.ItemId", iconId);
        ui.set("#AugmentInfoIcon.Visible", true);
        String displayName = def.getName();
        if (commonStatOffer != null && CommonAugment.ID.equalsIgnoreCase(def.getId())) {
            displayName = tr("ui.augments.common_stat.card_name", "{0}",
                    formatCommonStatDisplayName(commonStatOffer.attributeKey()));
        }
        ui.set("#AugmentInfoName.Text", displayName);
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

        String valuesText;
        if (commonStatOffer != null && CommonAugment.ID.equalsIgnoreCase(def.getId())) {
            String singleValue = valueFormatter.formatSingleValueLine(commonStatOffer.attributeKey(),
                    commonStatOffer.rolledValue(),
                    commonStatOffer.attributeKey());
            valuesText = tr("ui.augments.section.buffs", "Buffs:") + "\n" + singleValue;
        } else {
            valuesText = buildValuesText(def);
        }
        boolean hasValues = !valuesText.isBlank();
        ui.set("#AugmentInfoDivider2.Visible", hasValues);
        ui.set("#AugmentInfoValues.Text", valuesText);
        ui.set("#AugmentInfoValues.Visible", hasValues);
    }

    private String buildValuesText(AugmentDefinition def) {
        Map<String, Object> passives = def.getPassives();
        if (passives == null || passives.isEmpty()) {
            return "";
        }

        List<String> lines = new ArrayList<>();

        String cooldown = formatCooldown(passives);
        if (cooldown != null && !cooldown.isBlank()) {
            lines.add(cooldown);
        }

        String duration = formatDuration(passives);
        if (duration != null && !duration.isBlank()) {
            lines.add(duration);
        }

        String buffs = formatBuffs(passives);
        if (buffs != null && !buffs.isBlank()) {
            lines.add(tr("ui.augments.section.buffs", "Buffs:"));
            lines.addAll(List.of(buffs.split("\\n")));
        }

        String debuffs = formatDebuffs(passives);
        if (debuffs != null && !debuffs.isBlank()) {
            lines.add(tr("ui.augments.section.debuffs", "Debuffs:"));
            lines.addAll(List.of(debuffs.split("\\n")));
        }

        return String.join("\n", lines);
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
                effects = asMap(passive.get("buffs"));
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
                    continue;
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
                continue;
            }

            if (key.equalsIgnoreCase("max_stacks")) {
                Integer stacks = toInteger(val);
                if (stacks != null) {
                    maxStacks = maxStacks == null ? stacks : Math.max(maxStacks, stacks);
                }
                continue;
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
            parts.add("Max stacks: " + maxStacks);
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
            return;
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
        if (chosen == null) {
            return;
        }

        if ((positives && chosen > 0) || (!positives && chosen < 0)) {
            parts.add(formatBuffEntry(key, chosen, null, fallbackLabel, nestedThresholdSuffix));
        }
    }

    private String resolveThresholdSuffix(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }

        Double fullValueAtHealth = toDouble(context.get("full_value_at_health_percent"));
        if (fullValueAtHealth != null) {
            return " (full at <= " + formatNumber(fullValueAtHealth * 100.0D) + "% health)";
        }

        Double maxRatio = toDouble(context.get("max_ratio"));
        if (maxRatio != null) {
            return " (full at " + formatNumber(maxRatio) + "x ratio)";
        }

        Double maxDistance = toDouble(context.get("max_distance"));
        if (maxDistance != null) {
            Double minDistance = toDouble(context.get("min_distance"));
            if (minDistance != null) {
                return " (range " + formatNumber(minDistance) + "-" + formatNumber(maxDistance) + ")";
            }
            return " (full at " + formatNumber(maxDistance) + " distance)";
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
        String label = capitalize(fallbackName);
        String semanticKeyForUnit = canonicalBaseKey;
        if ((baseKey.isBlank() || baseKey.equals("value") || baseKey.equals("value_per_stack"))
                && fallbackLabel != null && !fallbackLabel.isBlank()) {
            String normalizedFallbackKey = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
            label = capitalize(fallbackLabel.replace('_', ' '));
            semanticKeyForUnit = normalizedFallbackKey;
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
        String rendered = label + ": "
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
        String label = capitalize(fallbackName);

        if ((normalizedKey.isBlank() || normalizedKey.equals("value")
                || normalizedKey.equals("max_value")
                || normalizedKey.equals("value_per_stack")) && fallbackLabel != null && !fallbackLabel.isBlank()) {
            String normalizedFallbackKey = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
            label = capitalize(fallbackLabel.replace('_', ' '));
            semanticKeyForUnit = normalizedFallbackKey;
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
            displayValue = toDisplayPercent(value);
        }

        if (normalizedKey.contains("decay")) {
            displayValue = -Math.abs(displayValue);
        }

        String sign;
        if ("x".equals(suffix)) {
            sign = displayValue < 0 ? "-" : "";
        } else {
            sign = displayValue > 0 ? "+" : "";
        }
        String rendered = label + ": " + sign + formatNumber(displayValue) + suffix;
        if (suffixNote != null && !suffixNote.isBlank()) {
            rendered += suffixNote;
        }
        return rendered;
    }

    private double toDisplayPercent(double value) {
        return Math.abs(value) >= 10.0D ? value : value * 100.0D;
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
        return formatNumber(seconds) + "s";
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

    private void applyLeftPanel(@Nonnull UICommandBuilder ui, PlayerData playerData) {
        Set<String> ownedIds = resolveOwnedIds(playerData);
        Collection<AugmentDefinition> allDefs = augmentManager != null ? augmentManager.getAugments().values()
                : List.of();

        ui.set("#AugmentStatCommon.Style.TextColor", COLOR_COMMON_OWNED);
        ui.set("#AugmentRerollCommon.Style.TextColor", COLOR_COMMON_OWNED);

        long totalMythic = allDefs.stream().filter(d -> d.getTier() == PassiveTier.MYTHIC).count();
        long totalElite = allDefs.stream().filter(d -> d.getTier() == PassiveTier.ELITE).count();

        int mythicOwned = countSelectedForTier(playerData, PassiveTier.MYTHIC);
        int eliteOwned = countSelectedForTier(playerData, PassiveTier.ELITE);
        int commonOwned = countSelectedForTier(playerData, PassiveTier.COMMON);
        int totalOwned = mythicOwned + eliteOwned + commonOwned;

        ui.set("#AugmentStatTotal.Text", "Total: " + totalOwned + " / " + allDefs.size());
        ui.set("#AugmentStatMythic.Text", "Mythic: " + mythicOwned + " / " + totalMythic);
        ui.set("#AugmentStatElite.Text", "Elite: " + eliteOwned + " / " + totalElite);
        ui.set("#AugmentStatCommon.Text", "Common: " + commonOwned);

        Map<String, Integer> rerolls = playerData != null ? playerData.getAugmentRerollsUsedSnapshot() : Map.of();
        ui.set("#AugmentRerollMythic.Text", "Mythic: " + rerolls.getOrDefault("MYTHIC", 0));
        ui.set("#AugmentRerollElite.Text", "Elite: " + rerolls.getOrDefault("ELITE", 0));
        ui.set("#AugmentRerollCommon.Text", "Common: " + rerolls.getOrDefault("COMMON", 0));
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

            ui.append(cardsSelector + "[" + rowIndex + "]", "Pages/Augments/AugmentGridEntry.ui");
            String base = cardsSelector + "[" + rowIndex + "][" + cardsInCurrentRow + "]";

            ui.set(base + " #ItemIcon.ItemId", resolveIconItemId(def));
            ui.set(base + " #ItemName.Text", def.getName());
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
                    definition != null ? tierColor(definition.getTier()) : COLOR_UNOWNED);

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

                String displayName = definition.getName();
                String icon = resolveIconItemId(definition);
                String groupKey;

                CommonAugment.CommonStatOffer offer = CommonAugment.parseStatOfferId(rawId);
                if (offer != null && CommonAugment.ID.equalsIgnoreCase(definition.getId())) {
                    String attributeKey = offer.attributeKey() == null ? "" : offer.attributeKey().trim();
                    displayName = tr("ui.augments.common_stat.card_name", "{0}",
                            formatCommonStatDisplayName(attributeKey));
                    icon = SkillAttributeIconResolver.resolveByConfigKey(attributeKey, icon);
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

    private String formatCommonStatDisplayName(String attributeKey) {
        if (attributeKey == null || attributeKey.isBlank()) {
            return tr("ui.augments.effect.label.common", "Common Stat");
        }
        String normalized = attributeKey.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String[] parts = normalized.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private record OwnedAugmentCard(String id, String displayName, String iconItemId) {
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

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
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
