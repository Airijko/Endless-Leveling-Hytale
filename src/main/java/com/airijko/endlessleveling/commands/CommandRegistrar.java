package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.commands.profile.ProfileCommand;
import com.airijko.endlessleveling.commands.classes.ClassCommand;
import com.airijko.endlessleveling.commands.augments.AugmentCommand;
import com.airijko.endlessleveling.util.FixedValue;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CommandRegistrar {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long STARTUP_FALLBACK_LOG_DELAY_MS = 3000L;

    private static final String[] ROOT_CANDIDATES = {"lvl", "el", "sk", "endlessleveling", "eskills", "level"};

    private CommandRegistrar() {
    }

        public static String registerCommands(Object commandRegistry,
            Object eventRegistry,
            PartyManager partyManager,
            RaceManager raceManager,
            ClassManager classManager,
            PlayerDataManager playerDataManager,
            AugmentManager augmentManager) {

        LOGGER.atWarning().log("CommandRegistrar.registerCommands invoked (commandRegistry=%s)",
                commandRegistry != null ? "present" : "null");
        
        String commandRoot = chooseAvailableCommandRoot(commandRegistry);
        FixedValue.ROOT_COMMAND.setValue("/" + commandRoot);

        if ("lvl".equalsIgnoreCase(commandRoot)) {
            LOGGER.atInfo().log("EndlessLevelingCommand root '/lvl' is set (default). If missing, then command is not taken by another plugin.");
        } else {
            LOGGER.atWarning().log("EndlessLevelingCommand root '/lvl' is already registered by another plugin; using fallback '/%s' instead.", commandRoot);
            LOGGER.atWarning().log("EndlessLeveling command aliases available: %s", String.join(", ", ROOT_CANDIDATES));
            scheduleFallbackStartupLog(commandRoot);
        }

        // Always log the final command route at severe level so it appears in all standard log filters.
        LOGGER.atSevere().log("EndlessLeveling final command root selected = '/%s' (candidates=%s)", commandRoot,
                String.join(", ", ROOT_CANDIDATES));

        EndlessLevelingCommand rootCommand = new EndlessLevelingCommand(commandRoot);
        rootCommand.setActiveCommandRoot(commandRoot);
        ProfileCommand profileCommand = new ProfileCommand();
        PartyCommand partyCommand = partyManager != null && partyManager.isAvailable() ? new PartyCommand() : null;
        RaceCommand raceCommand = new RaceCommand(raceManager, playerDataManager);
        ClassCommand classCommand = new ClassCommand(classManager, playerDataManager);
        AugmentCommand augmentCommand = new AugmentCommand();

        registerCommand(commandRegistry, rootCommand);
        ensureRootCommandRegistered(commandRegistry, rootCommand, commandRoot);
        registerCommand(commandRegistry, profileCommand);
        ensureShortcutCommandRegistered(commandRegistry, profileCommand, "/profile");
        if (partyCommand != null) {
            registerCommand(commandRegistry, partyCommand);
            ensureShortcutCommandRegistered(commandRegistry, partyCommand, "/party");
        }
        registerCommand(commandRegistry, raceCommand);
        ensureShortcutCommandRegistered(commandRegistry, raceCommand, "/races");
        registerCommand(commandRegistry, classCommand);
        ensureShortcutCommandRegistered(commandRegistry, classCommand, "/classes");
        registerCommand(commandRegistry, augmentCommand);
        ensureShortcutCommandRegistered(commandRegistry, augmentCommand, "/augments");

        // Run a single final ownership check right before server boot completes.
        scheduleFinalRootCheckOnBootEvent(eventRegistry, commandRegistry, commandRoot, rootCommand);
        return commandRoot;
    }

    private static void registerCommand(Object commandRegistry, Object command) {
        if (commandRegistry == null || command == null) {
            return;
        }
        try {
            var method = commandRegistry.getClass().getMethod("registerCommand", command.getClass());
            method.setAccessible(true);
            method.invoke(commandRegistry, command);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {
        }

        try {
            var method = commandRegistry.getClass().getMethod("registerCommand", Object.class);
            method.setAccessible(true);
            method.invoke(commandRegistry, command);
        } catch (Exception ignored) {
        }
    }

    public static String chooseAvailableCommandRoot(Object commandRegistry) {
        if (commandRegistry == null) {
            LOGGER.atWarning().log("Command registry unavailable; defaulting to '/lvl'.");
            return "lvl";
        }

        if (isCommandRegistered(commandRegistry, "lvl")) {
            LOGGER.atWarning().log("Detected '/lvl' is already registered by another plugin.");
            LOGGER.atWarning().log("'/lvl' is in use by another plugin; selecting fallback root.");
        } else {
            LOGGER.atInfo().log("'/lvl' is free; using it as Endless Leveling command root.");
            return "lvl";
        }

        for (String candidate : ROOT_CANDIDATES) {
            if (candidate.equalsIgnoreCase("lvl")) {
                continue;
            }
            if (!isCommandRegistered(commandRegistry, candidate)) {
                return candidate;
            } else {
                LOGGER.atFine().log("Fallback candidate '/%s' is already in use; checking next.", candidate);
            }
        }

        LOGGER.atWarning().log("No fallback root could be found; defaulting to '/lvl' (may conflict). ");
        return "lvl";
    }

    private static void scheduleFallbackStartupLog(String commandRoot) {
        Thread startupLogThread = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_FALLBACK_LOG_DELAY_MS);
                LOGGER.atInfo().log("Startup command root fallback active: using '/%s' because '/lvl' is already taken.", commandRoot);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "EndlessLeveling-FallbackRootLog");
        startupLogThread.setDaemon(true);
        startupLogThread.start();
    }

    private static void scheduleFinalRootCheckOnBootEvent(
            Object eventRegistry,
            Object commandRegistry,
            String initialRoot,
            EndlessLevelingCommand initialRootCommand) {
        if (!"lvl".equalsIgnoreCase(initialRoot) || eventRegistry == null) {
            return;
        }

        try {
            Class<?> bootEventClass = Class.forName("com.hypixel.hytale.server.core.event.events.BootEvent");
            var registerMethod = eventRegistry.getClass().getMethod("register", Class.class, Consumer.class);
            AtomicBoolean handled = new AtomicBoolean(false);

            Consumer<Object> onBoot = event -> {
                if (!handled.compareAndSet(false, true)) {
                    return;
                }

                java.util.Map<?, ?> registrationMap = getCommandRegistrationMap();
                if (registrationMap == null) {
                    return;
                }

                Object lvlCommand = getRegisteredCommand(registrationMap, "lvl");
                if (lvlCommand == null
                        || lvlCommand == initialRootCommand
                        || isCommandOwnedByEndlessLeveling(lvlCommand)) {
                    LOGGER.atInfo().log("Final startup root check: '/lvl' remains active for EndlessLeveling.");
                    return;
                }

                String owner = getOwnerName(lvlCommand);
                String fallbackRoot = findFirstAvailableFallbackRoot(registrationMap);
                if (fallbackRoot == null) {
                    LOGGER.atWarning().log(
                            "Final startup root check: '/lvl' is owned by %s, and no fallback alias is available.",
                            owner);
                    return;
                }

                if (registerFallbackWithCommandManager(fallbackRoot)) {
                    FixedValue.ROOT_COMMAND.setValue("/" + fallbackRoot);
                    LOGGER.atWarning().log(
                            "Final startup root check: '/lvl' is owned by %s. Registered fallback root '/%s'.",
                            owner,
                            fallbackRoot);
                } else {
                    LOGGER.atWarning().log(
                            "Final startup root check: detected '/lvl' owner %s, but failed to register fallback '/%s'.",
                            owner,
                            fallbackRoot);
                }
            };

            registerMethod.invoke(eventRegistry, bootEventClass, onBoot);
        } catch (Exception ignored) {
            LOGGER.atWarning().log("Unable to register final root check on BootEvent; fallback watch disabled.");
        }
    }

    private static boolean registerFallbackWithCommandManager(String fallbackRoot) {
        try {
            Class<?> managerClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.CommandManager");
            Object manager = managerClass.getMethod("get").invoke(null);
            if (manager == null) {
                return false;
            }

            Class<?> abstractCommandClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.AbstractCommand");
            var registerMethod = managerClass.getMethod("register", abstractCommandClass);

            EndlessLevelingCommand fallbackCommand = new EndlessLevelingCommand(fallbackRoot);
            fallbackCommand.setActiveCommandRoot(fallbackRoot);
            Object registration = registerMethod.invoke(manager, fallbackCommand);
            return registration != null;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void ensureRootCommandRegistered(
            Object commandRegistry,
            EndlessLevelingCommand rootCommand,
            String commandRoot) {
        java.util.Map<?, ?> registrationMap = getCommandRegistrationMap();
        Object registered = getRegisteredCommand(registrationMap, commandRoot);
        if (registered == rootCommand || isCommandOwnedByEndlessLeveling(registered)) {
            return;
        }

        registerCommand(commandRegistry, rootCommand);
        registrationMap = getCommandRegistrationMap();
        registered = getRegisteredCommand(registrationMap, commandRoot);
        if (registered == rootCommand || isCommandOwnedByEndlessLeveling(registered)) {
            return;
        }

        if (registerRootWithCommandManager(rootCommand)) {
            LOGGER.atWarning().log(
                    "Root command repair: '/%s' was not registered through command registry, repaired via CommandManager.",
                    commandRoot);
        } else {
            LOGGER.atWarning().log(
                    "Root command repair failed: '/%s' is still not registered.",
                    commandRoot);
        }
    }

    private static boolean registerRootWithCommandManager(EndlessLevelingCommand rootCommand) {
        try {
            Class<?> managerClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.CommandManager");
            Object manager = managerClass.getMethod("get").invoke(null);
            if (manager == null) {
                return false;
            }

            Class<?> abstractCommandClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.AbstractCommand");
            var registerMethod = managerClass.getMethod("register", abstractCommandClass);
            Object registration = registerMethod.invoke(manager, rootCommand);
            return registration != null;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void ensureShortcutCommandRegistered(Object commandRegistry, Object command, String label) {
        String commandName = getCommandName(command);
        if (commandName == null) {
            return;
        }

        java.util.Map<?, ?> registrationMap = getCommandRegistrationMap();
        Object registered = getRegisteredCommand(registrationMap, commandName);

        if (registered != null) {
            if (!isCommandOwnedByEndlessLeveling(registered)) {
                LOGGER.atWarning().log(
                        "Shortcut command '%s' is owned by another plugin (%s); leaving as-is.",
                        label,
                        getOwnerName(registered));
            }
            return;
        }

        registerCommand(commandRegistry, command);
        registrationMap = getCommandRegistrationMap();
        registered = getRegisteredCommand(registrationMap, commandName);
        if (registered != null) {
            return;
        }

        if (registerAnyCommandWithCommandManager(command)) {
            LOGGER.atWarning().log(
                    "Shortcut command repair: %s was missing and has been registered via CommandManager.",
                    label);
        } else {
            LOGGER.atWarning().log(
                    "Shortcut command repair failed: %s is still not registered.",
                    label);
        }
    }

    private static String getCommandName(Object command) {
        if (command == null) {
            return null;
        }
        try {
            Object nameObj = command.getClass().getMethod("getName").invoke(command);
            if (nameObj instanceof String name && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean registerAnyCommandWithCommandManager(Object command) {
        if (command == null) {
            return false;
        }
        try {
            Class<?> managerClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.CommandManager");
            Object manager = managerClass.getMethod("get").invoke(null);
            if (manager == null) {
                return false;
            }

            Class<?> abstractCommandClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.AbstractCommand");
            var registerMethod = managerClass.getMethod("register", abstractCommandClass);
            Object registration = registerMethod.invoke(manager, command);
            return registration != null;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String findFirstAvailableFallbackRoot(java.util.Map<?, ?> registrationMap) {
        for (String candidate : ROOT_CANDIDATES) {
            if ("lvl".equalsIgnoreCase(candidate)) {
                continue;
            }
            if (!isCommandRegistered(registrationMap, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static java.util.Map<?, ?> getCommandRegistrationMap() {
        try {
            Class<?> managerClass = Class.forName(
                    "com.hypixel.hytale.server.core.command.system.CommandManager");
            Object manager = managerClass.getMethod("get").invoke(null);
            if (manager == null) {
                return null;
            }
            Object mapObj = managerClass.getMethod("getCommandRegistration").invoke(manager);
            if (mapObj instanceof java.util.Map<?, ?> map) {
                return map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object getRegisteredCommand(java.util.Map<?, ?> registrationMap, String commandName) {
        if (registrationMap == null || commandName == null || commandName.isBlank()) {
            return null;
        }
        for (java.util.Map.Entry<?, ?> entry : registrationMap.entrySet()) {
            if (commandName.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isCommandOwnedByEndlessLeveling(Object commandObj) {
        if (commandObj == null) {
            return false;
        }

        String className = commandObj.getClass().getName();
        if (className.startsWith("com.airijko.endlessleveling")) {
            return true;
        }

        try {
            Object owner = commandObj.getClass().getMethod("getOwner").invoke(commandObj);
            if (owner != null) {
                String ownerName = (String) owner.getClass().getMethod("getName").invoke(owner);
                return ownerName != null && ownerName.toLowerCase().contains("endlessleveling");
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private static String getOwnerName(Object commandObj) {
        if (commandObj == null) {
            return "unknown";
        }
        try {
            Object owner = commandObj.getClass().getMethod("getOwner").invoke(commandObj);
            if (owner != null) {
                String name = (String) owner.getClass().getMethod("getName").invoke(owner);
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return commandObj.getClass().getName();
    }

    /**
     * Checks whether a command name is already registered by querying
     * CommandManager.get().getCommandRegistration() — the authoritative map.
     * CommandRegistry (the plugin-scoped object) has no lookup methods, so
     * reflection on it always fails; we go straight to the singleton instead.
     */
    private static boolean isCommandRegistered(Object commandRegistry, String commandName) {
        if (commandName == null || commandName.isBlank()) {
            return false;
        }
        java.util.Map<?, ?> registrationMap = getCommandRegistrationMap();
        return isCommandRegistered(registrationMap, commandName);
    }

    private static boolean isCommandRegistered(java.util.Map<?, ?> registrationMap, String commandName) {
        if (registrationMap == null || commandName == null || commandName.isBlank()) {
            return false;
        }
        try {
            for (Object key : registrationMap.keySet()) {
                if (commandName.equalsIgnoreCase(String.valueOf(key))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

}
