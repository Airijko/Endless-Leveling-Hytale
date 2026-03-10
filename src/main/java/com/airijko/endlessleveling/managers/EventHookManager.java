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

        List<String> configuredCommands = getStringList(PRESTIGE_EVENT_PATH + ".commands");
        if (configuredCommands.isEmpty()) {
            return;
        }

        boolean asPlayer = getBoolean(PRESTIGE_EVENT_PATH + ".as_player", false);
        PlayerRef playerRef = Universe.get().getPlayer(playerData.getUuid());

        executeConfiguredCommands(configuredCommands, asPlayer, playerRef, playerData,
                oldPrestigeLevel, newPrestigeLevel, Collections.emptyMap(), "prestige");
    }

    public void onPlayerLevelUp(PlayerData playerData, int oldLevel, int newLevel) {
        if (playerData == null || newLevel <= oldLevel) {
            return;
        }

        if (!getBoolean(LEVEL_EVENT_PATH + ".enabled", false)) {
            return;
        }

        Set<Integer> triggerLevels = getIntegerSet(LEVEL_EVENT_PATH + ".trigger_levels");
        if (!triggerLevels.isEmpty() && !triggerLevels.contains(newLevel)) {
            return;
        }

        List<String> configuredCommands = getStringList(LEVEL_EVENT_PATH + ".commands");
        if (configuredCommands.isEmpty()) {
            return;
        }

        boolean asPlayer = getBoolean(LEVEL_EVENT_PATH + ".as_player", false);
        PlayerRef playerRef = Universe.get().getPlayer(playerData.getUuid());
        int prestigeLevel = Math.max(0, playerData.getPrestigeLevel());

        Map<String, String> extraPlaceholders = Map.of(
                "{old_player_level}", Integer.toString(oldLevel),
                "{new_player_level}", Integer.toString(newLevel),
                "{level}", Integer.toString(newLevel));

        executeConfiguredCommands(configuredCommands, asPlayer, playerRef, playerData,
                prestigeLevel, prestigeLevel, extraPlaceholders, "level-up");
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

    @SuppressWarnings("unchecked")
    private List<String> getStringList(String path) {
        Object raw = eventsConfig.get(path, Collections.emptyList(), false);
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
