package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class PluginFilesManager {

    private static final String PLUGIN_FOLDER_NAME = "EndlessLeveling";
    private static final String PLAYERDATA_FOLDER_NAME = "playerdata";
    private static final String PARTYDATA_FOLDER_NAME = "partydata";
    private static final String PARTYDATA_FILE_NAME = "parties.json";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final JavaPlugin plugin;
    private final File pluginFolder;
    private final File playerDataFolder;
    private final File partyDataFolder;

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

        createFolders();

        this.configFile = initYamlFile("config.yml");
        this.levelingFile = initYamlFile("leveling.yml");
        this.partyDataFile = initPartyDataFile();
    }

    /** Create the plugin folder and player data folder */
    private void createFolders() {
        try {
            Files.createDirectories(pluginFolder.toPath());
            Files.createDirectories(playerDataFolder.toPath());
            Files.createDirectories(partyDataFolder.toPath());
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
