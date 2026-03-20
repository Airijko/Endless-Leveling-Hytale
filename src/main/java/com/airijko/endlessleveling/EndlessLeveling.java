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
import com.airijko.endlessleveling.augments.AugmentSyncValidator;
import com.airijko.endlessleveling.augments.MobAugmentExecutor;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.augments.AugmentUnlockManager;
import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.listeners.OpenPlayerHudListener;
import com.airijko.endlessleveling.listeners.PartyListener;
import com.airijko.endlessleveling.listeners.PlayerDataListener;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.EventHookManager;
import com.airijko.endlessleveling.managers.LanguageManager;
import com.airijko.endlessleveling.managers.LoggingManager;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.managers.VersionRegistry;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.leveling.MobLevelingManager;
import com.airijko.endlessleveling.leveling.PartyManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.PlayerDataMigration;
import com.airijko.endlessleveling.player.PlayerAttributeManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.races.RaceManager;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.security.PartnerBrandingAllowlist;
import com.airijko.endlessleveling.security.UiTitleIntegrityGuard;
import com.airijko.endlessleveling.systems.BreakBlockEntitySystem;
import com.airijko.endlessleveling.systems.ArmyOfTheDeadDeathSystem;
import com.airijko.endlessleveling.drops.MobDropTaggingSystem;
import com.airijko.endlessleveling.systems.PassiveRegenSystem;
import com.airijko.endlessleveling.mob.MobDamageScalingSystem;
import com.airijko.endlessleveling.mob.MobLevelingSystem;
import com.airijko.endlessleveling.drops.LuckDoubleDropSystem;
import com.airijko.endlessleveling.systems.PlayerCombatPostApplyProbeSystem;
import com.airijko.endlessleveling.systems.PlayerCombatSystem;
import com.airijko.endlessleveling.systems.PlayerDefenseSystem;
import com.airijko.endlessleveling.systems.PlayerNameplateSystem;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.systems.PeriodicSkillModifierSystem;
import com.airijko.endlessleveling.systems.SwiftnessKillSystem;
import com.airijko.endlessleveling.systems.HudRefreshSystem;
import com.airijko.endlessleveling.systems.UiIntegrityAlertSystem;
import com.airijko.endlessleveling.systems.WitherEffectSystem;
import com.airijko.endlessleveling.leveling.XpEventSystem;
import com.airijko.endlessleveling.util.FixedValue;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.security.CodeSource;
import java.util.Locale;

public class EndlessLeveling extends JavaPlugin {

    private static EndlessLeveling INSTANCE;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String DEFAULT_BRAND_NAME = "Endless Leveling";
    public static final String DEFAULT_COMMAND_PREFIX = "/lvl";
    public static final String DEFAULT_MESSAGE_PREFIX = "[EndlessLeveling] ";
    private static final String PARTNER_ADDON_MAIN_CLASS = "com.airijko.endlessleveling.EndlessLevelingPartnerAddon";

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
    private EventHookManager eventHookManager;
    private PartyManager partyManager;
    private RaceManager raceManager;
    private ClassManager classManager;
    private ArchetypePassiveManager archetypePassiveManager;
    private PlayerAttributeManager playerAttributeManager;
    private PlayerRaceStatSystem playerRaceStatSystem;
    private MobLevelingSystem mobLevelingSystem;
    private AugmentManager augmentManager;
    private AugmentRuntimeManager augmentRuntimeManager;
    private AugmentUnlockManager augmentUnlockManager;
    private AugmentSyncValidator augmentSyncValidator;
    private AugmentExecutor augmentExecutor;
    private MobAugmentExecutor mobAugmentExecutor;
    private UiTitleIntegrityGuard uiTitleIntegrityGuard;
    private volatile String brandName = DEFAULT_BRAND_NAME;
    private volatile String commandPrefix = DEFAULT_COMMAND_PREFIX;
    private volatile String messagePrefix = DEFAULT_MESSAGE_PREFIX;
    private volatile String navHeaderOverride;
    private volatile String navSubHeaderOverride;
    private volatile boolean partnerAddonAuthorized;
    private volatile boolean warnedUnauthorizedBrandingOverride;

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

    public EventHookManager getEventHookManager() {
        return eventHookManager;
    }

    public PartyManager getPartyManager() {
        return partyManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PluginFilesManager getFilesManager() {
        return filesManager;
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

    public MobLevelingSystem getMobLevelingSystem() {
        return mobLevelingSystem;
    }

    public AugmentRuntimeManager getAugmentRuntimeManager() {
        return augmentRuntimeManager;
    }

    public AugmentExecutor getAugmentExecutor() {
        return augmentExecutor;
    }

    public MobAugmentExecutor getMobAugmentExecutor() {
        return mobAugmentExecutor;
    }

    public AugmentManager getAugmentManager() {
        return augmentManager;
    }

    public AugmentUnlockManager getAugmentUnlockManager() {
        return augmentUnlockManager;
    }

    public AugmentSyncValidator getAugmentSyncValidator() {
        return augmentSyncValidator;
    }

    public String getBrandName() {
        return brandName;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public String getMessagePrefix() {
        return messagePrefix;
    }

    public String getNavHeaderOverride() {
        return navHeaderOverride;
    }

    public String getNavSubHeaderOverride() {
        return navSubHeaderOverride;
    }

    public boolean isPartnerAddonAuthorized() {
        return partnerAddonAuthorized;
    }

    synchronized void applyPartnerBrandingInternal(String requestedBrandName,
            String requestedCommandPrefix,
            String requestedMessagePrefix,
            String declaredServerHostsCsv,
            String requestedNavHeaderOverride,
            String requestedNavSubHeaderOverride) {
        String normalizedBrandName = normalizeBrandName(requestedBrandName);
        String normalizedCommandPrefix = normalizeCommandPrefix(requestedCommandPrefix);
        String normalizedMessagePrefix = normalizeMessagePrefix(requestedMessagePrefix);
        String normalizedNavHeaderOverride = normalizeOptionalNavText(requestedNavHeaderOverride);
        String normalizedNavSubHeaderOverride = normalizeOptionalNavText(requestedNavSubHeaderOverride);
        var declaredServerHosts = PartnerBrandingAllowlist.parseDeclaredHostsCsv(declaredServerHostsCsv);
        boolean premiumAddonAvailable = isPremiumPartnerAddonAvailable();
        boolean partnerDomainAuthorized = PartnerBrandingAllowlist.hasAuthorizedHost(declaredServerHosts);
        partnerAddonAuthorized = premiumAddonAvailable && partnerDomainAuthorized;

        boolean overrideRequest = !DEFAULT_BRAND_NAME.equals(normalizedBrandName)
                || !DEFAULT_COMMAND_PREFIX.equals(normalizedCommandPrefix)
                || !DEFAULT_MESSAGE_PREFIX.equals(normalizedMessagePrefix);
        if (overrideRequest && !premiumAddonAvailable) {
            if (!warnedUnauthorizedBrandingOverride) {
                warnedUnauthorizedBrandingOverride = true;
                LOGGER.atWarning().log(
                        "Rejected branding override because EL-Partner-Addon is not available. Keeping core branding defaults.");
            }
            return;
        }
        if (overrideRequest && !partnerDomainAuthorized) {
            if (!warnedUnauthorizedBrandingOverride) {
                warnedUnauthorizedBrandingOverride = true;
                LOGGER.atWarning().log(
                        "Rejected branding override because declared partner server host is not in core allowlist.");
            }
            return;
        }

        brandName = normalizedBrandName;
        commandPrefix = normalizedCommandPrefix;
        messagePrefix = normalizedMessagePrefix;
        navHeaderOverride = normalizedNavHeaderOverride;
        navSubHeaderOverride = normalizedNavSubHeaderOverride;

        FixedValue.ROOT_COMMAND.setValue(normalizedCommandPrefix);
        FixedValue.CHAT_PREFIX.setValue(normalizedMessagePrefix);
        if (uiTitleIntegrityGuard != null) {
            uiTitleIntegrityGuard.updateBranding(normalizedBrandName, normalizedMessagePrefix);
        }
    }

    private boolean isPremiumPartnerAddonAvailable() {
        try {
            Class<?> addonClass = Class.forName(PARTNER_ADDON_MAIN_CLASS);
            CodeSource codeSource = addonClass.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return true;
            }

            String location = codeSource.getLocation().toString().toLowerCase(Locale.ROOT);
            return location.contains("endlesslevelingpartneraddon")
                    || location.contains("endlesslevelingaddon")
                    || location.contains("el-partner-addon");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String normalizeBrandName(String requestedBrandName) {
        if (requestedBrandName == null) {
            return DEFAULT_BRAND_NAME;
        }
        String normalized = requestedBrandName.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank() || normalized.length() > 48) {
            return DEFAULT_BRAND_NAME;
        }
        return normalized;
    }

    private String normalizeCommandPrefix(String requestedCommandPrefix) {
        if (requestedCommandPrefix == null) {
            return DEFAULT_COMMAND_PREFIX;
        }
        String normalized = requestedCommandPrefix.trim();
        if (normalized.isBlank()) {
            return DEFAULT_COMMAND_PREFIX;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (!normalized.matches("/[A-Za-z][A-Za-z0-9_-]{0,31}")) {
            return DEFAULT_COMMAND_PREFIX;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeMessagePrefix(String requestedMessagePrefix) {
        if (requestedMessagePrefix == null) {
            return DEFAULT_MESSAGE_PREFIX;
        }
        String normalized = requestedMessagePrefix.trim();
        if (normalized.isBlank() || normalized.length() > 64) {
            return DEFAULT_MESSAGE_PREFIX;
        }
        if (!normalized.endsWith(" ")) {
            normalized = normalized + " ";
        }
        return normalized;
    }

    private String normalizeOptionalNavText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48).trim();
        }
        return normalized;
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
        configManager = new ConfigManager(filesManager, filesManager.getConfigFile());
        new ConfigManager(filesManager, filesManager.getWeaponsFile());
        languageManager = new LanguageManager(filesManager, configManager);

        // Load weapon ID and keyword overrides before systems start.
        ClassWeaponResolver.configure(WeaponConfig.load(filesManager.getWeaponsFile()));

        LoggingManager.configureFromConfig(configManager);

        raceManager = new RaceManager(configManager, filesManager);
        classManager = new ClassManager(configManager, filesManager);
        archetypePassiveManager = new ArchetypePassiveManager(raceManager, classManager);
        playerAttributeManager = new PlayerAttributeManager(raceManager);
        passiveManager = new PassiveManager(configManager);
        eventHookManager = new EventHookManager(new ConfigManager(filesManager, filesManager.getEventsFile()));
        augmentManager = new AugmentManager(filesManager.getAugmentsFolder().toPath(), filesManager, configManager);
        augmentRuntimeManager = new AugmentRuntimeManager();
        mobAugmentExecutor = new MobAugmentExecutor();
        uiTitleIntegrityGuard = new UiTitleIntegrityGuard();
        applyPartnerBrandingInternal(DEFAULT_BRAND_NAME, DEFAULT_COMMAND_PREFIX, DEFAULT_MESSAGE_PREFIX, null,
            null, null);
        skillManager = new SkillManager(filesManager,
                classManager,
                playerAttributeManager,
                archetypePassiveManager,
                passiveManager,
                augmentRuntimeManager);
        augmentExecutor = new AugmentExecutor(augmentManager, augmentRuntimeManager, skillManager);
        playerDataManager = new PlayerDataManager(filesManager, configManager, skillManager, raceManager,
                classManager);
        ConfigManager levelingConfigManager = new ConfigManager(filesManager, filesManager.getLevelingFile());
        augmentUnlockManager = new AugmentUnlockManager(configManager, levelingConfigManager, augmentManager,
                playerDataManager,
                archetypePassiveManager);
        augmentSyncValidator = new AugmentSyncValidator(playerDataManager, augmentUnlockManager);
        levelingManager = new LevelingManager(playerDataManager, filesManager, skillManager, archetypePassiveManager,
                passiveManager, augmentUnlockManager, eventHookManager);
        mobLevelingManager = new MobLevelingManager(filesManager, playerDataManager);
        partyManager = new PartyManager(playerDataManager, levelingManager);
        if (!partyManager.isAvailable()) {
            LOGGER.atWarning().log("PartyPro not detected; party features will stay disabled.");
        }

        if (NameplateBuilderCompatibility.isAvailable()) {
            boolean described = NameplateBuilderCompatibility.describeMobLevelSegment(this);
            if (described) {
                LOGGER.atInfo().log("NameplateBuilder detected; registered mob_level segment.");
            } else {
                LOGGER.atWarning().log("NameplateBuilder detected but mob_level segment registration failed.");
            }

            boolean playerDescribed = NameplateBuilderCompatibility.describePlayerLevelSegment(this);
            if (playerDescribed) {
                LOGGER.atInfo().log("NameplateBuilder detected; registered player_level segment.");
            } else {
                LOGGER.atWarning().log(
                        "NameplateBuilder detected but player_level segment registration failed (falling back to native nameplate text).");
            }

            boolean summonDescribed = NameplateBuilderCompatibility.describeSummonLabelSegment(this);
            if (summonDescribed) {
                LOGGER.atInfo().log("NameplateBuilder detected; registered summon_label segment.");
            } else {
                LOGGER.atWarning().log(
                        "NameplateBuilder detected but summon_label segment registration failed (falling back to native summon nameplate text).");
            }
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
        this.getEntityStoreRegistry().registerSystem(new MobDropTaggingSystem(luckDoubleDropSystem));
        this.getEntityStoreRegistry()
                .registerSystem(new XpEventSystem(playerDataManager, levelingManager, partyManager, passiveManager,
                        mobLevelingManager, archetypePassiveManager, luckDoubleDropSystem));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerCombatSystem(playerDataManager, skillManager, passiveManager,
                        archetypePassiveManager, classManager, augmentExecutor, mobAugmentExecutor,
                        mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerCombatPostApplyProbeSystem(mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new SwiftnessKillSystem(playerDataManager, passiveManager, archetypePassiveManager,
                        skillManager, augmentExecutor));
        this.getEntityStoreRegistry().registerSystem(new ArmyOfTheDeadDeathSystem());
        this.getEntityStoreRegistry().registerSystem(new MobDamageScalingSystem(mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PlayerDefenseSystem(playerDataManager, skillManager, passiveManager,
                        archetypePassiveManager, augmentExecutor, mobAugmentExecutor, mobLevelingManager));
        this.getEntityStoreRegistry()
                .registerSystem(new PassiveRegenSystem(playerDataManager, passiveManager, archetypePassiveManager,
                        skillManager, augmentRuntimeManager, augmentExecutor));
        // Register periodic skill modifier reapplication system
        this.getEntityStoreRegistry().registerSystem(new PeriodicSkillModifierSystem(playerDataManager, skillManager));
        playerRaceStatSystem = new PlayerRaceStatSystem(playerDataManager, skillManager);
        this.getEntityStoreRegistry().registerSystem(playerRaceStatSystem);
        this.getEntityStoreRegistry().registerSystem(new PlayerNameplateSystem(playerDataManager));
        mobLevelingSystem = new MobLevelingSystem();
        this.getEntityStoreRegistry().registerSystem(mobLevelingSystem);
        this.getEntityStoreRegistry().registerSystem(new HudRefreshSystem());
        this.getEntityStoreRegistry().registerSystem(new UiIntegrityAlertSystem(uiTitleIntegrityGuard));
        this.getEntityStoreRegistry().registerSystem(new WitherEffectSystem());

        // Register commands
        this.getCommandRegistry().registerCommand(new EndlessLevelingCommand());
        this.getCommandRegistry().registerCommand(new ProfileCommand());
        if (partyManager.isAvailable()) {
            this.getCommandRegistry().registerCommand(new PartyCommand());
        }
        this.getCommandRegistry().registerCommand(new RaceCommand(raceManager, playerDataManager));
        this.getCommandRegistry().registerCommand(new ClassCommand(classManager, playerDataManager));

        if (augmentManager != null) {
            augmentManager.load();
        }

        if (augmentSyncValidator != null) {
            augmentSyncValidator.auditAndNotify();
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
