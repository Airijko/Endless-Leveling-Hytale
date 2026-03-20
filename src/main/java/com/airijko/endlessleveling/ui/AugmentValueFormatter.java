package com.airijko.endlessleveling.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared augment value formatter used by UI pages so display behavior and
 * translations are centralized.
 */
public final class AugmentValueFormatter {

    @FunctionalInterface
    public interface Translator {
        String tr(String key, String fallback, Object... args);
    }

    private static final Map<String, String> BUFF_NAME_OVERRIDES = createBuffNameOverrides();

    // These stats are stored as direct percentage points in config (1.0 -> 1%).
    private static final Set<String> DIRECT_PERCENT_KEYS = Set.of(
            "strength",
            "sorcery",
            "haste",
            "defense",
            "precision",
            "ferocity");

    private final Translator translator;

    public AugmentValueFormatter(Translator translator) {
        this.translator = translator != null ? translator : (key, fallback, args) -> fallback;
    }

    public String formatSingleValueLine(String key, double value, String fallbackLabel) {
        return formatBuffEntry(key, value, null, fallbackLabel, null);
    }

    public String formatCooldown(Map<String, Object> passives) {
        Double value = findNumericField(passives, "trigger_cooldown", "cooldown", "proc_cooldown");
        if (value == null) {
            return null;
        }
        return tr("ui.augments.effect.cooldown", "Cooldown: {0}", formatSeconds(value));
    }

    public String formatDuration(Map<String, Object> passives) {
        String conquerorDuration = formatConquerorDuration(passives);
        if (conquerorDuration != null) {
            return conquerorDuration;
        }

        Double perStack = findNumericField(passives, "duration_per_stack");
        if (perStack != null) {
            return tr("ui.augments.effect.duration_per_stack", "Duration per stack: {0}", formatSeconds(perStack));
        }

        Double value = findNumericField(passives, "duration", "effect_duration");
        if (value == null) {
            return null;
        }
        return tr("ui.augments.effect.duration", "Duration: {0}", formatSeconds(value));
    }

    public String formatBuffs(Map<String, Object> passives) {
        return formatEffects(passives, true);
    }

    public String formatDebuffs(Map<String, Object> passives) {
        return formatEffects(passives, false);
    }

    private String formatEffects(Map<String, Object> passives, boolean positives) {
        if (passives == null || passives.isEmpty()) {
            return null;
        }

        String bruteForceFormatted = formatBruteForceEffects(passives, positives);
        if (bruteForceFormatted != null) {
            return bruteForceFormatted;
        }

        String conquerorFormatted = formatConquerorEffects(passives, positives);
        if (conquerorFormatted != null) {
            return conquerorFormatted;
        }

        String healthStateFormatted = formatHealthStateSections(passives, positives);
        if (healthStateFormatted != null) {
            return healthStateFormatted;
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

    private String formatBruteForceEffects(Map<String, Object> passives, boolean positives) {
        Map<String, Object> bruteForce = asMap(passives.get("brute_force"));
        if (bruteForce == null || bruteForce.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        if (positives) {
            Double strengthMultiplier = toDouble(bruteForce.get("strength_multiplier"));
            if (strengthMultiplier != null && strengthMultiplier > 0.0D) {
                lines.add(formatBuffEntry("strength_multiplier",
                        strengthMultiplier,
                        null,
                        "strength_multiplier",
                        null));
            }

            Double sorceryMultiplier = toDouble(bruteForce.get("sorcery_multiplier"));
            if (sorceryMultiplier != null && sorceryMultiplier > 0.0D) {
                lines.add(formatBuffEntry("sorcery_multiplier",
                        sorceryMultiplier,
                        null,
                        "sorcery_multiplier",
                        null));
            }
        } else {
            Double precisionLockValue = toDouble(bruteForce.get("precision_lock_value"));
            if (precisionLockValue != null) {
                lines.add(formatBuffEntry("precision_lock_value",
                        0.0D,
                        null,
                        "precision_lock_value",
                        null));
            }
        }

        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private String formatConquerorEffects(Map<String, Object> passives, boolean positives) {
        if (!positives || !isConquerorStructured(passives)) {
            return null;
        }

        Map<String, Object> buffs = asMap(passives.get("buffs"));
        if (buffs == null || buffs.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();

        Map<String, Object> bonusDamage = asMap(buffs.get("bonus_damage"));
        Double bonusDamagePerStack = bonusDamage == null ? null : toDouble(bonusDamage.get("value"));
        if (bonusDamagePerStack != null && bonusDamagePerStack > 0.0D) {
            lines.add(formatBuffEntry("bonus_damage_per_stack",
                    bonusDamagePerStack,
                    null,
                    "bonus_damage_per_stack",
                    null));
        }

        Integer maxStacks = toInteger(buffs.get("max_stacks"));
        if (maxStacks != null && maxStacks > 0) {
            lines.add(tr("ui.augments.effect.max_stacks", "Max stacks: {0}", maxStacks));
        }

        Map<String, Object> maxStackBonus = asMap(passives.get("max_stack_bonus"));
        Map<String, Object> bonusTrueDamage = maxStackBonus == null ? null
                : asMap(maxStackBonus.get("bonus_true_damage"));
        if (bonusTrueDamage != null && !bonusTrueDamage.isEmpty()) {
            String trueDamageLine = tryFormatFlatRatioComposite(
                    "bonus_true_damage",
                    bonusTrueDamage,
                    true,
                    tr("ui.augments.effect.note.at_max_stacks", " (at max stacks)"));
            if (trueDamageLine != null && !trueDamageLine.isBlank()) {
                lines.add(trueDamageLine);
            }
        }

        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private String formatConquerorDuration(Map<String, Object> passives) {
        if (!isConquerorStructured(passives)) {
            return null;
        }

        Map<String, Object> duration = asMap(passives.get("duration"));
        Double seconds = duration == null ? null : toDouble(duration.get("seconds"));
        if (seconds == null || seconds <= 0.0D) {
            return null;
        }

        return tr("ui.augments.effect.duration_conqueror",
                "Duration: {0} (refreshes cooldown when stacking)",
                formatSeconds(seconds));
    }

    private boolean isConquerorStructured(Map<String, Object> passives) {
        if (passives == null || passives.isEmpty()) {
            return false;
        }
        Map<String, Object> buffs = asMap(passives.get("buffs"));
        Map<String, Object> duration = asMap(passives.get("duration"));
        Map<String, Object> maxStackBonus = asMap(passives.get("max_stack_bonus"));
        Map<String, Object> bonusTrueDamage = maxStackBonus == null ? null
                : asMap(maxStackBonus.get("bonus_true_damage"));

        boolean hasStacks = buffs != null && buffs.containsKey("max_stacks")
                && asMap(buffs.get("bonus_damage")) != null;
        boolean hasDurationSeconds = duration != null && duration.containsKey("seconds");
        boolean hasTrueDamageBundle = bonusTrueDamage != null;
        return hasStacks && hasDurationSeconds && hasTrueDamageBundle;
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
            parts.add(tr("ui.augments.effect.max_stacks", "Max stacks: {0}", maxStacks));
        }
        return String.join("\n", parts);
    }

    private String formatHealthStateSections(Map<String, Object> passives, boolean positives) {
        Map<String, Object> healthyState = asMap(passives.get("healthy_state"));
        Map<String, Object> woundedState = asMap(passives.get("wounded_state"));
        boolean hasHealthSection = (healthyState != null && !healthyState.isEmpty())
                || (woundedState != null && !woundedState.isEmpty());
        if (!hasHealthSection) {
            return null;
        }

        boolean hasThresholdMarkers = (healthyState != null && healthyState.containsKey("health_threshold_above"))
                || (woundedState != null && woundedState.containsKey("health_threshold_below"));
        if (!hasThresholdMarkers) {
            return null;
        }

        if (!positives) {
            List<String> healthyDebuffs = collectHealthStateDetails(healthyState, true, false);
            return healthyDebuffs.isEmpty() ? null : String.join("\n", healthyDebuffs);
        }

        List<String> healthyBuffs = collectHealthStateDetails(healthyState, true, true);
        List<String> woundedBuffs = collectHealthStateDetails(woundedState, false, true);
        if (healthyBuffs.isEmpty() && woundedBuffs.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        if (!healthyBuffs.isEmpty()) {
            lines.add(resolveHealthStateHeading(healthyState, true));
            lines.addAll(healthyBuffs);
        }
        if (!woundedBuffs.isEmpty()) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(resolveHealthStateHeading(woundedState, false));
            lines.addAll(woundedBuffs);
        }

        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private List<String> collectHealthStateDetails(Map<String, Object> section,
            boolean healthyState,
            boolean positives) {
        if (section == null || section.isEmpty()) {
            return List.of();
        }

        Set<String> sectionLines = new LinkedHashSet<>();
        if (healthyState) {
            if (positives) {
                Object bonusDamage = section.get("bonus_damage");
                if (bonusDamage != null) {
                    collectEffect(sectionLines, "bonus_damage", bonusDamage, true, "bonus_damage", section);
                }
            } else {
                Map<String, Object> selfDamage = asMap(section.get("self_damage"));
                Double currentHpPercentCost = selfDamage == null ? null
                        : toDouble(selfDamage.get("percent_of_current_hp"));
                if (currentHpPercentCost != null && currentHpPercentCost > 0.0D) {
                    sectionLines.add(formatBuffEntry("self_damage",
                            -Math.abs(currentHpPercentCost),
                            null,
                            "self_damage",
                            null));
                }
            }
        } else {
            if (positives) {
                Object healing = section.get("healing");
                if (healing != null) {
                    Map<String, Object> healingMap = asMap(healing);
                    if (healingMap != null) {
                        Double missingHealthPercent = toDouble(healingMap.get("missing_health_percent"));
                        if (missingHealthPercent != null && missingHealthPercent > 0.0D) {
                            sectionLines.add(formatBuffEntry("missing_health_percent",
                                    missingHealthPercent,
                                    null,
                                    "missing_health_percent",
                                    null));
                        }

                        Double strengthScaling = toDouble(healingMap.get("strength_scaling"));
                        if (strengthScaling != null && strengthScaling > 0.0D) {
                            sectionLines.add(formatBuffEntry("healing_strength_scaling",
                                    strengthScaling,
                                    null,
                                    "healing_strength_scaling",
                                    null));
                        }

                        Double sorceryScaling = toDouble(healingMap.get("sorcery_scaling"));
                        if (sorceryScaling != null && sorceryScaling > 0.0D) {
                            sectionLines.add(formatBuffEntry("healing_sorcery_scaling",
                                    sorceryScaling,
                                    null,
                                    "healing_sorcery_scaling",
                                    null));
                        }

                        for (Map.Entry<String, Object> extra : healingMap.entrySet()) {
                            String extraKey = extra.getKey();
                            if (extraKey == null) {
                                continue;
                            }
                            String lower = extraKey.toLowerCase(Locale.ROOT);
                            if (lower.equals("missing_health_percent")
                                    || lower.equals("strength_scaling")
                                    || lower.equals("sorcery_scaling")) {
                                continue;
                            }
                            if (isTimingKey(lower) || isMetadataOnlyKey(lower)) {
                                continue;
                            }
                            collectEffect(sectionLines, extraKey, extra.getValue(), true, extraKey, healingMap);
                        }
                    } else {
                        collectEffect(sectionLines, "healing", healing, true, "healing", section);
                    }
                }
            }
        }

        return new ArrayList<>(sectionLines);
    }

    private String resolveHealthStateHeading(Map<String, Object> section, boolean healthyState) {
        String thresholdKey = healthyState ? "health_threshold_above" : "health_threshold_below";
        Double thresholdValue = toDouble(section.get(thresholdKey));
        if (thresholdValue != null) {
            double thresholdPercent = Math.abs(thresholdValue) <= 1.0D ? thresholdValue * 100.0D : thresholdValue;
            if (healthyState) {
                return tr("ui.augments.effect.section.above_health", "Above {0}% Health:",
                        formatNumber(thresholdPercent));
            }
            return tr("ui.augments.effect.section.below_health", "Below {0}% Health:", formatNumber(thresholdPercent));
        }
        if (healthyState) {
            return tr("ui.augments.effect.section.above_health.generic", "Above Health Threshold:");
        }
        return tr("ui.augments.effect.section.below_health.generic", "Below Health Threshold:");
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
        String effectSuffix = suppressThresholdSuffixForEffect(normalizedKey) ? null : thresholdSuffix;

        Double scalar = toDouble(val);
        if (scalar != null) {
            if (normalizedKey.equals("min_health_hp") && "heal_on_trigger".equalsIgnoreCase(fallbackLabel)) {
                return;
            }
            if (!positives && normalizedKey.equals("precision_lock_value") && scalar > 0.0D) {
                parts.add(formatBuffEntry(key, -Math.abs(scalar), null, fallbackLabel, effectSuffix));
                return;
            }
            if ((positives && scalar > 0) || (!positives && scalar < 0)) {
                parts.add(formatBuffEntry(key, scalar, null, fallbackLabel, effectSuffix));
            }
            return;
        }

        Map<String, Object> nested = asMap(val);
        if (nested == null) {
            return;
        }

        String nestedThresholdSuffix = resolveThresholdSuffix(nested);
        if (nestedThresholdSuffix == null || nestedThresholdSuffix.isBlank()) {
            nestedThresholdSuffix = effectSuffix;
        }
        if (suppressThresholdSuffixForEffect(normalizedKey)) {
            nestedThresholdSuffix = null;
        }

        String compositeLine = tryFormatFlatRatioComposite(key, nested, positives, nestedThresholdSuffix);
        if (compositeLine != null && !compositeLine.isBlank()) {
            parts.add(compositeLine);
            return;
        }

        Double minValue = toDouble(nested.get("min_value"));
        Double maxValue = toDouble(nested.get("max_value"));
        boolean renderedPrimary = false;
        if (minValue != null && maxValue != null) {
            if ((positives && maxValue > 0) || (!positives && minValue < 0)) {
                parts.add(formatRangeEntry(key, minValue, maxValue, fallbackLabel, nestedThresholdSuffix));
            }
            renderedPrimary = true;
        }

        if (!renderedPrimary) {
            Double baseValue = toDouble(nested.get("value"));
            if (maxValue != null && (baseValue == null || Math.abs(baseValue) < 0.0001D)) {
                if ((positives && maxValue > 0) || (!positives && maxValue < 0)) {
                    parts.add(formatRangeEntry(key, 0.0D, maxValue, fallbackLabel, nestedThresholdSuffix));
                }
                renderedPrimary = true;
            }
        }

        if (!renderedPrimary) {
            Double chosen = firstNumber(nested, "value", "conversion_percent", "max_value", "value_per_stack", key);
            if (chosen != null) {
                if ((positives && chosen > 0) || (!positives && chosen < 0)) {
                    parts.add(formatBuffEntry(key, chosen, null, fallbackLabel, nestedThresholdSuffix));
                }
                renderedPrimary = true;
            }
        }

        for (Map.Entry<String, Object> nestedEntry : nested.entrySet()) {
            String nestedKey = nestedEntry.getKey();
            if (nestedKey == null) {
                continue;
            }
            String nestedLower = nestedKey.toLowerCase(Locale.ROOT);
            if (nestedLower.equals("min_value")
                    || nestedLower.equals("max_value")
                    || nestedLower.equals("value")
                    || nestedLower.equals("conversion_percent")
                    || nestedLower.equals("value_per_stack")
                    || nestedLower.equals("max_stacks")) {
                continue;
            }
            if (isTimingKey(nestedLower) || isMetadataOnlyKey(nestedLower)) {
                continue;
            }
            collectEffect(parts, nestedKey, nestedEntry.getValue(), positives, nestedKey, nested);
        }
    }

    private String resolveThresholdSuffix(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }

        String scalingStat = asString(context.get("scaling_stat"));
        boolean missingHealthScaling = scalingStat != null
                && scalingStat.toLowerCase(Locale.ROOT).contains("missing_health");
        if (!missingHealthScaling) {
            boolean hasFullAtHealthThreshold = context.containsKey("full_value_at_health_percent");
            boolean hasLifeStealStyleRange = context.containsKey("max_value")
                    && (context.containsKey("healing_to_damage") || context.containsKey("max_missing_health_percent"));
            missingHealthScaling = hasFullAtHealthThreshold && hasLifeStealStyleRange;
        }

        Double fullValueAtHealth = toDouble(context.get("full_value_at_health_percent"));
        if (fullValueAtHealth != null) {
            String maxValueAtHealth = tr("ui.augments.effect.note.max_value_at_health",
                    " (max value at {0}% health)",
                    formatNumber(fullValueAtHealth * 100.0D));
            if (missingHealthScaling) {
                return maxValueAtHealth
                        + "\n"
                        + tr("ui.augments.effect.note.missing_health_line", "Scales with missing health")
                        + "\n\n";
            }
            return maxValueAtHealth;
        }

        if (missingHealthScaling) {
            return tr("ui.augments.effect.note.missing_health", " (scales with missing health)");
        }

        Map<String, Object> condition = asMap(context.get("condition"));
        if (condition != null && !condition.isEmpty()) {
            String resourceName = prettifyResourceName(asString(condition.get("resource")));
            Double minPercent = normalizePercentThresholdForDisplay(toDouble(condition.get("min_percent")));
            if (minPercent != null) {
                return tr("ui.augments.effect.note.condition.above",
                        " (above {0}% {1})",
                        formatNumber(minPercent),
                        resourceName);
            }
            Double maxPercent = normalizePercentThresholdForDisplay(toDouble(condition.get("max_percent")));
            if (maxPercent != null) {
                return tr("ui.augments.effect.note.condition.below",
                        " (below {0}% {1})",
                        formatNumber(maxPercent),
                        resourceName);
            }
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

    private Double normalizePercentThresholdForDisplay(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.abs(value) <= 1.0D ? value * 100.0D : value;
    }

    private String prettifyResourceName(String resource) {
        if (resource == null || resource.isBlank()) {
            return tr("ui.augments.effect.resource.generic", "Resource");
        }
        String normalized = resource.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (normalized.isBlank()) {
            return tr("ui.augments.effect.resource.generic", "Resource");
        }
        return capitalize(normalized);
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
        double displayMin = "%".equals(unit) ? toDisplayPercent(semanticKeyForUnit, normalizedMin) : normalizedMin;
        double displayMax = "%".equals(unit) ? toDisplayPercent(semanticKeyForUnit, normalizedMax) : normalizedMax;

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
        String number = formatNumber(Math.abs(value));
        String sign = value < 0 ? "-" : "";
        return sign + number + (suffix == null ? "" : suffix);
    }

    private String tryFormatFlatRatioComposite(String key,
            Map<String, Object> nested,
            boolean positives,
            String suffixNote) {
        if (!positives || key == null || nested == null || nested.isEmpty()) {
            return null;
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (normalizedKey.equals("bonus_true_damage")) {
            Double flatValue = toDouble(nested.get("value"));
            Double ratioValue = toDouble(nested.get("true_damage_percent"));
            if ((flatValue == null || flatValue <= 0.0D) && (ratioValue == null || ratioValue <= 0.0D)) {
                return null;
            }

            List<String> segments = new ArrayList<>();
            if (flatValue != null && flatValue > 0.0D) {
                segments.add("+" + formatNumber(flatValue) + " flat");
            }
            if (ratioValue != null && ratioValue > 0.0D) {
                double displayPercent = toDisplayPercent("true_damage_percent", ratioValue);
                segments.add("+" + formatNumber(displayPercent) + "% damage dealt");
            }

            String label = translateEffectLabel("bonus_true_damage",
                    BUFF_NAME_OVERRIDES.getOrDefault("bonus_true_damage", "Bonus true damage"));
            String rendered = capitalize(label) + ": " + String.join(" ", segments);
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if (normalizedKey.equals("phantom_damage") || nested.containsKey("flat_damage")) {
            Double flatDamage = toDouble(nested.get("flat_damage"));
            Double strengthScaling = toDouble(nested.get("strength_scaling"));
            Double sorceryScaling = toDouble(nested.get("sorcery_scaling"));

            boolean hasFlat = flatDamage != null && flatDamage > 0.0D;
            boolean hasScaling = (strengthScaling != null && strengthScaling > 0.0D)
                    || (sorceryScaling != null && sorceryScaling > 0.0D);
            if (!hasFlat || !hasScaling) {
                return null;
            }

            List<String> segments = new ArrayList<>();
            segments.add("+" + formatNumber(flatDamage) + " flat");
            if (strengthScaling != null && strengthScaling > 0.0D) {
                double displayPercent = toDisplayPercent("strength_scaling", strengthScaling);
                segments.add("+" + formatNumber(displayPercent) + "% Strength");
            }
            if (sorceryScaling != null && sorceryScaling > 0.0D) {
                double displayPercent = toDisplayPercent("sorcery_scaling", sorceryScaling);
                segments.add("+" + formatNumber(displayPercent) + "% Sorcery");
            }

            String label = translateEffectLabel(normalizedKey,
                    BUFF_NAME_OVERRIDES.getOrDefault(normalizedKey, key.replace('_', ' ')));
            String rendered = capitalize(label) + ": " + String.join(" ", segments);
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        return null;
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

        String fallbackName = BUFF_NAME_OVERRIDES.getOrDefault(canonicalKey,
                key == null ? "" : key.replace('_', ' '));
        String label = translateEffectLabel(canonicalKey, fallbackName);
        String semanticKeyForUnit = canonicalKey;

        if ((canonicalKey.isBlank()
                || canonicalKey.equals("value")
                || canonicalKey.equals("value_per_stack")
                || canonicalKey.equals("conversion_percent"))
                && fallbackLabel != null
                && !fallbackLabel.isBlank()) {
            String normalizedFallbackKey = fallbackLabel.toLowerCase(Locale.ROOT).replace(' ', '_');
            label = translateEffectLabel(normalizedFallbackKey, fallbackLabel.replace('_', ' '));
            semanticKeyForUnit = normalizedFallbackKey;
        }

        if ("mana_from_sorcery".equals(canonicalKey)) {
            double asPercent = toDisplayPercent(canonicalKey, value);
            String rendered = tr("ui.augments.effect.rule.mana_from_sorcery",
                    "Mana: +{0}% of Sorcery",
                    formatNumber(asPercent));
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if ("sorcery_from_mana".equals(canonicalKey)) {
            double asPercent = toDisplayPercent(canonicalKey, value);
            String rendered = tr("ui.augments.effect.rule.sorcery_from_mana",
                    "Sorcery: +{0}% of Mana",
                    formatNumber(asPercent));
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if ("wither".equals(canonicalKey)) {
            double asPercent = toDisplayPercent(canonicalKey, value);
            String rendered = tr("ui.augments.effect.rule.wither_per_second",
                    "Wither: +{0}% max health damage per second",
                    formatNumber(asPercent));
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if ("immunity_window".equals(canonicalKey)) {
            String rendered = tr("ui.augments.effect.rule.immunity_window",
                    "Immunity Window: {0}",
                    formatSeconds(Math.max(0.0D, value)));
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if (canonicalKey.endsWith("_multiplier")) {
            String rendered = capitalize(label) + ": " + formatNumber(value) + "x";
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        if ("min_health_hp".equals(canonicalKey)) {
            String rendered = tr("ui.augments.effect.rule.min_health_hp",
                    "Under Rage: You can't drop below {0} health",
                    formatNumber(value));
            if (suffixNote != null && !suffixNote.isBlank()) {
                rendered += suffixNote;
            }
            return rendered;
        }

        String suffix = forcedSuffix;
        double displayValue = value;

        if (suffix == null) {
            suffix = inferSuffix(semanticKeyForUnit, value);
            if ("%".equals(suffix)) {
                displayValue = toDisplayPercent(semanticKeyForUnit, value);
            } else if (normalizedKey.contains("ratio")) {
                suffix = "x";
                displayValue = Math.abs(value);
            }
        } else if ("%".equals(suffix)) {
            displayValue = toDisplayPercent(semanticKeyForUnit, value);
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

        String rendered = capitalize(label) + ": " + sign + formatNumber(displayValue) + suffix;
        if (suffixNote != null && !suffixNote.isBlank()) {
            rendered += suffixNote;
        }
        return rendered;
    }

    private double toDisplayPercent(String normalizedKey, double value) {
        if (normalizedKey != null && DIRECT_PERCENT_KEYS.contains(normalizedKey)) {
            return value;
        }
        return Math.abs(value) >= 10.0D ? value : value * 100.0D;
    }

    private String inferSuffix(String normalizedKey, double value) {
        if (normalizedKey == null) {
            return "";
        }
        if (normalizedKey.equals("precision_lock_value") || normalizedKey.equals("immunity_window")) {
            return "";
        }
        if (isFlatHealthKey(normalizedKey) || isFlatAmountKey(normalizedKey)) {
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
                || isHealingKey(normalizedKey)
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

    private boolean isFlatAmountKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }
        return normalizedKey.startsWith("flat_")
                || normalizedKey.endsWith("_flat")
                || normalizedKey.contains("_flat_");
    }

    private boolean isFlatHealthKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }
        return normalizedKey.endsWith("_hp")
                || normalizedKey.equals("flat_health")
                || normalizedKey.equals("flat_health_per_stack")
                || normalizedKey.equals("health_flat");
    }

    private boolean suppressThresholdSuffixForEffect(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }
        return normalizedKey.equals("bonus_ferocity")
                || normalizedKey.equals("healing_to_damage")
                || normalizedKey.equals("heal_to_damage");
    }

    private boolean isHealingKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) {
            return false;
        }
        return normalizedKey.equals("heal")
                || normalizedKey.startsWith("heal_")
                || normalizedKey.endsWith("_heal")
                || normalizedKey.contains("_heal_")
                || normalizedKey.contains("healing");
    }

    private String translateEffectLabel(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        return tr("ui.augments.effect.label." + key, fallback);
    }

    private boolean isTimingKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("duration") || lower.contains("cooldown") || lower.equals("seconds");
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
                || lower.equals("max_stacks")
                || lower.equals("trigger_cooldown")
                || lower.equals("cooldown_per_target")
                || lower.equals("target_debuff")
                || lower.equals("break_conditions")
                || lower.equals("on_miss")
                || lower.equals("on_damage_taken");
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

    private Double toDouble(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String asString(Object val) {
        if (val == null) {
            return null;
        }
        String str = val.toString();
        return str.isBlank() ? null : str;
    }

    private Integer toInteger(Object val) {
        if (val instanceof Number number) {
            return number.intValue();
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
            Map<String, Object> map = new LinkedHashMap<>();
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
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
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

    private String tr(String key, String fallback, Object... args) {
        return translator.tr(key, fallback, args);
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
        map.put("healing_to_damage", "Heal to Damage");
        map.put("bonus_damage_on_hit", "Bonus Damage");
        map.put("bonus_damage", "Bonus Damage");
        map.put("bonus_damage_per_stack", "Bonus Damage per stack");
        map.put("max_bonus_damage", "Bonus Damage");
        map.put("bonus_true_damage", "Bonus True Damage");
        map.put("phantom_damage", "Phantom Damage");
        map.put("bonus_ferocity", "Ferocity");
        map.put("max_bonus_ferocity", "Ferocity");
        map.put("strength_from_max_health", "Strength");
        map.put("sorcery_from_max_health", "Sorcery");
        map.put("sorcery_bonus_high", "Sorcery");
        map.put("sorcery_penalty_low", "Sorcery");
        map.put("precision_lock_value", "Precision");
        map.put("strength_multiplier", "Strength");
        map.put("sorcery_multiplier", "Sorcery");
        map.put("crit_defense", "Damage Reduction");
        map.put("taunt_radius", "Taunt Radius");
        map.put("bonus_damage_by_distance", "Bonus Damage");
        map.put("bonus_damage_vs_hp_ratio", "Bonus Damage");
        map.put("execution_heal", "Execute Heal");
        map.put("self_damage", "Self Damage");
        map.put("percent_of_current_hp", "Self Damage");
        map.put("missing_health_percent", "Missing Health Heal");
        map.put("strength_scaling", "Strength Scaling");
        map.put("sorcery_scaling", "Sorcery Scaling");
        map.put("healing_strength_scaling", "Healing Ratio from Strength");
        map.put("healing_sorcery_scaling", "Healing Ratio from Sorcery");
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
        map.put("immunity_window", "Immunity Window");
        map.put("health_threshold", "Health Threshold");
        map.put("trigger_threshold", "Trigger Threshold");
        map.put("full_value_at_health_percent", "Full Value Threshold");
        map.put("max_distance", "Max distance (full bonus)");
        return map;
    }
}
