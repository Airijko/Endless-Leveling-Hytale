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
    private static final String COLOR_COMMON_OWNED = "#e6c168";
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

    private String searchQuery = "";
    private String selectedAugmentId = null;

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
        ui.set("#OpenAugmentsChooseButton.Text", "CHOOSE AUGMENTS");
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
        buildSection(ui, events, unlockedAugments, "#UnlockedCards", "#UnlockedSection", ownedIds);

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
            ui.set("#AugmentInfoName.Text", "Select an augment");
            ui.set("#AugmentInfoName.Style.TextColor", "#9fb6d3");
            ui.set("#AugmentInfoTier.Visible", false);
            ui.set("#AugmentInfoDivider.Visible", false);
            ui.set("#AugmentInfoDescription.Visible", false);
            ui.set("#AugmentInfoDivider2.Visible", false);
            ui.set("#AugmentInfoValues.Visible", false);
            return;
        }

        ui.set("#AugmentInfoIcon.ItemId", resolveIconItemId(def));
        ui.set("#AugmentInfoIcon.Visible", true);
        ui.set("#AugmentInfoName.Text", def.getName());
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

        String valuesText = buildValuesText(def);
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
            lines.add("Buffs:");
            lines.addAll(List.of(buffs.split("\\n")));
        }

        String debuffs = formatDebuffs(passives);
        if (debuffs != null && !debuffs.isBlank()) {
            lines.add("Debuffs:");
            lines.addAll(List.of(debuffs.split("\\n")));
        }

        return String.join("\n", lines);
    }

    private String formatCooldown(Map<String, Object> passives) {
        Double value = findNumericField(passives, "trigger_cooldown", "cooldown", "proc_cooldown");
        if (value == null) {
            return null;
        }
        return "Cooldown: " + formatSeconds(value);
    }

    private String formatDuration(Map<String, Object> passives) {
        Double perStack = findNumericField(passives, "duration_per_stack");
        if (perStack != null) {
            return "Duration per stack: " + formatSeconds(perStack);
        }
        Double value = findNumericField(passives, "duration", "effect_duration");
        if (value == null) {
            return null;
        }
        return "Duration: " + formatSeconds(value);
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
        return formatEffects(passives, true);
    }

    private String formatDebuffs(Map<String, Object> passives) {
        return formatEffects(passives, false);
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
                    parts.add(formatRangeEntry(normalizedKey, scalar, fallbackLabel, thresholdSuffix));
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

        Double maxValue = toDouble(nested.get("max_value"));
        Double baseValue = toDouble(nested.get("value"));
        if (maxValue != null && (baseValue == null || Math.abs(baseValue) < 0.0001D)) {
            if ((positives && maxValue > 0) || (!positives && maxValue < 0)) {
                parts.add(formatRangeEntry(key, maxValue, fallbackLabel, nestedThresholdSuffix));
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

    private String formatRangeEntry(String key, double maxValue, String fallbackLabel, String suffixNote) {
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

        String unit = inferSuffix(semanticKeyForUnit, maxValue);
        double displayMax = "%".equals(unit) ? toDisplayPercent(maxValue) : maxValue;
        String rangePrefix = displayMax > 0 ? "+" : (displayMax < 0 ? "-" : "");
        String rendered = label + ": " + rangePrefix + "0-" + formatNumber(Math.abs(displayMax)) + unit;
        if (suffixNote != null && !suffixNote.isBlank()) {
            rendered += suffixNote;
        }
        return rendered;
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

        long totalMythic = allDefs.stream().filter(d -> d.getTier() == PassiveTier.MYTHIC).count();
        long totalElite = allDefs.stream().filter(d -> d.getTier() == PassiveTier.ELITE).count();
        long totalCommon = allDefs.stream().filter(d -> d.getTier() == PassiveTier.COMMON).count();

        long mythicOwned = ownedIds.stream()
                .map(id -> augmentManager != null ? augmentManager.getAugment(id) : null)
                .filter(d -> d != null && d.getTier() == PassiveTier.MYTHIC)
                .count();
        long eliteOwned = ownedIds.stream()
                .map(id -> augmentManager != null ? augmentManager.getAugment(id) : null)
                .filter(d -> d != null && d.getTier() == PassiveTier.ELITE)
                .count();
        long commonOwned = ownedIds.stream()
                .map(id -> augmentManager != null ? augmentManager.getAugment(id) : null)
                .filter(d -> d != null && d.getTier() == PassiveTier.COMMON)
                .count();

        ui.set("#AugmentStatTotal.Text", "Total: " + ownedIds.size() + " / " + allDefs.size());
        ui.set("#AugmentStatMythic.Text", "Mythic: " + mythicOwned + " / " + totalMythic);
        ui.set("#AugmentStatElite.Text", "Elite: " + eliteOwned + " / " + totalElite);
        ui.set("#AugmentStatCommon.Text", "Common: " + commonOwned + " / " + totalCommon);

        Map<String, Integer> rerolls = playerData != null ? playerData.getAugmentRerollsUsedSnapshot() : Map.of();
        ui.set("#AugmentRerollMythic.Text", "Mythic: " + rerolls.getOrDefault("MYTHIC", 0));
        ui.set("#AugmentRerollElite.Text", "Elite: " + rerolls.getOrDefault("ELITE", 0));
        ui.set("#AugmentRerollCommon.Text", "Common: " + rerolls.getOrDefault("COMMON", 0));
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

            ui.append(cardsSelector + "[" + rowIndex + "]", "Augments/AugmentGridEntry.ui");
            String base = cardsSelector + "[" + rowIndex + "][" + cardsInCurrentRow + "]";

            ui.set(base + " #ItemIcon.ItemId", resolveIconItemId(def));
            ui.set(base + " #ItemName.Text", def.getName());

            boolean owned = ownedIds.contains(def.getId());
            ui.set(base + " #ItemName.Style.TextColor", owned ? tierColor(def.getTier()) : COLOR_UNOWNED);

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
