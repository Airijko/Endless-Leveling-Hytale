package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.passives.PassiveDefinitionParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import org.yaml.snakeyaml.Yaml;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.SkillAttributeType;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.lang.reflect.Method;

public class RaceManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int BUILTIN_RACES_VERSION = 4;
    private static final String RACES_VERSION_FILE = "races.version";

    private final PluginFilesManager filesManager;
    private final boolean racesEnabled;
    private final boolean forceBuiltinRaces;
    private final Map<String, RaceDefinition> racesByKey = new HashMap<>();
    private final Yaml yaml = new Yaml();

    private String defaultRaceId = PlayerData.DEFAULT_RACE_ID;
    private final long chooseRaceCooldownSeconds;

    public RaceManager(ConfigManager configManager, PluginFilesManager filesManager) {
        Objects.requireNonNull(configManager, "ConfigManager is required");
        this.filesManager = Objects.requireNonNull(filesManager, "PluginFilesManager is required");
        this.racesEnabled = parseBoolean(configManager.get("enable_races", Boolean.TRUE, false), true);
        this.forceBuiltinRaces = parseBoolean(configManager.get("force_builtin_races", Boolean.FALSE, false),
                false);
        Object defaultRaceConfig = configManager.get("default_race", PlayerData.DEFAULT_RACE_ID, false);
        String configuredDefault = safeString(defaultRaceConfig);
        if (configuredDefault != null) {
            this.defaultRaceId = configuredDefault;
        }
        Object cooldownConfig = configManager.get("choose_race_cooldown", 0, false);
        this.chooseRaceCooldownSeconds = parseCooldownSeconds(cooldownConfig);

        if (!racesEnabled) {
            LOGGER.atInfo().log("Race system disabled via config.yml (enable_races=false).");
            return;
        }

        syncBuiltinRacesIfNeeded();
        loadRaces();
    }

    public boolean isEnabled() {
        return racesEnabled && !racesByKey.isEmpty();
    }

    public RaceDefinition getRace(String raceId) {
        if (raceId == null) {
            return null;
        }
        return racesByKey.get(normalizeKey(raceId));
    }

    public RaceDefinition getDefaultRace() {
        RaceDefinition configuredDefault = racesByKey.get(normalizeKey(defaultRaceId));
        if (configuredDefault != null) {
            return configuredDefault;
        }
        return racesByKey.values().stream().findFirst().orElse(null);
    }

    public long getChooseRaceCooldownSeconds() {
        return Math.max(0L, chooseRaceCooldownSeconds);
    }

    public String getDefaultRaceId() {
        RaceDefinition defaultRace = getDefaultRace();
        return defaultRace != null ? defaultRace.getId() : PlayerData.DEFAULT_RACE_ID;
    }

    public String resolveRaceId(String requestedId) {
        return resolveRaceIdentifier(requestedId);
    }

    public String resolveRaceIdentifier(String requestedValue) {
        if (!isEnabled()) {
            return PlayerData.DEFAULT_RACE_ID;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            RaceDefinition fallback = getDefaultRace();
            return fallback != null ? fallback.getId() : PlayerData.DEFAULT_RACE_ID;
        }

        RaceDefinition requested = findRaceByUserInput(requestedValue);
        if (requested != null) {
            return requested.getId();
        }

        RaceDefinition fallback = getDefaultRace();
        return fallback != null ? fallback.getId() : PlayerData.DEFAULT_RACE_ID;
    }

    public RaceDefinition getPlayerRace(PlayerData data) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolveRaceIdentifier(data.getRaceId());
        RaceDefinition resolved = getRace(resolvedId);
        if (resolved != null) {
            if (!resolved.getId().equals(data.getRaceId())) {
                data.setRaceId(resolved.getId());
            }
            return resolved;
        }

        RaceDefinition fallback = getDefaultRace();
        if (fallback != null) {
            if (!fallback.getId().equals(data.getRaceId())) {
                data.setRaceId(fallback.getId());
            }
            return fallback;
        }

        data.setRaceId(PlayerData.DEFAULT_RACE_ID);
        return null;
    }

    public RaceDefinition setPlayerRace(PlayerData data, String requestedValue) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolveRaceIdentifier(requestedValue);
        RaceDefinition resolved = getRace(resolvedId);
        if (resolved == null) {
            resolved = getDefaultRace();
        }
        if (resolved != null) {
            data.setRaceId(resolved.getId());
            applyRaceModelIfEnabled(data);
        } else {
            data.setRaceId(PlayerData.DEFAULT_RACE_ID);
        }
        return resolved;
    }

    public Collection<RaceDefinition> getLoadedRaces() {
        return Collections.unmodifiableCollection(racesByKey.values());
    }

    public RaceDefinition findRaceByUserInput(String userInput) {
        if (!isEnabled() || userInput == null) {
            return null;
        }
        RaceDefinition byId = getRace(userInput);
        if (byId != null) {
            return byId;
        }

        String normalizedName = normalizeKey(userInput);
        if (normalizedName.isEmpty()) {
            return null;
        }

        for (RaceDefinition definition : racesByKey.values()) {
            String displayName = definition.getDisplayName();
            if (displayName != null && normalizeKey(displayName).equals(normalizedName)) {
                return definition;
            }
        }
        return null;
    }

    public double getAttribute(PlayerData playerData, SkillAttributeType attributeType, double fallback) {
        if (!isEnabled() || playerData == null || attributeType == null) {
            return fallback;
        }
        RaceDefinition race = getPlayerRace(playerData);
        if (race == null) {
            return fallback;
        }
        return race.getBaseAttribute(attributeType, fallback);
    }

    /**
     * Apply the configured race model if the player has the option enabled and the
     * race defines one.
     */
    public void applyRaceModelIfEnabled(PlayerData data) {
        if (data == null || !data.isUseRaceModel()) {
            return;
        }
        RaceDefinition race = getPlayerRace(data);
        if (race == null) {
            return;
        }
        applyRaceModelToPlayer(data, race);
    }

    private void loadRaces() {
        File racesFolder = filesManager.getRacesFolder();
        if (racesFolder == null || !racesFolder.exists()) {
            LOGGER.atWarning().log("Races folder is missing; cannot load race definitions.");
            return;
        }

        try (Stream<Path> files = Files.walk(racesFolder.toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(this::loadRaceFromFile);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to walk races directory: %s", e.getMessage());
        }

        if (!racesByKey.containsKey(normalizeKey(defaultRaceId)) && !racesByKey.isEmpty()) {
            defaultRaceId = racesByKey.values().iterator().next().getId();
            LOGGER.atInfo().log("Default race set to %s", defaultRaceId);
        }

        LOGGER.atInfo().log("Loaded %d race definition(s).", racesByKey.size());
    }

    private void syncBuiltinRacesIfNeeded() {
        if (!forceBuiltinRaces) {
            return;
        }
        File racesFolder = filesManager.getRacesFolder();
        if (racesFolder == null) {
            LOGGER.atWarning().log("Races folder is null; cannot sync built-in races.");
            return;
        }

        int storedVersion = readRacesVersion(racesFolder);
        if (storedVersion == BUILTIN_RACES_VERSION) {
            return; // up to date
        }

        clearDirectory(racesFolder.toPath());
        filesManager.exportResourceDirectory("races", racesFolder, true);
        writeRacesVersion(racesFolder, BUILTIN_RACES_VERSION);
        LOGGER.atInfo().log("Synced built-in races to version %d (force_builtin_races=true)", BUILTIN_RACES_VERSION);
    }

    private int readRacesVersion(File racesFolder) {
        Path versionPath = racesFolder.toPath().resolve(RACES_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read races version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeRacesVersion(File racesFolder, int version) {
        Path versionPath = racesFolder.toPath().resolve(RACES_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write races version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear races directory: %s", e.getMessage());
        }
    }

    private void loadRaceFromFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Object> yamlData = yaml.load(reader);
            if (yamlData == null) {
                LOGGER.atWarning().log("Race file %s was empty.", path.getFileName());
                return;
            }

            RaceDefinition definition = buildDefinition(path, yamlData);
            if (!definition.isEnabled()) {
                LOGGER.atInfo().log("Skipping disabled race %s from %s", definition.getId(), path.getFileName());
                return;
            }

            racesByKey.put(normalizeKey(definition.getId()), definition);
            LOGGER.atInfo().log("Loaded race %s", definition.getId());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to read race file %s: %s", path.getFileName(), e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.atSevere().log("Failed to parse race file %s: %s", path.getFileName(), e.getMessage());
        }
    }

    private RaceDefinition buildDefinition(Path file, Map<String, Object> yamlData) {
        String raceId = deriveRaceId(file, yamlData);
        String displayName = safeString(yamlData.getOrDefault("race_name", raceId));
        String description = safeString(yamlData.get("description"));
        String modelId = safeString(yamlData.get("model"));
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        EnumMap<SkillAttributeType, Double> attributes = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> attributeSection = castToStringObjectMap(yamlData.get("attributes"));
        for (SkillAttributeType type : SkillAttributeType.values()) {
            if (attributeSection == null || !attributeSection.containsKey(type.getConfigKey())) {
                continue;
            }
            double value = parseDouble(attributeSection.get(type.getConfigKey()));
            attributes.put(type, value);
        }

        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(raceId, passives);

        return new RaceDefinition(raceId,
                displayName,
                description,
                modelId,
                enabled,
                attributes,
                passives,
                passiveDefinitions);
    }

    private List<Map<String, Object>> parsePassives(Object node) {
        List<Map<String, Object>> passives = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return passives;
        }
        for (Object entry : iterable) {
            Map<String, Object> passive = castToStringObjectMap(entry);
            if (passive != null && passive.containsKey("type")) {
                passives.add(passive);
            }
        }
        return passives;
    }

    private List<RacePassiveDefinition> buildPassiveDefinitions(String raceId, List<Map<String, Object>> passives) {
        List<RacePassiveDefinition> definitions = new ArrayList<>();
        if (passives == null) {
            return definitions;
        }

        for (int index = 0; index < passives.size(); index++) {
            Map<String, Object> passive = passives.get(index);
            if (passive == null) {
                continue;
            }

            String rawType = safeString(passive.get("type"));
            if (rawType == null) {
                LOGGER.atWarning().log("Race %s passive entry %d is missing a type", raceId, index + 1);
                continue;
            }

            ArchetypePassiveType type = ArchetypePassiveType.fromConfigKey(rawType);
            if (type == null) {
                LOGGER.atWarning().log("Race %s passive type '%s' is not recognized", raceId, rawType);
                continue;
            }

            double value = parseDouble(passive.get("value"));
            SkillAttributeType attributeType = null;
            if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                String attributeKey = safeString(passive.get("attribute"));
                attributeType = SkillAttributeType.fromConfigKey(attributeKey);
                if (attributeType == null) {
                    LOGGER.atWarning().log(
                            "Race %s passive entry %d has INNATE_ATTRIBUTE_GAIN without a valid attribute key",
                            raceId, index + 1);
                    continue;
                }
            }
            DamageLayer damageLayer = PassiveDefinitionParser.resolveDamageLayer(type, passive);
            String tag = PassiveDefinitionParser.resolveTag(type, passive);
            PassiveStackingStyle stacking = PassiveDefinitionParser.resolveStacking(type, passive);
            definitions.add(new RacePassiveDefinition(type,
                    value,
                    passive,
                    attributeType,
                    damageLayer,
                    tag,
                    stacking));
        }
        return definitions;
    }

    /**
     * Apply the race model (if any) by issuing a command on behalf of the player.
     * Uses reflection to avoid hard coupling to the command API; logs a warning if
     * the command runner cannot be found.
     */
    private void applyRaceModelToPlayer(PlayerData data, RaceDefinition race) {
        if (data == null || race == null) {
            return;
        }
        PlayerRef playerRef = Universe.get().getPlayer(data.getUuid());
        if (playerRef == null) {
            return; // player not online yet
        }

        String modelId = race.getModelId();
        if (modelId == null || modelId.isBlank()) {
            resetPlayerModel(playerRef);
            return;
        }

        String baseCommand = "model set " + modelId + " " + playerRef.getUsername();
        LOGGER.atFine().log("RaceManager: applying model %s to %s using command '%s'", modelId,
                playerRef.getUsername(), baseCommand);

        if (!dispatchModelCommand(playerRef, baseCommand)) {
            LOGGER.atWarning().log("RaceManager: failed to dispatch model command '%s' for %s", baseCommand,
                    data.getPlayerName());
            logCommandIntrospection();
        }
    }

    private boolean resetPlayerModel(PlayerRef playerRef) {
        if (playerRef == null) {
            return false;
        }
        String command = "model reset " + playerRef.getUsername();
        LOGGER.atFine().log("RaceManager: resetting model for %s using command '%s'", playerRef.getUsername(), command);
        return dispatchModelCommand(playerRef, command);
    }

    private boolean dispatchModelCommand(PlayerRef playerRef, String commandWithOptionalSlash) {
        if (commandWithOptionalSlash == null || commandWithOptionalSlash.isBlank()) {
            return false;
        }
        String commandNoSlash = commandWithOptionalSlash.startsWith("/")
                ? commandWithOptionalSlash.substring(1)
                : commandWithOptionalSlash;

        boolean dispatched = runCommandAsConsole("/" + commandNoSlash);
        if (!dispatched) {
            dispatched = runCommandAsConsole(commandNoSlash);
        }
        if (!dispatched) {
            dispatched = runCommandViaCommandManager(playerRef, commandNoSlash);
        }
        if (!dispatched) {
            dispatched = runCommandViaReflection(playerRef, "/" + commandNoSlash);
        }
        if (!dispatched) {
            dispatched = runCommandViaReflection(playerRef, commandNoSlash);
        }
        return dispatched;
    }

    private boolean runCommandAsConsole(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Object universe = Universe.get();
        if (universe == null) {
            return false;
        }
        // Try universe command manager first
        Object commandManager = invokeNoArg(universe, "getCommandManager");
        if (commandManager != null && invokeConsoleCommand(commandManager, command)) {
            return true;
        }
        // Try universe directly in case it exposes command execution
        if (invokeConsoleCommand(universe, command)) {
            return true;
        }

        // Try plugin command manager if exposed
        Object plugin = EndlessLeveling.getInstance();
        if (plugin != null) {
            Object pluginCommandManager = invokeNoArg(plugin, "getCommandManager");
            if (pluginCommandManager != null && invokeConsoleCommand(pluginCommandManager, command)) {
                return true;
            }
            if (invokeConsoleCommand(plugin, command)) {
                return true;
            }
        }

        // Try static methods on Universe class
        if (invokeStaticCommand(Universe.class, command)) {
            return true;
        }

        return false;
    }

    private boolean invokeStaticCommand(Class<?> type, String command) {
        if (type == null || command == null || command.isBlank()) {
            return false;
        }
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand", "executeConsole",
                "executeCommandString", "handleCommand" };
        for (String name : candidateNames) {
            Method m = findMethod(type, name, String.class);
            if (m == null) {
                continue;
            }
            try {
                m.setAccessible(true);
                m.invoke(null, command);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Try to dispatch a command string on behalf of a player using common method
     * names via reflection to keep compatibility with server API changes.
     */
    private boolean runCommandViaReflection(PlayerRef playerRef, String command) {
        if (playerRef == null || command == null || command.isBlank()) {
            return false;
        }
        try {
            Object world = invokeNoArg(playerRef, "getWorld");
            if (world != null) {
                if (invokeCommandAnySignature(world, playerRef, command)) {
                    return true;
                }
            }

            Object commandManager = invokeNoArg(playerRef, "getCommandManager");
            if (commandManager != null && invokeCommandAnySignature(commandManager, playerRef, command)) {
                return true;
            }

            Object universe = Universe.get();
            if (universe != null) {
                Object universeCommandManager = invokeNoArg(universe, "getCommandManager");
                if (universeCommandManager != null
                        && invokeCommandAnySignature(universeCommandManager, playerRef, command)) {
                    return true;
                }
            }

            if (invokeCommandAnySignature(playerRef, playerRef, command)) {
                return true;
            }

            return false;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: error dispatching model command: %s", ex.getMessage());
            return false;
        }
    }

    private boolean runCommandViaCommandManager(PlayerRef playerRef, String command) {
        if (playerRef == null || command == null || command.isBlank()) {
            return false;
        }
        try {
            CommandManager manager = CommandManager.get();
            if (manager == null) {
                return false;
            }
            manager.handleCommand(playerRef, command);
            return true;
        } catch (Throwable ex) {
            LOGGER.atWarning().log("RaceManager: CommandManager handleCommand failed: %s", ex.getMessage());
            return false;
        }
    }

    private boolean invokeConsoleCommand(Object target, String command) {
        return invokeCommandAnySignature(target, null, command);
    }

    private boolean invokeCommandAnySignature(Object target, Object sender, String command) {
        if (target == null || command == null || command.isBlank()) {
            return false;
        }
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "sendChatMessage", "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand",
                "executeConsole", "executeCommandString", "handleCommand" };
        Method best = null;
        for (Method method : target.getClass().getMethods()) {
            String name = method.getName();
            boolean nameMatch = false;
            for (String candidate : candidateNames) {
                if (candidate.equals(name)) {
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == String.class) {
                best = method;
                break;
            }
            if (params.length == 2 && params[1] == String.class) {
                best = method;
                // prefer string + sender variants too; keep searching for exact match
                if (sender != null && params[0].isInstance(sender)) {
                    break;
                }
            }
        }
        if (best == null) {
            return false;
        }
        try {
            best.setAccessible(true);
            Class<?>[] params = best.getParameterTypes();
            if (params.length == 1) {
                best.invoke(target, command);
            } else if (params.length == 2) {
                Object firstArg = null;
                if (sender != null && params[0].isInstance(sender)) {
                    firstArg = sender;
                }
                best.invoke(target, firstArg, command);
            } else {
                return false;
            }
            return true;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: failed to invoke command method %s on %s: %s", best.getName(),
                    target.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }

    private void logCommandIntrospection() {
        Object universe = Universe.get();
        Object universeCommandManager = universe != null ? invokeNoArg(universe, "getCommandManager") : null;
        Object plugin = EndlessLeveling.getInstance();
        Object pluginCommandManager = plugin != null ? invokeNoArg(plugin, "getCommandManager") : null;

        String universeMethods = describeCommandMethods(universe);
        String universeManagerMethods = describeCommandMethods(universeCommandManager);
        String pluginMethods = describeCommandMethods(plugin);
        String pluginManagerMethods = describeCommandMethods(pluginCommandManager);
        String universeStatic = describeCommandMethods(Universe.class);

        LOGGER.atWarning().log(
                "RaceManager: command introspection - Universe: %s | UniverseCmdMgr: %s | Plugin: %s | PluginCmdMgr: %s | Universe(static): %s",
                universeMethods, universeManagerMethods, pluginMethods, pluginManagerMethods, universeStatic);
    }

    private String describeCommandMethods(Object target) {
        if (target == null) {
            return "(null)";
        }
        Class<?> type = target instanceof Class<?> cls ? cls : target.getClass();
        String[] candidateNames = new String[] { "executeCommand", "dispatchCommand", "runCommand", "execute",
                "sendChatMessage", "executeConsoleCommand", "dispatchConsoleCommand", "runConsoleCommand",
                "executeConsole", "executeCommandString", "handleCommand" };
        StringBuilder sb = new StringBuilder();
        for (Method method : type.getMethods()) {
            for (String candidate : candidateNames) {
                if (candidate.equals(method.getName())) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(method.getName()).append('(');
                    Class<?>[] params = method.getParameterTypes();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(params[i].getSimpleName());
                    }
                    sb.append(')');
                }
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    private boolean invokeCommand(Object target, PlayerRef playerRef, String command) {
        Method m = findMethod(target.getClass(), "executeCommand", playerRef.getClass(), String.class);
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", playerRef.getClass(), String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "executeCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", String.class, playerRef.getClass());
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", String.class, playerRef.getClass());
        }
        if (m == null) {
            // try looser signature
            m = findMethod(target.getClass(), "executeCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", Object.class, String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", Object.class, String.class);
        }
        if (m == null) {
            // single-argument variants
            m = findMethod(target.getClass(), "executeCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "dispatchCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "runCommand", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "execute", String.class);
        }
        if (m == null) {
            m = findMethod(target.getClass(), "sendChatMessage", String.class);
        }
        if (m == null) {
            return false;
        }
        try {
            m.setAccessible(true);
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2) {
                if (params[0].isAssignableFrom(playerRef.getClass())) {
                    m.invoke(target, playerRef, command);
                } else {
                    m.invoke(target, command, playerRef);
                }
            } else if (params.length == 1) {
                // assume single String parameter
                m.invoke(target, command);
            } else {
                m.invoke(target, playerRef, command);
            }
            return true;
        } catch (Exception ex) {
            LOGGER.atWarning().log("RaceManager: failed to invoke command method %s: %s", m.getName(),
                    ex.getMessage());
            return false;
        }
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception ex) {
            return null;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private Map<String, Object> castToStringObjectMap(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private String deriveRaceId(Path file, Map<String, Object> yamlData) {
        String explicitId = safeString(yamlData.get("id"));
        if (explicitId != null) {
            return explicitId;
        }
        String raceName = safeString(yamlData.get("race_name"));
        if (raceName != null) {
            return raceName;
        }
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        if (base.isBlank()) {
            return PlayerData.DEFAULT_RACE_ID;
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private String safeString(Object value) {
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return defaultValue;
    }

    private long parseCooldownSeconds(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof String stringValue) {
            try {
                long parsed = Long.parseLong(stringValue.trim());
                return Math.max(0L, parsed);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
