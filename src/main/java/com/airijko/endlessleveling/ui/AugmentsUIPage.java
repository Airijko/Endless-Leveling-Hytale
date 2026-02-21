package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Augments page that displays three random augment definitions.
 */
public class AugmentsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final int CARD_COUNT = 3;

    private static final Map<String, String> BUFF_NAME_OVERRIDES = createBuffNameOverrides();

    private final AugmentManager augmentManager;
    private final AugmentUnlockManager augmentUnlockManager;
    private final PlayerDataManager playerDataManager;
    private final PlayerRef playerRef;

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
        ui.append("Augments/AugmentsCards.ui");

        List<AugmentDefinition> augments = pickPlayerAugments();
        for (int i = 0; i < CARD_COUNT; i++) {
            AugmentDefinition augment = i < augments.size() ? augments.get(i) : null;
            applyCard(ui, i + 1, augment);
        }

        events.addEventBinding(Activating, "#AugmentCard1Choose", of("Action", "augment:choose:0"), false);
        events.addEventBinding(Activating, "#AugmentCard2Choose", of("Action", "augment:choose:1"), false);
        events.addEventBinding(Activating, "#AugmentCard3Choose", of("Action", "augment:choose:2"), false);
    }

    private List<AugmentDefinition> pickPlayerAugments() {
        if (augmentManager == null) {
            return List.of();
        }

        PlayerData playerData = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;
        if (playerData != null && augmentUnlockManager != null) {
            augmentUnlockManager.ensureUnlocks(playerData);
        }

        List<String> offerIds = new ArrayList<>();
        if (playerData != null) {
            Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
            // Prioritize higher tiers first (MYTHIC -> ELITE -> COMMON)
            PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON };
            for (PassiveTier tier : priority) {
                List<String> tierOffers = offers.getOrDefault(tier.name(), List.of());
                offerIds.addAll(tierOffers);
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
            playerRef.sendMessage(Message.raw("Augment data unavailable.").color("#ff6666"));
            return;
        }

        // Make sure offers exist
        augmentUnlockManager.ensureUnlocks(playerData);

        List<AugmentChoice> choices = collectChoices(playerData);
        if (index < 0 || index >= choices.size()) {
            playerRef.sendMessage(Message.raw("No augment in that slot.").color("#ff9900"));
            return;
        }

        AugmentChoice choice = choices.get(index);
        AugmentDefinition def = augmentManager.getAugment(choice.id);
        PassiveTier tier = def != null ? def.getTier() : choice.tier;
        if (tier == null) {
            playerRef.sendMessage(Message.raw("Unable to resolve augment tier.").color("#ff6666"));
            return;
        }

        String tierKey = tier.name();
        playerData.setSelectedAugmentForTier(tierKey, choice.id);
        playerData.setAugmentOffersForTier(tierKey, List.of());
        playerDataManager.save(playerData);

        playerRef.sendMessage(Message.raw("Selected augment: " + choice.id + " (" + tierKey + ")").color("#4fd7f7"));
    }

    private List<AugmentChoice> collectChoices(PlayerData playerData) {
        List<AugmentChoice> choices = new ArrayList<>();
        Map<String, List<String>> offers = playerData.getAugmentOffersSnapshot();
        PassiveTier[] priority = { PassiveTier.MYTHIC, PassiveTier.ELITE, PassiveTier.COMMON };
        for (PassiveTier tier : priority) {
            List<String> tierOffers = offers.getOrDefault(tier.name(), List.of());
            for (String id : tierOffers) {
                choices.add(new AugmentChoice(id, tier));
            }
        }
        return choices;
    }

    private record AugmentChoice(String id, PassiveTier tier) {
    }

    private void applyCard(@Nonnull UICommandBuilder ui, int slotIndex, AugmentDefinition augment) {
        String titleSelector = "#AugmentCard" + slotIndex + "Title";
        String descriptionSelector = "#AugmentCard" + slotIndex + "Description";
        String iconSelector = "#AugmentCard" + slotIndex + "Icon";
        String cooldownSelector = "#AugmentCard" + slotIndex + "Cooldown";
        String durationSelector = "#AugmentCard" + slotIndex + "Duration";
        String buffsSelector = "#AugmentCard" + slotIndex + "Buffs";
        String debuffsSelector = "#AugmentCard" + slotIndex + "Debuffs";

        // Temporary placeholder icon until augments supply their own.
        ui.set(iconSelector + ".ItemId", "Ingredient_Ice_Essence");
        ui.set(iconSelector + ".Visible", true);

        if (augment == null) {
            ui.set(titleSelector + ".Text", "NO AUGMENT");
            ui.set(descriptionSelector + ".Text", "Add augments to /mods/EndlessLeveling/augments to see them here.");
            ui.set(cooldownSelector + ".Visible", false);
            ui.set(durationSelector + ".Visible", false);
            ui.set(buffsSelector + ".Visible", false);
            ui.set(debuffsSelector + ".Visible", false);
            return;
        }

        ui.set(titleSelector + ".Text", augment.getName().toUpperCase(Locale.ROOT));

        String description = augment.getDescription();
        if (description == null || description.isBlank()) {
            description = "No description provided.";
        }
        ui.set(descriptionSelector + ".Text", description);

        Map<String, Object> passives = augment.getPassives();

        String cooldownText = formatCooldown(passives);
        if (cooldownText == null) {
            ui.set(cooldownSelector + ".Visible", false);
        } else {
            ui.set(cooldownSelector + ".Text", cooldownText);
            ui.set(cooldownSelector + ".Visible", true);
        }

        String durationText = formatDuration(passives);
        if (durationText == null) {
            ui.set(durationSelector + ".Visible", false);
        } else {
            ui.set(durationSelector + ".Text", durationText);
            ui.set(durationSelector + ".Visible", true);
        }

        String buffsText = formatBuffs(passives);
        if (buffsText == null || buffsText.isBlank()) {
            ui.set(buffsSelector + ".Visible", false);
        } else {
            ui.set(buffsSelector + ".Text", buffsText);
            ui.set(buffsSelector + ".Visible", true);
        }

        String debuffsText = formatDebuffs(passives);
        if (debuffsText == null || debuffsText.isBlank()) {
            ui.set(debuffsSelector + ".Visible", false);
        } else {
            ui.set(debuffsSelector + ".Text", debuffsText);
            ui.set(debuffsSelector + ".Visible", true);
        }
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
        map.put("movement_speed_bonus", "Move Speed");
        map.put("movement_speed", "Move Speed");
        map.put("resistance_bonus", "Resistance");
        map.put("wither", "Wither");
        map.put("slow_percent", "Slow");
        map.put("mana", "Mana");
        map.put("mana_from_sorcery", "Mana");
        map.put("sorcery_from_mana", "Sorcery");
        map.put("max_distance", "Max distance (full bonus)");
        return map;
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

    private String formatEffects(Map<String, Object> passives, boolean positives) {
        if (passives == null || passives.isEmpty()) {
            return null;
        }

        // Directly handle top-level buffs/debuffs maps if present.
        String directKey = positives ? "buffs" : "debuffs";
        Map<String, Object> direct = asMap(passives.get(directKey));
        if (direct != null && !direct.isEmpty()) {
            String rendered = renderBuffMap(direct, positives);
            if (!rendered.isBlank()) {
                return rendered;
            }
        }

        // Priority: explicit buffs/debuffs map on any passive (use passive name as
        // fallback label).
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
                    return rendered;
                }
            }
        }

        // Fallback: collect numeric fields that look like effects, using passive name
        // as label when needed.
        List<String> parts = new ArrayList<>();
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
                collectEffect(parts, key, val, positives, passiveName);
            }
        }

        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n", parts);
    }

    private String renderBuffMap(Map<String, Object> buffs, boolean positives) {
        return renderBuffMap(buffs, positives, null);
    }

    private String renderBuffMap(Map<String, Object> buffs, boolean positives, String fallbackLabel) {
        List<String> parts = new ArrayList<>();
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
            collectEffect(parts, key, val, positives, fallbackLabel == null ? key : fallbackLabel);
        }

        if (maxStacks != null) {
            parts.add("Max stacks: " + maxStacks);
        }
        return String.join("\n", parts);
    }

    private void collectEffect(List<String> parts, String key, Object val, boolean positives, String fallbackLabel) {
        if (isTimingKey(key)) {
            return; // timing values render in the footer, not the buff list
        }

        Double scalar = toDouble(val);
        if (scalar != null) {
            if ((positives && scalar > 0) || (!positives && scalar < 0)) {
                parts.add(formatBuffEntry(key, scalar, null, fallbackLabel));
            }
            return;
        }

        Map<String, Object> nested = asMap(val);
        if (nested == null) {
            return;
        }

        Double chosen = firstNumber(nested, "value", "max_value", "value_per_stack", key);
        String forcedSuffix = null;
        if (chosen == null) {
            return;
        }

        if ((positives && chosen > 0) || (!positives && chosen < 0)) {
            parts.add(formatBuffEntry(key, chosen, forcedSuffix, fallbackLabel));
        }
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

    private String formatBuffEntry(String key, double value, String forcedSuffix, String fallbackLabel) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String label = BUFF_NAME_OVERRIDES.getOrDefault(normalizedKey, key == null ? "" : key.replace('_', ' '));

        // If the key is generic, prefer the parent/fallback label.
        if ((normalizedKey.isBlank() || normalizedKey.equals("value")
                || normalizedKey.equals("max_value")
                || normalizedKey.equals("value_per_stack")) && fallbackLabel != null && !fallbackLabel.isBlank()) {
            label = fallbackLabel.replace('_', ' ');
        }

        String suffix = forcedSuffix;
        double displayValue = value;

        if (suffix == null) {
            if (normalizedKey.contains("percent") || normalizedKey.contains("max_value") || Math.abs(value) <= 1.0) {
                suffix = "%";
                displayValue = value * 100.0;
            } else if (normalizedKey.contains("ratio")) {
                suffix = "x";
                displayValue = Math.abs(value);
            } else {
                suffix = "";
            }
        } else if ("%".equals(suffix)) {
            displayValue = value * 100.0; // ensure forced percent shows human-friendly scale
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
        return capitalize(label) + ": " + sign + formatNumber(displayValue) + suffix;
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
}