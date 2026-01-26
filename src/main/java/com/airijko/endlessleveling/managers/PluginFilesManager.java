package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.io.*;
import java.util.UUID;

public class PluginFilesManager {

    private final File pluginFolder;
    private File playerDataFolder;

    private File configFile;
    private File levelingFile;

    public PluginFilesManager(File pluginFolder, JavaPlugin plugin) {
        this.pluginFolder = pluginFolder;
        createFolders();

        // Initialize YAML files
        this.configFile = initYamlFile("config.yml", plugin);
        this.levelingFile = initYamlFile("leveling.yml", plugin);
    }

    /** Create the plugin folder and player data folder */
    private void createFolders() {
        if (!pluginFolder.exists()) pluginFolder.mkdirs();

        playerDataFolder = new File(pluginFolder, "playerdata");
        if (!playerDataFolder.exists()) playerDataFolder.mkdirs();

        System.out.println("[Endless Leveling] Plugin folders initialized at: " + pluginFolder.getAbsolutePath());
    }

    /** Accessors */
    public File getPluginFolder() { return pluginFolder; }
    public File getPlayerDataFolder() { return playerDataFolder; }
    public File getConfigFile() { return configFile; }
    public File getLevelingFile() { return levelingFile; }

    /** Convenience method for player data file */
    public File getPlayerDataFile(UUID uuid) {
        return new File(playerDataFolder, uuid + ".yml");
    }

    /**
     * Initialize a default config file from plugin resources if it doesn't exist.
     */
    public File initYamlFile(String resourceName, JavaPlugin plugin) {
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
                System.out.println("[Endless Leveling] YAML file " + resourceName + " created at " + yamlFile.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("[Endless Leveling] YAML file " + resourceName + " already exists.");
        }

        return yamlFile;
    }
}
