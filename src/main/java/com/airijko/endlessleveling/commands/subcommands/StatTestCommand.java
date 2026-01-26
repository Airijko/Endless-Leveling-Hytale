package com.airijko.endlessleveling.commands.subcommands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

public class StatTestCommand extends AbstractPlayerCommand {

    public StatTestCommand() {
        super("stattest", "Test player stats");
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        CompletableFuture.runAsync(() -> {
            // Fetch entity stats
            EntityStatMap entityStatMap = store.getComponent(ref, EntityStatMap.getComponentType());

            StringBuilder statsMessage = new StringBuilder("Player Stats:\n");

            if (entityStatMap != null) {
                appendStat(statsMessage, entityStatMap, "Health", DefaultEntityStatTypes.getHealth());
                appendStat(statsMessage, entityStatMap, "Oxygen", DefaultEntityStatTypes.getOxygen());
                appendStat(statsMessage, entityStatMap, "Stamina", DefaultEntityStatTypes.getStamina());
                appendStat(statsMessage, entityStatMap, "Mana", DefaultEntityStatTypes.getMana());
                appendStat(statsMessage, entityStatMap, "Signature Energy", DefaultEntityStatTypes.getSignatureEnergy());
                appendStat(statsMessage, entityStatMap, "Ammo", DefaultEntityStatTypes.getAmmo());
            } else {
                statsMessage.append("No stats found for the player.\n");
            }

            // Fetch movement settings
            MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
            if (movementManager != null) {
                MovementSettings settings = movementManager.getSettings();

                // Calculate movement values
                float walkSpeed = settings.baseSpeed * settings.forwardWalkSpeedMultiplier;
                float runSpeed = settings.baseSpeed * settings.forwardRunSpeedMultiplier;
                float sprintSpeed = settings.baseSpeed * settings.forwardSprintSpeedMultiplier;

                statsMessage.append("\nMovement Speeds:\n");
                statsMessage.append("Walk Speed: ").append(walkSpeed).append("\n");
                statsMessage.append("Run Speed: ").append(runSpeed).append("\n");
                statsMessage.append("Sprint Speed: ").append(sprintSpeed).append("\n");
            } else {
                statsMessage.append("No movement settings found.\n");
            }

            // Send message to player
            playerRef.sendMessage(Message.raw(statsMessage.toString()));
        }, world);
    }

    private void appendStat(StringBuilder statsMessage, EntityStatMap statMap, String label, int statIndex) {
        var statValue = statMap.get(statIndex);
        if (statValue != null) {
            statsMessage.append(label).append(": ").append(statValue.get()).append("\n");
        } else {
            statsMessage.append(label).append(": N/A\n");
        }
    }
}
