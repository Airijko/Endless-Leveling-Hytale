package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.managers.PassiveManager;
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

/**
 * /skills resetcooldowns - admin utility that clears passive + augment cooldown
 * runtime state for cached players.
 */
public class ResetCooldownsCommand extends AbstractPlayerCommand {

    private static final String PERMISSION_NODE = HytalePermissions.fromCommand("endlessleveling.resetcooldowns");

    private final PassiveManager passiveManager;
    private final AugmentRuntimeManager augmentRuntimeManager;

    public ResetCooldownsCommand() {
        super("resetcooldowns", "Reset all passive and augment cooldown timers for cached players");
        this.passiveManager = EndlessLeveling.getInstance().getPassiveManager();
        this.augmentRuntimeManager = EndlessLeveling.getInstance().getAugmentRuntimeManager();
        this.addAliases("cooldownsreset", "resetpassivecooldowns", "resetcds", "cdreset", "resetcd");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        CommandUtil.requirePermission(commandContext.sender(), PERMISSION_NODE);

        if (passiveManager == null && augmentRuntimeManager == null) {
            senderRef.sendMessage(Message.raw("Passive and augment managers are unavailable.").color("#ff6666"));
            return;
        }

        int passiveAffected = passiveManager != null ? passiveManager.resetAllPassiveCooldowns() : 0;
        if (augmentRuntimeManager != null) {
            augmentRuntimeManager.clearAll();
        }

        senderRef.sendMessage(Message.raw(
                "Reset passive cooldowns for " + passiveAffected
                        + " player runtime state(s) and cleared all augment cooldown runtime state.")
                .color("#4fd7f7"));
    }
}
