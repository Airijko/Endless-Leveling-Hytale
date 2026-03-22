package com.airijko.endlessleveling.commands.classes;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.commands.subcommands.OpenPageSubCommand;
import com.airijko.endlessleveling.commands.classes.AddClassSwapCommand;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.ui.ClassesUIPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /class root command for browsing EndlessLeveling classes.
 */
public class ClassCommand extends AbstractPlayerCommand {

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;

    public ClassCommand(ClassManager classManager, PlayerDataManager playerDataManager) {
        super("classes", "List available EndlessLeveling classes");
        this.classManager = classManager;
        this.playerDataManager = playerDataManager;
        this.addAliases("class");
        this.addSubCommand(new ClassChooseCommand(classManager, playerDataManager));
        this.addSubCommand(new OpenPageSubCommand(
                "ui",
                "Open the EndlessLeveling Classes page",
                playerRef -> new ClassesUIPage(playerRef, CustomPageLifetime.CanDismiss)));
        this.addSubCommand(new ClassPathsCommand(classManager));
        this.addSubCommand(new AddClassSwapCommand(classManager, playerDataManager));
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        if (classManager == null || !classManager.isEnabled()) {
            senderRef.sendMessage(Message.raw("Classes are currently disabled.").color("#ff6666"));
            return;
        }

        List<CharacterClassDefinition> classes = new ArrayList<>(classManager.getLoadedClasses());
        if (classes.isEmpty()) {
            senderRef.sendMessage(Message.raw("No classes have been configured yet.").color("#ff6666"));
            return;
        }

        classes.sort(Comparator.comparing(
                def -> def.getDisplayName().toLowerCase(Locale.ROOT)));

        PlayerData data = resolvePlayerData(senderRef);
        CharacterClassDefinition primary = data == null ? null : classManager.getPlayerPrimaryClass(data);
        CharacterClassDefinition secondary = data == null ? null : classManager.getPlayerSecondaryClass(data);

        senderRef.sendMessage(Message.raw("Available classes (" + classes.size() + "): ").color("#4fd7f7"));

        for (CharacterClassDefinition definition : classes) {
            boolean isPrimary = primary != null && primary.getId().equalsIgnoreCase(definition.getId());
            boolean isSecondary = secondary != null && secondary.getId().equalsIgnoreCase(definition.getId());
            String displayName = definition.getDisplayName();
            String role = definition.getRole();

            List<Message> parts = new ArrayList<>();
            parts.add(Message.raw(" • ").color("#ffc300"));
            parts.add(Message.raw(displayName).color("#ffffff"));
            if (isPrimary) {
                parts.add(Message.raw(" (primary)").color("#6cff78"));
            } else if (isSecondary) {
                parts.add(Message.raw(" (secondary)").color("#d4b5ff"));
            }
            if (role != null && !role.isBlank()) {
                parts.add(Message.raw(" – " + role).color("#9fb6d3"));
            }

            senderRef.sendMessage(Message.join(parts.toArray(Message[]::new)));
        }

        senderRef.sendMessage(Message.raw(
                "Tip: Use /class choose <primary|secondary> <class> to update assignments, /class ui to open the browser, or /class paths <class> to inspect progression.")
                .color("#9fb6d3"));
    }

    private PlayerData resolvePlayerData(PlayerRef senderRef) {
        if (playerDataManager == null || senderRef == null) {
            return null;
        }
        PlayerData data = playerDataManager.get(senderRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(senderRef.getUuid(), senderRef.getUsername());
        }
        return data;
    }
}
