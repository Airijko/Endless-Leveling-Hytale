package com.airijko.endlessleveling;

import com.airijko.endlessleveling.commands.EndlessLevelingCommand;
import com.airijko.endlessleveling.commands.PartyCommand;
import com.airijko.endlessleveling.listeners.LuckDoubleDropSystem;
import com.airijko.endlessleveling.listeners.OpenPlayerHudListener;
import com.airijko.endlessleveling.listeners.PartyListener;
import com.airijko.endlessleveling.listeners.PlayerCombatListener;
import com.airijko.endlessleveling.listeners.PlayerDataListener;
import com.airijko.endlessleveling.listeners.PlayerDefenseListener;
import com.airijko.endlessleveling.listeners.XpEventListener;
import com.airijko.endlessleveling.managers.*;
import com.airijko.endlessleveling.systems.PassiveRegenSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

public class EndlessLeveling extends JavaPlugin {

    private static EndlessLeveling INSTANCE;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    // ------------------------
    // Shared managers (singleton)
    // ------------------------
    private PluginFilesManager filesManager;
    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private LevelingManager levelingManager;
    private SkillManager skillManager;
    private PassiveManager passiveManager;
    private PartyManager partyManager;

    // Getter for SkillManager
    public SkillManager getSkillManager() {
        return skillManager;
    }

    // Getter for PlayerDataManager
    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public LevelingManager getLevelingManager() {
        return levelingManager;
    }

    public PassiveManager getPassiveManager() {
        return passiveManager;
    }

    public PartyManager getPartyManager() {
        return partyManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** Singleton access to the mod instance */
    public static EndlessLeveling getInstance() {
        return INSTANCE;
    }

    public EndlessLeveling(@Nonnull JavaPluginInit init) {
        super(init);
        INSTANCE = this;
    }

    @Override
    protected void setup() {
        // Initialize all folders and managers
        filesManager = new PluginFilesManager(this);
        configManager = new ConfigManager(filesManager.getConfigFile());

        boolean enableLogging = toBoolean(configManager.get("enable_logging", Boolean.FALSE, false), false);
        LoggingManager.configure(enableLogging);

        skillManager = new SkillManager(filesManager);
        passiveManager = new PassiveManager(configManager);
        playerDataManager = new PlayerDataManager(filesManager, skillManager);
        levelingManager = new LevelingManager(playerDataManager, filesManager, skillManager, passiveManager);
        partyManager = new PartyManager(playerDataManager, levelingManager, filesManager);

        // Register event listeners
        PlayerDataListener playerDataListener = new PlayerDataListener(playerDataManager, passiveManager);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, playerDataListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, playerDataListener::onPlayerDisconnect);

        PartyListener partyListener = new PartyListener(partyManager);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, partyListener::onPlayerDisconnect);

        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, OpenPlayerHudListener::openGui);
        LuckDoubleDropSystem luckDoubleDropSystem = new LuckDoubleDropSystem(playerDataManager, passiveManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class,
                luckDoubleDropSystem::onInventoryChange);
        this.getEntityStoreRegistry()
                .registerSystem(new XpEventListener(playerDataManager, levelingManager, partyManager, passiveManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerCombatListener(playerDataManager, skillManager, passiveManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerDefenseListener(playerDataManager, skillManager, passiveManager));
        this.getEntityStoreRegistry().registerSystem(new PassiveRegenSystem(playerDataManager, passiveManager));

        // Register commands
        this.getCommandRegistry().registerCommand(new EndlessLevelingCommand("skills", "Skills menu"));
        this.getCommandRegistry().registerCommand(new PartyCommand());

        LOGGER.atInfo().log("Plugin initialized! Plugin folder: %s",
                filesManager.getPluginFolder().getAbsolutePath());
    }

    protected void shutdown() {
        if (playerDataManager != null) {
            playerDataManager.saveAll();
            LOGGER.atInfo().log("Server shutting down: all player data saved.");
        }
        if (partyManager != null) {
            partyManager.saveAllParties();
            LOGGER.atInfo().log("Server shutting down: all party data saved.");
        }
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        return defaultValue;
    }
}
