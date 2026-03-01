package com.airijko.endlessleveling;

import com.airijko.endlessleveling.classes.ClassWeaponResolver;
import com.airijko.endlessleveling.classes.WeaponConfig;
import com.airijko.endlessleveling.commands.EndlessLevelingCommand;
import com.airijko.endlessleveling.commands.PartyCommand;
import com.airijko.endlessleveling.commands.RaceCommand;
import com.airijko.endlessleveling.commands.classes.ClassCommand;
import com.airijko.endlessleveling.commands.profile.ProfileCommand;
import com.airijko.endlessleveling.augments.AugmentExecutor;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.listeners.LuckDoubleDropSystem;
import com.airijko.endlessleveling.listeners.OpenPlayerHudListener;
import com.airijko.endlessleveling.listeners.PartyListener;
import com.airijko.endlessleveling.listeners.PlayerCombatListener;
import com.airijko.endlessleveling.listeners.PlayerDataListener;
import com.airijko.endlessleveling.listeners.PlayerDefenseListener;
import com.airijko.endlessleveling.listeners.SwiftnessKillSystem;
import com.airijko.endlessleveling.listeners.XpEventListener;
import com.airijko.endlessleveling.listeners.BreakBlockEntitySystem;
import com.airijko.endlessleveling.managers.*;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.systems.PassiveRegenSystem;
import com.airijko.endlessleveling.systems.MobNameplateSystem;
import com.airijko.endlessleveling.systems.MobDamageScalingSystem;
import com.airijko.endlessleveling.systems.MobLevelingSystem;
import com.airijko.endlessleveling.systems.PlayerNameplateSystem;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.systems.PeriodicSkillModifierSystem;
import com.airijko.endlessleveling.systems.HudRefreshSystem;
import com.airijko.endlessleveling.systems.WitherEffectSystem;
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
    private LanguageManager languageManager;
    private PlayerDataManager playerDataManager;
    private LevelingManager levelingManager;
    private MobLevelingManager mobLevelingManager;
    private SkillManager skillManager;
    private PassiveManager passiveManager;
    private PartyManager partyManager;
    private RaceManager raceManager;
    private ClassManager classManager;
    private ArchetypePassiveManager archetypePassiveManager;
    private PlayerAttributeManager playerAttributeManager;
    private PlayerRaceStatSystem playerRaceStatSystem;
    private AugmentManager augmentManager;
    private AugmentRuntimeManager augmentRuntimeManager;
    private AugmentUnlockManager augmentUnlockManager;
    private AugmentExecutor augmentExecutor;

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

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public RaceManager getRaceManager() {
        return raceManager;
    }

    public ClassManager getClassManager() {
        return classManager;
    }

    public PlayerAttributeManager getPlayerAttributeManager() {
        return playerAttributeManager;
    }

    public PlayerRaceStatSystem getPlayerRaceStatSystem() {
        return playerRaceStatSystem;
    }

    public AugmentRuntimeManager getAugmentRuntimeManager() {
        return augmentRuntimeManager;
    }

    public AugmentExecutor getAugmentExecutor() {
        return augmentExecutor;
    }

    public AugmentManager getAugmentManager() {
        return augmentManager;
    }

    public AugmentUnlockManager getAugmentUnlockManager() {
        return augmentUnlockManager;
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
        languageManager = new LanguageManager(filesManager, configManager);

        // Load weapon ID and keyword overrides before systems start.
        ClassWeaponResolver.configure(WeaponConfig.load(filesManager.getWeaponsFile()));

        LoggingManager.configureFromConfig(configManager);

        raceManager = new RaceManager(configManager, filesManager);
        classManager = new ClassManager(configManager, filesManager);
        archetypePassiveManager = new ArchetypePassiveManager(raceManager, classManager);
        playerAttributeManager = new PlayerAttributeManager(raceManager);
        passiveManager = new PassiveManager(configManager);
        augmentManager = new AugmentManager(filesManager.getAugmentsFolder().toPath(), filesManager, configManager);
        augmentRuntimeManager = new AugmentRuntimeManager();
        skillManager = new SkillManager(filesManager, playerAttributeManager, archetypePassiveManager, passiveManager,
                augmentRuntimeManager);
        augmentExecutor = new AugmentExecutor(augmentManager, augmentRuntimeManager, skillManager);
        playerDataManager = new PlayerDataManager(filesManager, skillManager, raceManager, classManager);
        augmentUnlockManager = new AugmentUnlockManager(configManager, augmentManager, playerDataManager,
                archetypePassiveManager);
        levelingManager = new LevelingManager(playerDataManager, filesManager, skillManager, archetypePassiveManager,
                passiveManager, augmentUnlockManager);
        mobLevelingManager = new MobLevelingManager(filesManager, playerDataManager);
        partyManager = new PartyManager(playerDataManager, levelingManager);
        if (!partyManager.isAvailable()) {
            LOGGER.atWarning().log("PartyPro not detected; party features will stay disabled.");
        }

        // Register event listeners
        PlayerDataListener playerDataListener = new PlayerDataListener(playerDataManager, passiveManager, skillManager,
                raceManager, augmentUnlockManager);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, playerDataListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, playerDataListener::onPlayerDisconnect);

        if (partyManager.isAvailable()) {
            PartyListener partyListener = new PartyListener(partyManager);
            this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, partyListener::onPlayerDisconnect);
        }

        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, OpenPlayerHudListener::openGui);
        LuckDoubleDropSystem luckDoubleDropSystem = new LuckDoubleDropSystem(playerDataManager, passiveManager);
        this.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class,
                luckDoubleDropSystem::onInventoryChange);
        this.getEntityStoreRegistry().registerSystem(new BreakBlockEntitySystem(luckDoubleDropSystem));
        this.getEntityStoreRegistry()
                .registerSystem(new XpEventListener(playerDataManager, levelingManager, partyManager, passiveManager,
                        mobLevelingManager, archetypePassiveManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerCombatListener(playerDataManager, skillManager, passiveManager,
                        archetypePassiveManager, classManager, augmentExecutor, mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new SwiftnessKillSystem(playerDataManager, passiveManager, archetypePassiveManager,
                        skillManager, augmentExecutor));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerDefenseListener(playerDataManager, skillManager, passiveManager,
                        archetypePassiveManager, augmentExecutor));
        this.getEntityStoreRegistry()
                .registerSystem(new PassiveRegenSystem(playerDataManager, passiveManager, archetypePassiveManager,
                        skillManager, augmentRuntimeManager, augmentExecutor));
        // Register periodic skill modifier reapplication system
        this.getEntityStoreRegistry().registerSystem(new PeriodicSkillModifierSystem(playerDataManager, skillManager));
        playerRaceStatSystem = new PlayerRaceStatSystem(playerDataManager, skillManager);
        this.getEntityStoreRegistry().registerSystem(playerRaceStatSystem);
        this.getEntityStoreRegistry().registerSystem(new PlayerNameplateSystem(playerDataManager));
        this.getEntityStoreRegistry().registerSystem(new MobNameplateSystem());
        this.getEntityStoreRegistry().registerSystem(new HudRefreshSystem());
        this.getEntityStoreRegistry().registerSystem(new WitherEffectSystem());
        this.getEntityStoreRegistry().registerSystem(new MobLevelingSystem());
        this.getEntityStoreRegistry().registerSystem(new MobDamageScalingSystem(mobLevelingManager));

        // Register commands
        this.getCommandRegistry().registerCommand(new EndlessLevelingCommand("skills", "Skills menu"));
        this.getCommandRegistry().registerCommand(new ProfileCommand());
        if (partyManager.isAvailable()) {
            this.getCommandRegistry().registerCommand(new PartyCommand());
        }
        this.getCommandRegistry().registerCommand(new RaceCommand(raceManager, playerDataManager));
        this.getCommandRegistry().registerCommand(new ClassCommand(classManager, playerDataManager));

        if (augmentManager != null) {
            augmentManager.load();
        }

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

}
