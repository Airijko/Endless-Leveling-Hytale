package com.airijko.endlessleveling.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PluginFilesManager {

    private static final String PLUGIN_FOLDER_NAME = "EndlessLeveling";
    private static final String PLAYERDATA_FOLDER_NAME = "playerdata";
    private static final String LEGACY_PLAYERDATA_ARCHIVE_FOLDER_NAME = "old-playerdata-yml";
    private static final String PARTYDATA_FOLDER_NAME = "partydata";
    private static final String RACES_FOLDER_NAME = "races";
    private static final String CLASSES_FOLDER_NAME = "classes";
    private static final String AUGMENTS_FOLDER_NAME = "augments";
    private static final String LANG_FOLDER_NAME = "lang";
    private static final String WORLD_SETTINGS_FOLDER_NAME = "world-settings";
    private static final String WEAPONS_FILE_NAME = "weapons.json";
    private static final String PARTYDATA_FILE_NAME = "parties.json";
    private static final String OLD_FOLDER_NAME = "old";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern MANIFEST_VERSION_PATTERN = Pattern.compile("\"Version\"\\s*:\\s*\"([^\"]+)\"");
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final List<String> BUILTIN_WORLD_SETTINGS_FILES = Arrays.asList(
            "blacklisted-worlds.json",
            "global.json",
            "default.json",
            "major-dungeons.json",
            "endgame-dungeons.json",
            "shiva-dungeons.json",
            "README.md");

    private final JavaPlugin plugin;
    private final File pluginFolder;
    private final File playerDataFolder;
    private final File legacyPlayerDataArchiveFolder;
    private final File partyDataFolder;
    private final File racesFolder;
    private final File classesFolder;
    private final File augmentsFolder;
    private final File langFolder;
    private final File worldSettingsFolder;

    private final File weaponsFile;

    private final File configFile;
    private final File levelingFile;
    private final File eventsFile;
    private final File partyDataFile;
    private final Object archiveLock = new Object();
    private Path currentArchiveSession;

    public PluginFilesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Path modsPath = PluginManager.MODS_PATH;
        if (modsPath == null) {
            throw new IllegalStateException("Mods path is not initialized for EndlessLeveling");
        }
        this.pluginFolder = modsPath.resolve(PLUGIN_FOLDER_NAME).toFile();
        this.playerDataFolder = new File(pluginFolder, PLAYERDATA_FOLDER_NAME);
        this.legacyPlayerDataArchiveFolder = new File(playerDataFolder, LEGACY_PLAYERDATA_ARCHIVE_FOLDER_NAME);
        this.partyDataFolder = new File(pluginFolder, PARTYDATA_FOLDER_NAME);
        this.racesFolder = new File(pluginFolder, RACES_FOLDER_NAME);
        this.classesFolder = new File(pluginFolder, CLASSES_FOLDER_NAME);
        this.augmentsFolder = new File(pluginFolder, AUGMENTS_FOLDER_NAME);
        this.langFolder = new File(pluginFolder, LANG_FOLDER_NAME);
        this.worldSettingsFolder = new File(pluginFolder, WORLD_SETTINGS_FOLDER_NAME);

        createFolders();

        this.configFile = initYamlFile("config.yml");
        this.levelingFile = initYamlFile("leveling.yml");
        this.eventsFile = initYamlFile("events.yml");
        this.weaponsFile = initResourceFile(WEAPONS_FILE_NAME);
        this.partyDataFile = initPartyDataFile();

        seedResourceDirectoryIfEmpty("races", racesFolder);
        seedResourceDirectoryIfEmpty("classes", classesFolder);
        // Seed bundled augments only when the directory is empty so per-file deletions can disable augments.
        seedResourceDirectoryIfEmpty("augments", augmentsFolder);
        seedResourceDirectoryIfEmpty("world-settings", worldSettingsFolder);
        exportResourceDirectory("lang", langFolder, false);
    }

    /** Create the plugin folder and player data folder */
    private void createFolders() {
        try {
            Files.createDirectories(pluginFolder.toPath());
            Files.createDirectories(playerDataFolder.toPath());
            Files.createDirectories(partyDataFolder.toPath());
            Files.createDirectories(racesFolder.toPath());
            Files.createDirectories(classesFolder.toPath());
            Files.createDirectories(augmentsFolder.toPath());
            Files.createDirectories(langFolder.toPath());
            Files.createDirectories(worldSettingsFolder.toPath());
            LOGGER.atInfo().log("Plugin folders initialized at: %s", pluginFolder.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create EndlessLeveling folders", e);
        }
    }

    /** Accessors */
    public File getPluginFolder() {
        return pluginFolder;
    }

    public File getPlayerDataFolder() {
        return playerDataFolder;
    }

    /**
     * Optional local fail-safe folder that stores copies of migrated legacy
     * playerdata YAML files. Users can delete this folder safely when no
     * rollback history is needed.
     */
    public File getLegacyPlayerDataArchiveFolder() {
        return legacyPlayerDataArchiveFolder;
    }

    public File getPartyDataFolder() {
        return partyDataFolder;
    }

    public File getRacesFolder() {
        return racesFolder;
    }

    public File getClassesFolder() {
        return classesFolder;
    }

    public File getAugmentsFolder() {
        return augmentsFolder;
    }

    public File getLangFolder() {
        return langFolder;
    }

    public File getWorldSettingsFolder() {
        return worldSettingsFolder;
    }

    /**
     * Sync or migrate bundled world-settings files while leaving custom files
     * untouched.
     *
     * force_builtin_world_settings=true: overwrite bundled files on version bump.
     * force_builtin_world_settings=false: merge bundled defaults into bundled files,
     * preserving user-edited values.
     */
    public void syncBuiltinWorldSettingsIfNeeded(ConfigManager configManager) {
        if (configManager == null || worldSettingsFolder == null) {
            return;
        }

        boolean forceSync = parseBoolean(configManager.get("force_builtin_world_settings", Boolean.FALSE, false),
                false);
        int storedVersion = readWorldSettingsVersion(worldSettingsFolder);
        if (storedVersion == VersionRegistry.BUILTIN_WORLD_SETTINGS_VERSION) {
            return;
        }

        archivePathIfExists(worldSettingsFolder.toPath(), "world-settings",
                "world-settings.version:" + storedVersion);

        if (forceSync) {
            for (String fileName : BUILTIN_WORLD_SETTINGS_FILES) {
                exportResourceFile("world-settings/" + fileName, worldSettingsFolder.toPath().resolve(fileName), true);
            }
            writeWorldSettingsVersion(worldSettingsFolder, VersionRegistry.BUILTIN_WORLD_SETTINGS_VERSION);
            LOGGER.atInfo().log("Synced built-in world-settings to version %d (force_builtin_world_settings=true)",
                    VersionRegistry.BUILTIN_WORLD_SETTINGS_VERSION);
            return;
        }

        for (String fileName : BUILTIN_WORLD_SETTINGS_FILES) {
            Path target = worldSettingsFolder.toPath().resolve(fileName);
            if (!fileName.toLowerCase().endsWith(".json")) {
                // Non-JSON support files are created when missing but never overwritten in
                // migration mode.
                if (!Files.exists(target)) {
                    exportResourceFile("world-settings/" + fileName, target, false);
                }
                continue;
            }
            mergeBundledJsonDefaults("world-settings/" + fileName, target);
        }

        writeWorldSettingsVersion(worldSettingsFolder, VersionRegistry.BUILTIN_WORLD_SETTINGS_VERSION);
        LOGGER.atInfo().log("Migrated built-in world-settings to version %d (force_builtin_world_settings=false)",
                VersionRegistry.BUILTIN_WORLD_SETTINGS_VERSION);
    }

    public File getWeaponsFile() {
        return weaponsFile;
    }

    public File getConfigFile() {
        return configFile;
    }

    public File getLevelingFile() {
        return levelingFile;
    }

    public File getEventsFile() {
        return eventsFile;
    }

    /** Convenience method for player data file */
    public File getPlayerDataFile(UUID uuid) {
        return new File(playerDataFolder, uuid + ".json");
    }

    /** Legacy YAML location used for one-way migration into JSON player data files. */
    public File getLegacyPlayerDataFile(UUID uuid) {
        return new File(playerDataFolder, uuid + ".yml");
    }

    public File getPartyDataFile() {
        return partyDataFile;
    }

    public Path archiveFileIfExists(File sourceFile, String archiveRelativePath, String priorVersionTag) {
        if (sourceFile == null) {
            return null;
        }
        return archivePathIfExists(sourceFile.toPath(), archiveRelativePath, priorVersionTag);
    }

    public Path archivePathIfExists(Path sourcePath, String archiveRelativePath, String priorVersionTag) {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }
        synchronized (archiveLock) {
            Path archiveRoot = getOrCreateArchiveSession();
            Path targetPath = archiveRelativePath == null || archiveRelativePath.isBlank()
                    ? archiveRoot.resolve(sourcePath.getFileName().toString())
                    : archiveRoot.resolve(archiveRelativePath);
            try {
                copyRecursively(sourcePath, targetPath);
                appendArchiveIndexLine(archiveRoot, sourcePath, targetPath, priorVersionTag);
                LOGGER.atInfo().log("Archived %s to %s", sourcePath, targetPath);
                return targetPath;
            } catch (IOException e) {
                LOGGER.atWarning().log("Failed to archive %s: %s", sourcePath, e.getMessage());
                return null;
            }
        }
    }

    private Path getOrCreateArchiveSession() {
        if (currentArchiveSession != null) {
            return currentArchiveSession;
        }
        String timestamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP_FORMAT);
        String pluginVersion = sanitizeForPath(resolvePluginVersion());
        if (pluginVersion.isBlank()) {
            pluginVersion = "unknown";
        }
        Path sessionPath = pluginFolder.toPath()
                .resolve(OLD_FOLDER_NAME)
                .resolve(timestamp + "_v" + pluginVersion);
        try {
            Files.createDirectories(sessionPath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create centralized backup folder", e);
        }
        currentArchiveSession = sessionPath;
        return currentArchiveSession;
    }

    private void appendArchiveIndexLine(Path archiveRoot, Path sourcePath, Path targetPath, String priorVersionTag)
            throws IOException {
        String versionText = (priorVersionTag == null || priorVersionTag.isBlank()) ? "unknown" : priorVersionTag;
        Path indexPath = archiveRoot.resolve("index.txt");
        String line = String.format("%s | from=%s | prior=%s%n", targetPath.getFileName(), sourcePath, versionText);
        Files.writeString(indexPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void copyRecursively(Path sourcePath, Path destinationPath) throws IOException {
        if (Files.isDirectory(sourcePath)) {
            try (Stream<Path> stream = Files.walk(sourcePath)) {
                stream.forEach(path -> {
                    Path relative = sourcePath.relativize(path);
                    Path target = destinationPath.resolve(relative.toString());
                    try {
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(target);
                        } else {
                            Path parent = target.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            return;
        }

        Path parent = destinationPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void exportResourceFile(String resourcePath, Path targetPath, boolean overwriteExisting) {
        if (resourcePath == null || resourcePath.isBlank() || targetPath == null) {
            return;
        }
        try {
            if (!overwriteExisting && Files.exists(targetPath)) {
                return;
            }
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = plugin.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.atWarning().log("Bundled resource %s not found; skipping", resourcePath);
                    return;
                }
                if (overwriteExisting) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(in, targetPath);
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to export bundled resource %s: %s", resourcePath, e.getMessage());
        }
    }

    private void mergeBundledJsonDefaults(String resourcePath, Path targetPath) {
        try {
            Map<String, Object> bundled = readBundledJsonAsMap(resourcePath);
            if (bundled == null) {
                return;
            }

            if (!Files.exists(targetPath)) {
                writeJsonMap(targetPath, bundled);
                return;
            }

            Map<String, Object> current = readJsonFileAsMap(targetPath);
            if (current == null) {
                // Invalid JSON on disk: keep behavior predictable by restoring bundled template.
                writeJsonMap(targetPath, bundled);
                return;
            }

            Map<String, Object> merged = deepMergeDefaultsWithCurrent(bundled, current);
            if (!Objects.equals(current, merged)) {
                writeJsonMap(targetPath, merged);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to migrate built-in world-settings file %s: %s", targetPath,
                    e.getMessage());
        }
    }

    private Map<String, Object> readBundledJsonAsMap(String resourcePath) {
        try (InputStream in = plugin.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.atWarning().log("Bundled world-settings resource missing: %s", resourcePath);
                return null;
            }
            try (Reader reader = new InputStreamReader(in)) {
                return GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Object>>() {
                }.getType());
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read bundled world-settings resource %s: %s", resourcePath,
                    e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readJsonFileAsMap(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            return GSON.fromJson(reader, new TypeToken<LinkedHashMap<String, Object>>() {
            }.getType());
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to parse JSON file %s: %s", path, e.getMessage());
            return null;
        }
    }

    private void writeJsonMap(Path targetPath, Map<String, Object> content) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String output = GSON.toJson(content);
        if (!output.endsWith("\n")) {
            output = output + "\n";
        }
        Files.writeString(targetPath, output);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMergeDefaultsWithCurrent(Map<String, Object> defaults, Map<String, Object> current) {
        Map<String, Object> merged = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            Object currentValue = current.get(key);

            if (defaultValue instanceof Map<?, ?> defaultMap && currentValue instanceof Map<?, ?> currentMap) {
                merged.put(key, deepMergeDefaultsWithCurrent((Map<String, Object>) defaultMap,
                        (Map<String, Object>) currentMap));
            } else if (current.containsKey(key)) {
                merged.put(key, currentValue);
            } else {
                merged.put(key, defaultValue);
            }
        }

        for (Map.Entry<String, Object> entry : current.entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged;
    }

    private int readWorldSettingsVersion(File folder) {
        Path versionPath = folder.toPath().resolve(VersionRegistry.WORLD_SETTINGS_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            return Integer.parseInt(Files.readString(versionPath).trim());
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read world-settings version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeWorldSettingsVersion(File folder, int version) {
        Path versionPath = folder.toPath().resolve(VersionRegistry.WORLD_SETTINGS_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write world-settings version file: %s", e.getMessage());
        }
    }

    private boolean parseBoolean(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private String resolvePluginVersion() {
        try (InputStream in = plugin.getClassLoader().getResourceAsStream("manifest.json")) {
            if (in != null) {
                String json = new String(in.readAllBytes());
                Matcher matcher = MANIFEST_VERSION_PATTERN.matcher(json);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        } catch (IOException ignored) {
        }

        Package pkg = plugin.getClass().getPackage();
        if (pkg != null) {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion.trim();
            }
        }
        return "unknown";
    }

    private String sanitizeForPath(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public void exportResourceDirectory(String resourceRoot, File destination, boolean overwriteExisting) {
        try {
            Files.createDirectories(destination.toPath());
            CodeSource codeSource = plugin.getClass().getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                LOGGER.atWarning().log("Unable to locate code source while exporting %s", resourceRoot);
                return;
            }

            Path sourcePath = Paths.get(codeSource.getLocation().toURI());
            if (Files.isDirectory(sourcePath)) {
                Path resourcePath = sourcePath.resolve(resourceRoot);
                if (!Files.exists(resourcePath)) {
                    LOGGER.atWarning().log("Resource directory %s not found under %s", resourceRoot,
                            sourcePath);
                    return;
                }
                copyDirectory(resourcePath, destination.toPath(), overwriteExisting);
                return;
            }

            try (InputStream fileInput = Files.newInputStream(sourcePath);
                    JarInputStream jarStream = new JarInputStream(fileInput)) {
                String prefix = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
                JarEntry entry;
                while ((entry = jarStream.getNextJarEntry()) != null) {
                    try {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (!name.startsWith(prefix)) {
                            continue;
                        }

                        Path relativePath = Paths.get(name.substring(prefix.length()));
                        Path targetPath = destination.toPath().resolve(relativePath.toString());
                        if (!overwriteExisting && Files.exists(targetPath)) {
                            continue;
                        }

                        Path parent = targetPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }

                        if (overwriteExisting) {
                            Files.copy(jarStream, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.copy(jarStream, targetPath);
                        }
                    } finally {
                        jarStream.closeEntry();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to export resource directory %s: %s", resourceRoot, e.getMessage());
        }
    }

    private void seedResourceDirectoryIfEmpty(String resourceRoot, File destination) {
        if (destination == null) {
            return;
        }
        if (hasJsonFiles(destination.toPath())) {
            return;
        }
        exportResourceDirectory(resourceRoot, destination, false);
    }

    private boolean hasJsonFiles(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.toString().toLowerCase())
                    .anyMatch(path -> path.endsWith(".json"));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to inspect %s for JSON files: %s", folder, e.getMessage());
            return false;
        }
    }

    private void copyDirectory(Path source, Path destination, boolean overwriteExisting) throws IOException {
        if (!Files.exists(source)) {
            LOGGER.atWarning().log("Source directory %s does not exist when exporting resources.", source);
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(path -> copyFile(path, source, destination, overwriteExisting));
        }
    }

    private void copyFile(Path file, Path sourceRoot, Path destinationRoot, boolean overwriteExisting) {
        Path relative = sourceRoot.relativize(file);
        Path target = destinationRoot.resolve(relative.toString());
        if (!overwriteExisting && Files.exists(target)) {
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (overwriteExisting) {
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(file, target);
            }
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to copy resource %s: %s", relative, e.getMessage());
        }
    }

    /**
     * Initialize a default config file from plugin resources if it doesn't exist.
     */
    public File initYamlFile(String resourceName) {
        File yamlFile = new File(pluginFolder, resourceName);

        if (!yamlFile.exists()) {
            try (InputStream in = plugin.getClassLoader().getResourceAsStream(resourceName)) {
                if (in == null) {
                    throw new FileNotFoundException("Resource " + resourceName + " not found in plugin JAR!");
                }
                try (OutputStream out = new FileOutputStream(yamlFile)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }

                ensureConfigVersionMarkerOnCreate(resourceName, yamlFile);
                LOGGER.atInfo().log("YAML file %s created at %s", resourceName, yamlFile.getAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create YAML file: " + resourceName, e);
            }
        } else {
            LOGGER.atFine().log("YAML file %s already exists.", resourceName);
        }

        return yamlFile;
    }

    /**
     * Initialize a default resource file from plugin resources if it doesn't exist.
     */
    public File initResourceFile(String resourceName) {
        File file = new File(pluginFolder, resourceName);
        if (!file.exists()) {
            try (InputStream in = plugin.getClassLoader().getResourceAsStream(resourceName)) {
                if (in == null) {
                    throw new FileNotFoundException("Resource " + resourceName + " not found in plugin JAR!");
                }
                try (OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }

                LOGGER.atInfo().log("Resource file %s created at %s", resourceName, file.getAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create resource file: " + resourceName, e);
            }
        } else {
            LOGGER.atFine().log("Resource file %s already exists.", resourceName);
        }

        return file;
    }

    private void ensureConfigVersionMarkerOnCreate(String resourceName, File yamlFile) throws IOException {
        if (resourceName == null || yamlFile == null || !yamlFile.exists()) {
            return;
        }

        Integer version = VersionRegistry.getResourceConfigVersion(resourceName);
        if (version == null) {
            return;
        }

        String content = Files.readString(yamlFile.toPath());
        if (content.contains("config_version:")) {
            return;
        }

        String lineBreak = content.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder suffix = new StringBuilder();
        if (!content.endsWith("\n") && !content.endsWith("\r")) {
            suffix.append(lineBreak);
        }
        suffix.append(lineBreak)
                .append("# DON'T EDIT THIS LINE BELOW")
                .append(lineBreak)
                .append("config_version: ")
                .append(version)
                .append(lineBreak);

        Files.writeString(yamlFile.toPath(), suffix.toString(), StandardOpenOption.APPEND);
    }

    private File initPartyDataFile() {
        File jsonFile = new File(partyDataFolder, PARTYDATA_FILE_NAME);
        if (jsonFile.exists()) {
            LOGGER.atFine().log("Party data file already exists at %s", jsonFile.getAbsolutePath());
            return jsonFile;
        }

        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write("{\n  \"parties\": []\n}\n");
            LOGGER.atInfo().log("Party data file created at %s", jsonFile.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create party data file", e);
        }
        return jsonFile;
    }
}
