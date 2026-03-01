package com.airijko.endlessleveling.commands.classes;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ClassAssignmentSlot;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * /class choose <primary|secondary> <class>
 */
public class ClassChooseCommand extends AbstractPlayerCommand {

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;

    private final RequiredArg<String> slotArg = this.withRequiredArg("slot", "primary|secondary", ArgTypes.STRING);
    private final RequiredArg<String> classArg = this.withRequiredArg("class", "Class identifier", ArgTypes.STRING);

    public ClassChooseCommand(ClassManager classManager, PlayerDataManager playerDataManager) {
        super("choose", "Change your EndlessLeveling class assignments");
        this.classManager = classManager;
        this.playerDataManager = playerDataManager;
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
        if (playerDataManager == null) {
            senderRef.sendMessage(Message.raw("Player data is unavailable right now.").color("#ff6666"));
            return;
        }

        PlayerData data = playerDataManager.get(senderRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(senderRef.getUuid(), senderRef.getUsername());
        }
        if (data == null) {
            senderRef.sendMessage(Message.raw("Unable to load your player data.").color("#ff6666"));
            return;
        }

        String slot = normalize(slotArg.get(context));
        String classInput = sanitizeClassInput(classArg.get(context));
        if (slot == null || classInput == null) {
            senderRef.sendMessage(Message.raw("Usage: /class choose <primary|secondary> <class>").color("#ff6666"));
            return;
        }

        switch (slot) {
            case "primary", "main", "p" -> handlePrimaryChange(senderRef, data, classInput, ref, store);
            case "secondary", "off", "s" -> handleSecondaryChange(senderRef, data, classInput, ref, store);
            default -> senderRef.sendMessage(Message.raw("Unknown slot. Use primary or secondary.").color("#ff6666"));
        }
    }

    private void handlePrimaryChange(PlayerRef senderRef,
            PlayerData data,
            String classInput,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        CharacterClassDefinition desired = classManager.findClassByUserInput(classInput);
        if (desired == null) {
            senderRef.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#ff6666"),
                    Message.raw("Unknown class: ").color("#ffffff"),
                    Message.raw(classInput).color("#ffc300")));
            return;
        }

        CharacterClassDefinition current = classManager.getPlayerPrimaryClass(data);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            senderRef.sendMessage(Message.raw("That is already your primary class.").color("#ff9900"));
            return;
        }

        if (!isClassChangeReady(senderRef, data, ClassAssignmentSlot.PRIMARY)) {
            return;
        }

        CharacterClassDefinition applied = classManager.setPlayerPrimaryClass(data, desired.getId());
        classManager.markClassChange(data, ClassAssignmentSlot.PRIMARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(data);
        }

        senderRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#4fd7f7"),
                Message.raw("Primary class set to ").color("#ffffff"),
                Message.raw(applied.getDisplayName()).color("#ffc300")));
    }

    private void handleSecondaryChange(PlayerRef senderRef,
            PlayerData data,
            String classInput,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (!classManager.isSecondaryClassEnabled()) {
            senderRef.sendMessage(Message.raw("Secondary classes are currently disabled.").color("#ff6666"));
            return;
        }

        if (classInput.equalsIgnoreCase("none") || classInput.equalsIgnoreCase("clear")) {
            clearSecondary(senderRef, data, ref, store);
            return;
        }

        CharacterClassDefinition desired = classManager.findClassByUserInput(classInput);
        if (desired == null) {
            senderRef.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#ff6666"),
                    Message.raw("Unknown class: ").color("#ffffff"),
                    Message.raw(classInput).color("#ffc300")));
            return;
        }

        CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(data);
        if (primary != null && primary.getId().equalsIgnoreCase(desired.getId())) {
            senderRef.sendMessage(Message.raw("Your secondary class cannot match your primary.").color("#ff9900"));
            return;
        }

        CharacterClassDefinition currentSecondary = classManager.getPlayerSecondaryClass(data);
        if (currentSecondary != null && currentSecondary.getId().equalsIgnoreCase(desired.getId())) {
            senderRef.sendMessage(Message.raw("That is already your secondary class.").color("#ff9900"));
            return;
        }

        if (!isClassChangeReady(senderRef, data, ClassAssignmentSlot.SECONDARY)) {
            return;
        }

        CharacterClassDefinition applied = classManager.setPlayerSecondaryClass(data, desired.getId());
        if (applied == null) {
            senderRef.sendMessage(Message.raw("Unable to set that as your secondary class.").color("#ff6666"));
            return;
        }

        classManager.markClassChange(data, ClassAssignmentSlot.SECONDARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);

        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(data);
        }

        senderRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#4fd7f7"),
                Message.raw("Secondary class set to ").color("#ffffff"),
                Message.raw(applied.getDisplayName()).color("#d4b5ff")));
    }

    private void clearSecondary(PlayerRef senderRef,
            PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (!classManager.isSecondaryClassEnabled()) {
            if (data.getSecondaryClassId() != null) {
                data.setSecondaryClassId(null);
                playerDataManager.save(data);
                reapplyBonuses(data, ref, store);
            }
            senderRef.sendMessage(Message.raw("Secondary classes are currently disabled.").color("#ff6666"));
            return;
        }

        if (data.getSecondaryClassId() == null) {
            senderRef.sendMessage(Message.raw("You do not have a secondary class assigned.").color("#ff9900"));
            return;
        }

        if (!isClassChangeReady(senderRef, data, ClassAssignmentSlot.SECONDARY)) {
            return;
        }
        data.setSecondaryClassId(null);
        classManager.markClassChange(data, ClassAssignmentSlot.SECONDARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);
        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(data);
        }
        senderRef.sendMessage(Message.raw("Secondary class cleared.").color("#4fd7f7"));
    }

    private void reapplyBonuses(PlayerData data, Ref<EntityStore> ref, Store<EntityStore> store) {
        SkillManager skillManager = EndlessLeveling.getInstance().getSkillManager();
        boolean applied = false;
        if (skillManager != null) {
            applied = skillManager.applyAllSkillModifiers(ref, store, data);
        }
        if (!applied) {
            PlayerRaceStatSystem retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
            if (retrySystem != null) {
                retrySystem.scheduleRetry(data.getUuid());
            }
        }
    }

    private String normalize(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    private String sanitizeClassInput(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isClassChangeReady(PlayerRef senderRef, PlayerData data, ClassAssignmentSlot slot) {
        if (slot == null) {
            return true;
        }
        if (OperatorHelper.isOperator(senderRef)) {
            return true;
        }
        if (!classManager.hasClassSwitchesRemaining(data)) {
            senderRef.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#ff6666"),
                    Message.raw("You have no class changes remaining.").color("#ffffff")));
            return false;
        }
        long remaining = classManager.getClassCooldownRemaining(data, slot);
        if (remaining <= 0L) {
            return true;
        }
        String slotLabel = slot == ClassAssignmentSlot.PRIMARY ? "primary" : "secondary";
        senderRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#ff6666"),
                Message.raw("You can change your ").color("#ffffff"),
                Message.raw(slotLabel).color("#ffc300"),
                Message.raw(" class again in ").color("#ffffff"),
                Message.raw(formatDuration(remaining)).color("#ffc300"),
                Message.raw(".").color("#ffffff")));
        return false;
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h");
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(minutes).append("m");
        }
        if (remainingSeconds > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(remainingSeconds).append("s");
        }
        return builder.toString();
    }
}
