package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Handles configurable gameplay event hooks from events.yml.
 */
public class EventHookManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private static final String PRESTIGE_EVENT_PATH = "events.prestige_level_up";
    private static final String LEVEL_EVENT_PATH = "events.player_level_up";

    private record LevelCommandRule(List<String> commands,
            boolean repeatAccessPrestiges,
            Set<Integer> prestigeLevels,
            boolean hasPrestigeFilter) {
    }

    private record PrestigeLevelFilter(Set<Integer> levels, boolean explicit) {
    }

    private final ConfigManager eventsConfig;

    public EventHookManager(@Nonnull ConfigManager eventsConfig) {
        this.eventsConfig = Objects.requireNonNull(eventsConfig, "eventsConfig");
        reload();
    }

    public void reload() {
        eventsConfig.load();
    }

    public void onPrestigeLevelUp(PlayerData playerData, int oldPrestigeLevel, int newPrestigeLevel) {
        if (playerData == null || newPrestigeLevel <= oldPrestigeLevel) {
            return;
        }

        if (!getBoolean(PRESTIGE_EVENT_PATH + ".enabled", true)) {
            return;
        }

        boolean asPlayer = getBoolean(PRESTIGE_EVENT_PATH + ".as_player", false);
        PlayerRef playerRef = Universe.get().getPlayer(playerData.getUuid());
        Object rawCommands = getRaw(PRESTIGE_EVENT_PATH + ".commands", Collections.emptyList());

        // Catch up all crossed prestige levels in case multiple levels were gained at
        // once.
        for (int prestigeLevel = oldPrestigeLevel + 1; prestigeLevel <= newPrestigeLevel; prestigeLevel++) {
            List<String> configuredCommands = resolveCommandsForLevel(rawCommands, prestigeLevel);
            if (configuredCommands.isEmpty()) {
                continue;
            }

            executeConfiguredCommands(configuredCommands, asPlayer, playerRef, playerData,
                    prestigeLevel - 1, prestigeLevel, Collections.emptyMap(), "prestige");
        }
    }

    public void onPlayerLevelUp(PlayerData playerData, int oldLevel, int newLevel) {
        if (playerData == null || newLevel <= oldLevel) {
            return;
        }

        if (!getBoolean(LEVEL_EVENT_PATH + ".enabled", false)) {
            return;
        }

        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());
        boolean defaultRepeatAccessPrestiges = getBoolean(
                LEVEL_EVENT_PATH + ".repeat_access_prestiges",
                getBoolean(LEVEL_EVENT_PATH + ".recur_through_prestige", true));

        Object rawCommands = getRaw(LEVEL_EVENT_PATH + ".commands", Collections.emptyList());
        boolean mappedCommands = rawCommands instanceof Map<?, ?>;
        Set<Integer> triggerLevels = mappedCommands ? Set.of() : getIntegerSet(LEVEL_EVENT_PATH + ".trigger_levels");

        boolean asPlayer = getBoolean(LEVEL_EVENT_PATH + ".as_player", false);
        PlayerRef playerRef = Universe.get().getPlayer(playerData.getUuid());

        // Catch up all crossed levels in case XP gains skip through multiple levels.
        for (int level = oldLevel + 1; level <= newLevel; level++) {
            if (!mappedCommands && !triggerLevels.isEmpty() && !triggerLevels.contains(level)) {
                continue;
            }

            List<LevelCommandRule> rules = resolveLevelCommandRules(rawCommands, level, defaultRepeatAccessPrestiges);
            if (rules.isEmpty()) {
                continue;
            }

            for (LevelCommandRule rule : rules) {
                if (rule == null || rule.commands() == null || rule.commands().isEmpty()) {
                    continue;
                }
                if (rule.hasPrestigeFilter()) {
                    if (rule.prestigeLevels() == null || !rule.prestigeLevels().contains(prestigeLevel)) {
                        continue;
                    }
                } else if (prestigeLevel > 0 && !rule.repeatAccessPrestiges()) {
                    continue;
                }

                Map<String, String> extraPlaceholders = Map.of(
                        "{old_player_level}", Integer.toString(level - 1),
                        "{new_player_level}", Integer.toString(level),
                        "{level}", Integer.toString(level));

                executeConfiguredCommands(rule.commands(), asPlayer, playerRef, playerData,
                        prestigeLevel, prestigeLevel, extraPlaceholders, "level-up");
            }
        }
    }

    private void executeConfiguredCommands(List<String> configuredCommands,
            boolean asPlayer,
            PlayerRef playerRef,
            PlayerData playerData,
            int oldPrestigeLevel,
            int newPrestigeLevel,
            Map<String, String> extraPlaceholders,
            String eventLabel) {

        for (String template : configuredCommands) {
            String command = applyPlaceholders(template, playerData, oldPrestigeLevel, newPrestigeLevel,
                    extraPlaceholders);
            if (command == null || command.isBlank()) {
                continue;
            }

            String normalizedCommand = normalizeCommand(command);
            if (normalizedCommand.isBlank()) {
                continue;
            }

            if (asPlayer) {
                if (playerRef == null) {
                    LOGGER.atFine().log(
                            "Skipping %s command for %s because player is offline: %s",
                            eventLabel,
                            playerData.getPlayerName(),
                            normalizedCommand);
                    continue;
                }
                CommandManager.get().handleCommand(playerRef, normalizedCommand);
                continue;
            }

            CommandManager.get().handleCommand(ConsoleSender.INSTANCE, normalizedCommand);
        }
    }

    private String normalizeCommand(String rawCommand) {
        if (rawCommand == null) {
            return "";
        }

        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    private String applyPlaceholders(String command,
            PlayerData playerData,
            int oldPrestigeLevel,
            int newPrestigeLevel,
            Map<String, String> extraPlaceholders) {
        if (command == null || playerData == null) {
            return command;
        }

        String playerName = playerData.getPlayerName() == null ? "unknown" : playerData.getPlayerName();

        String rendered = command
                .replace("{player_name}", playerName)
                .replace("{player}", playerName)
                .replace("{player_uuid}", String.valueOf(playerData.getUuid()))
                .replace("{prestige_level}", Integer.toString(newPrestigeLevel))
                .replace("{new_prestige_level}", Integer.toString(newPrestigeLevel))
                .replace("{old_prestige_level}", Integer.toString(oldPrestigeLevel))
                .replace("{player_level}", Integer.toString(Math.max(1, playerData.getLevel())));

        if (extraPlaceholders == null || extraPlaceholders.isEmpty()) {
            return rendered;
        }

        for (Map.Entry<String, String> entry : extraPlaceholders.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            rendered = rendered.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }

        return rendered;
    }

    private List<String> resolveCommandsForLevel(Object rawCommands, int level) {
        if (rawCommands instanceof Map<?, ?> mapped) {
            List<String> results = new ArrayList<>();
            for (Map.Entry<?, ?> entry : mapped.entrySet()) {
                if (!matchesLevelKey(entry.getKey(), level)) {
                    continue;
                }
                results.addAll(extractCommands(entry.getValue()));
            }
            return results;
        }
        return asStringList(rawCommands);
    }

    private List<LevelCommandRule> resolveLevelCommandRules(Object rawCommands,
            int level,
            boolean defaultRepeatAccessPrestiges) {
        if (rawCommands instanceof Map<?, ?> mapped) {
            List<LevelCommandRule> results = new ArrayList<>();
            for (Map.Entry<?, ?> entry : mapped.entrySet()) {
                if (!matchesLevelKey(entry.getKey(), level)) {
                    continue;
                }

                List<String> commands = extractCommands(entry.getValue());
                if (commands.isEmpty()) {
                    continue;
                }

                boolean repeatAccessPrestiges = extractRepeatAccessPrestiges(entry.getValue(),
                        defaultRepeatAccessPrestiges);
                PrestigeLevelFilter prestigeFilter = extractPrestigeLevelFilter(entry.getValue());
                results.add(new LevelCommandRule(commands,
                        repeatAccessPrestiges,
                        prestigeFilter.levels(),
                        prestigeFilter.explicit()));
            }
            return results;
        }

        List<String> fallback = asStringList(rawCommands);
        if (fallback.isEmpty()) {
            return List.of();
        }
        return List.of(new LevelCommandRule(fallback, defaultRepeatAccessPrestiges, Set.of(), false));
    }

    private List<String> extractCommands(Object rawValue) {
        if (rawValue instanceof Map<?, ?> mapValue) {
            Object nestedCommands = mapValue.get("commands");
            if (nestedCommands != null) {
                return asStringList(nestedCommands);
            }
        }
        return asStringList(rawValue);
    }

    private boolean extractRepeatAccessPrestiges(Object rawValue, boolean defaultValue) {
        if (rawValue instanceof Map<?, ?> mapValue) {
            Object preferred = mapValue.get("repeat_access_prestiges");
            if (preferred != null) {
                return parseBoolean(preferred, defaultValue);
            }
            return parseBoolean(mapValue.get("recur_through_prestige"), defaultValue);
        }
        return defaultValue;
    }

    private PrestigeLevelFilter extractPrestigeLevelFilter(Object rawValue) {
        if (rawValue instanceof Map<?, ?> mapValue) {
            Object rawLevels = mapValue.containsKey("prestige_levels")
                    ? mapValue.get("prestige_levels")
                    : mapValue.get("prestige_level");
            if (rawLevels != null) {
                Set<Integer> levels = toIntegerSet(rawLevels);
                if (!levels.isEmpty()) {
                    return new PrestigeLevelFilter(levels, true);
                }
            }
        }
        return new PrestigeLevelFilter(Set.of(), false);
    }

    private Set<Integer> toIntegerSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }

        Set<Integer> results = new HashSet<>();

        if (raw instanceof Number number) {
            int value = number.intValue();
            if (value >= 0) {
                results.add(value);
            }
            return results;
        }

        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                if (entry instanceof Number number) {
                    int value = number.intValue();
                    if (value >= 0) {
                        results.add(value);
                    }
                    continue;
                }
                try {
                    int value = Integer.parseInt(entry.toString().trim());
                    if (value >= 0) {
                        results.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed entries.
                }
            }
            return results;
        }

        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return Set.of();
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        if ("*".equals(normalized) || "all".equals(normalized) || "any".equals(normalized)) {
            return Set.of();
        }

        if (text.startsWith("[") && text.endsWith("]") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }

        if (text.contains(",")) {
            for (String part : text.split(",")) {
                String trimmed = part == null ? "" : part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    int value = Integer.parseInt(trimmed);
                    if (value >= 0) {
                        results.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed entries.
                }
            }
            return results;
        }

        try {
            int value = Integer.parseInt(text);
            if (value >= 0) {
                results.add(value);
            }
        } catch (NumberFormatException ignored) {
            // Ignore malformed entry.
        }

        return results;
    }

    private boolean matchesLevelKey(Object rawKey, int level) {
        if (rawKey == null || level <= 0) {
            return false;
        }

        if (rawKey instanceof Number number) {
            return number.intValue() == level;
        }

        if (rawKey instanceof List<?> list) {
            return listContainsLevel(list, level);
        }

        String key = rawKey.toString().trim();
        if (key.isEmpty()) {
            return false;
        }

        String normalized = key.toLowerCase(Locale.ROOT);
        if ("all".equals(normalized) || "*".equals(normalized) || "default".equals(normalized)
                || "any".equals(normalized)) {
            return true;
        }

        if (normalized.startsWith("*/")) {
            return matchesRecurringInterval(normalized.substring(2), level);
        }
        if (normalized.startsWith("every:")) {
            return matchesRecurringInterval(normalized.substring("every:".length()), level);
        }
        if (normalized.startsWith("every ")) {
            return matchesRecurringInterval(normalized.substring("every ".length()), level);
        }

        if (key.startsWith("[") && key.endsWith("]") && key.length() >= 2) {
            String inner = key.substring(1, key.length() - 1);
            return csvContainsLevel(inner, level);
        }

        if (key.contains(",")) {
            return csvContainsLevel(key, level);
        }

        try {
            return Integer.parseInt(key) == level;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean matchesRecurringInterval(String rawInterval, int level) {
        if (rawInterval == null || rawInterval.isBlank() || level <= 0) {
            return false;
        }

        try {
            int interval = Integer.parseInt(rawInterval.trim());
            return interval > 0 && (level % interval == 0);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean listContainsLevel(List<?> list, int level) {
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            if (entry instanceof Number number && number.intValue() == level) {
                return true;
            }
            try {
                if (Integer.parseInt(entry.toString().trim()) == level) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries.
            }
        }
        return false;
    }

    private boolean csvContainsLevel(String csv, int level) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String part : csv.split(",")) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                if (Integer.parseInt(trimmed) == level) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries.
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<String> results = new ArrayList<>();
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            String value = entry.toString().trim();
            if (!value.isEmpty()) {
                results.add(value);
            }
        }
        return results;
    }

    private Object getRaw(String path, Object defaultValue) {
        return eventsConfig.get(path, defaultValue, false);
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(String path) {
        Object raw = eventsConfig.get(path, Collections.emptyList(), false);
        return asStringList(raw);
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> getIntegerSet(String path) {
        Object raw = eventsConfig.get(path, Collections.emptyList(), false);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }

        Set<Integer> results = new HashSet<>();
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            if (entry instanceof Number number) {
                int value = number.intValue();
                if (value > 0) {
                    results.add(value);
                }
                continue;
            }

            try {
                int value = Integer.parseInt(entry.toString().trim());
                if (value > 0) {
                    results.add(value);
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed values.
            }
        }

        return results;
    }

    private boolean getBoolean(String path, boolean defaultValue) {
        Object raw = eventsConfig.get(path, defaultValue, false);
        return parseBoolean(raw, defaultValue);
    }

    private boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        if (raw instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)
                    || "on".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)
                    || "off".equals(normalized)) {
                return false;
            }
        }
        return defaultValue;
    }
}
