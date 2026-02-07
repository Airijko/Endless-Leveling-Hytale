package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

public class PluginFilesManager {

    private static final String PLUGIN_FOLDER_NAME = "EndlessLeveling";
    private static final String PLAYERDATA_FOLDER_NAME = "playerdata";
    private static final String PARTYDATA_FOLDER_NAME = "partydata";
    private static final String RACES_FOLDER_NAME = "races";
    private static final String PARTYDATA_FILE_NAME = "parties.json";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final JavaPlugin plugin;
    private final File pluginFolder;
    private final File playerDataFolder;
    private final File partyDataFolder;
    private final File racesFolder;

    private final File configFile;
    private final File levelingFile;
    private final File partyDataFile;

    public PluginFilesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        Path modsPath = PluginManager.MODS_PATH;
        if (modsPath == null) {
            throw new IllegalStateException("Mods path is not initialized for EndlessLeveling");
        }
        this.pluginFolder = modsPath.resolve(PLUGIN_FOLDER_NAME).toFile();
        this.playerDataFolder = new File(pluginFolder, PLAYERDATA_FOLDER_NAME);
        this.partyDataFolder = new File(pluginFolder, PARTYDATA_FOLDER_NAME);
        this.racesFolder = new File(pluginFolder, RACES_FOLDER_NAME);

        createFolders();

        this.configFile = initYamlFile("config.yml");
        this.levelingFile = initYamlFile("leveling.yml");
        this.partyDataFile = initPartyDataFile();

        exportResourceDirectory("races", racesFolder);
    }

    /** Create the plugin folder and player data folder */
    private void createFolders() {
        try {
            Files.createDirectories(pluginFolder.toPath());
            Files.createDirectories(playerDataFolder.toPath());
            Files.createDirectories(partyDataFolder.toPath());
            Files.createDirectories(racesFolder.toPath());
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

    public File getPartyDataFolder() {
        return partyDataFolder;
    }

    public File getRacesFolder() {
        return racesFolder;
    }

    public File getConfigFile() {
        return configFile;
    }

    public File getLevelingFile() {
        return levelingFile;
    }

    /** Convenience method for player data file */
    public File getPlayerDataFile(UUID uuid) {
        return new File(playerDataFolder, uuid + ".yml");
    }

    public File getPartyDataFile() {
        return partyDataFile;
    }

    private void exportResourceDirectory(String resourceRoot, File destination) {
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
                copyDirectory(resourcePath, destination.toPath());
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
                        if (Files.exists(targetPath)) {
                            continue;
                        }

                        Path parent = targetPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }

                        Files.copy(jarStream, targetPath);
                    } finally {
                        jarStream.closeEntry();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to export resource directory %s: %s", resourceRoot, e.getMessage());
        }
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            LOGGER.atWarning().log("Source directory %s does not exist when exporting resources.", source);
            return;
        }
        try (Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(path -> copyFile(path, source, destination));
        }
    }

    private void copyFile(Path file, Path sourceRoot, Path destinationRoot) {
        Path relative = sourceRoot.relativize(file);
        Path target = destinationRoot.resolve(relative.toString());
        if (Files.exists(target)) {
            return;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(file, target);
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
                LOGGER.atInfo().log("YAML file %s created at %s", resourceName, yamlFile.getAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create YAML file: " + resourceName, e);
            }
        } else {
            LOGGER.atFine().log("YAML file %s already exists.", resourceName);
        }

        return yamlFile;
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
