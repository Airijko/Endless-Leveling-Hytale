package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.LoggingManager;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public class ReloadCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.reload");

    private final ConfigManager configManager;
    private final LevelingManager levelingManager;
    private final MobLevelingManager mobLevelingManager;
    private final RaceManager raceManager;
    private final ClassManager classManager;
    private final SkillManager skillManager;
    private final AugmentManager augmentManager;

    public ReloadCommand() {
        super("reload", "Reload EndlessLeveling configs, races, and classes");
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.configManager = plugin != null ? plugin.getConfigManager() : null;
        this.levelingManager = plugin != null ? plugin.getLevelingManager() : null;
        this.mobLevelingManager = plugin != null ? plugin.getMobLevelingManager() : null;
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
        this.classManager = plugin != null ? plugin.getClassManager() : null;
        this.skillManager = plugin != null ? plugin.getSkillManager() : null;
        this.augmentManager = plugin != null ? plugin.getAugmentManager() : null;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        if (configManager != null) {
            configManager.load();
            LoggingManager.configureFromConfig(configManager);
        }

        if (levelingManager != null) {
            levelingManager.reloadConfig();
        }

        if (mobLevelingManager != null) {
            mobLevelingManager.reloadConfig();
        }

        if (skillManager != null) {
            skillManager.reload();
        }

        if (raceManager != null) {
            raceManager.reload();
        }

        if (classManager != null) {
            classManager.reload();
        }

        if (augmentManager != null) {
            augmentManager.load();
        }

        senderRef.sendMessage(
                Message.raw("EndlessLeveling configuration, classes, and races reloaded.").color("#6cff78"));

        // Refresh HUDs to reflect any mode/range changes.
        PlayerHud.refreshAll();
    }

}
