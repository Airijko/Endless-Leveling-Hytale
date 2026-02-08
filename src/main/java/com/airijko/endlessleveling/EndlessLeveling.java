package com.airijko.endlessleveling;

import com.airijko.endlessleveling.commands.EndlessLevelingCommand;
import com.airijko.endlessleveling.commands.PartyCommand;
import com.airijko.endlessleveling.commands.RaceCommand;
import com.airijko.endlessleveling.commands.profile.ProfileCommand;
import com.airijko.endlessleveling.listeners.LuckDoubleDropSystem;
import com.airijko.endlessleveling.listeners.OpenPlayerHudListener;
import com.airijko.endlessleveling.listeners.PartyListener;
import com.airijko.endlessleveling.listeners.PlayerCombatListener;
import com.airijko.endlessleveling.listeners.PlayerDataListener;
import com.airijko.endlessleveling.listeners.PlayerDefenseListener;
import com.airijko.endlessleveling.listeners.XpEventListener;
import com.airijko.endlessleveling.listeners.BreakBlockEntitySystem;
import com.airijko.endlessleveling.managers.*;
import com.airijko.endlessleveling.passives.ArchetypePassiveManager;
import com.airijko.endlessleveling.systems.PassiveRegenSystem;
import com.airijko.endlessleveling.systems.MobNameplateSystem;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
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
    private MobLevelingManager mobLevelingManager;
    private SkillManager skillManager;
    private PassiveManager passiveManager;
    private PartyManager partyManager;
    private RaceManager raceManager;
    private ArchetypePassiveManager archetypePassiveManager;
    private PlayerAttributeManager playerAttributeManager;
    private PlayerRaceStatSystem playerRaceStatSystem;

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

    public MobLevelingManager getMobLevelingManager() {
        return mobLevelingManager;
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

    public RaceManager getRaceManager() {
        return raceManager;
    }

    public PlayerAttributeManager getPlayerAttributeManager() {
        return playerAttributeManager;
    }

    public PlayerRaceStatSystem getPlayerRaceStatSystem() {
        return playerRaceStatSystem;
    }

    public ArchetypePassiveManager getArchetypePassiveManager() {
        return archetypePassiveManager;
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

        raceManager = new RaceManager(configManager, filesManager);
        archetypePassiveManager = new ArchetypePassiveManager(raceManager);
        playerAttributeManager = new PlayerAttributeManager(raceManager);
        skillManager = new SkillManager(filesManager, playerAttributeManager);
        passiveManager = new PassiveManager(configManager);
        playerDataManager = new PlayerDataManager(filesManager, skillManager, raceManager);
        levelingManager = new LevelingManager(playerDataManager, filesManager, skillManager, passiveManager,
                archetypePassiveManager);
        mobLevelingManager = new MobLevelingManager(filesManager, playerDataManager);
        partyManager = new PartyManager(playerDataManager, levelingManager, filesManager);

        // Register event listeners
        PlayerDataListener playerDataListener = new PlayerDataListener(playerDataManager, passiveManager, skillManager);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, playerDataListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, playerDataListener::onPlayerDisconnect);

        PartyListener partyListener = new PartyListener(partyManager);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, partyListener::onPlayerDisconnect);

        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, OpenPlayerHudListener::openGui);
        LuckDoubleDropSystem luckDoubleDropSystem = new LuckDoubleDropSystem(playerDataManager, passiveManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class,
                luckDoubleDropSystem::onInventoryChange);
        this.getEntityStoreRegistry().registerSystem(new BreakBlockEntitySystem(luckDoubleDropSystem));
        this.getEntityStoreRegistry()
                .registerSystem(new XpEventListener(playerDataManager, levelingManager, partyManager, passiveManager,
                        mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerCombatListener(playerDataManager, skillManager, passiveManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerDefenseListener(playerDataManager, skillManager, passiveManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PassiveRegenSystem(playerDataManager, passiveManager, archetypePassiveManager));
        playerRaceStatSystem = new PlayerRaceStatSystem(playerDataManager, skillManager);
        this.getEntityStoreRegistry().registerSystem(playerRaceStatSystem);
        this.getEntityStoreRegistry().registerSystem(new MobNameplateSystem());
        this.getEntityStoreRegistry().registerSystem(new com.airijko.endlessleveling.systems.MobHealthModifierSystem());
        this.getEntityStoreRegistry()
                .registerSystem(new com.airijko.endlessleveling.systems.MobDamageScalingSystem(mobLevelingManager));

        // Register commands
        this.getCommandRegistry().registerCommand(new EndlessLevelingCommand("skills", "Skills menu"));
        this.getCommandRegistry().registerCommand(new ProfileCommand());
        this.getCommandRegistry().registerCommand(new PartyCommand());
        this.getCommandRegistry().registerCommand(new RaceCommand(raceManager, playerDataManager));

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
