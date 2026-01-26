package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * /skills resetskillpoints - resets the caller's allocated attributes back to
 * the baseline.
 */
public class ResetSkillPointsCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;

    public ResetSkillPointsCommand() {
        super("resetskillpoints", "Reset your EndlessLeveling skill points to their default distribution");
        this.playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        this.skillManager = EndlessLeveling.getInstance().getSkillManager();
        this.addAliases("resetskills");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        if (playerDataManager == null || skillManager == null) {
            playerRef.sendMessage(
                    Message.raw("Skill system is not initialised. Please contact an admin.").color("#ff6666"));
            return;
        }

        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("No saved data found. Try rejoining.").color("#ff6666"));
            return;
        }

        skillManager.resetSkillAttributes(playerData);
        skillManager.applyAllSkillModifiers(ref, store, playerData);
        playerDataManager.save(playerData);

        playerRef.sendMessage(Message.raw("Your skill points have been reset to the default layout.").color("#4fd7f7"));
    }
}
