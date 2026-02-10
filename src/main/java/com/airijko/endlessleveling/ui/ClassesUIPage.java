package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassAssignmentSlot;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page for browsing and selecting EndlessLeveling classes.
 */
public class ClassesUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private String selectedClassId;

    public ClassesUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.classManager = plugin != null ? plugin.getClassManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.levelingManager = plugin != null ? plugin.getLevelingManager() : null;
        this.selectedClassId = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Classes/ClassesPage.ui");
        NavUIHelper.bindNavEvents(events);

        events.addEventBinding(Activating, "#ConfirmPrimaryButton", of("Action", "class:confirm_primary"), false);
        events.addEventBinding(Activating, "#ConfirmSecondaryButton", of("Action", "class:confirm_secondary"), false);

        if (classManager == null || !classManager.isEnabled()) {
            ui.set("#SelectedClassLabel.Text", "Classes Offline");
            ui.set("#SelectedClassSubtitle.Text", "Classes are currently disabled in config.yml.");
            ui.set("#ClassLoreText.Text", "Enable classes to browse archetypes.");
            ui.set("#ClassCountLabel.Text", "0 available");
            ui.set("#ClassSwapCooldownHint.Text", "Enable classes to manage archetypes.");
            ui.set("#ClassPrimaryCooldownValue.Text", "--");
            ui.set("#ClassSecondaryCooldownValue.Text", "--");
            ui.clear("#ClassRows");
            ui.clear("#ClassWeaponEntries");
            ui.clear("#ClassPassiveEntries");
            ui.set("#ClassWeaponSummary.Text", "Weapon bonuses unavailable.");
            ui.set("#ClassPassiveSummary.Text", "Passive bonuses unavailable.");
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#SelectedClassLabel.Text", "Player data unavailable");
            ui.set("#SelectedClassSubtitle.Text", "Unable to load your class information right now.");
            ui.set("#ClassLoreText.Text", "Try reopening this page in a few moments.");
            ui.set("#ClassCountLabel.Text", "0 available");
            ui.set("#ClassSwapCooldownHint.Text", "Try reopening this page in a few moments.");
            ui.set("#ClassPrimaryCooldownValue.Text", "--");
            ui.set("#ClassSecondaryCooldownValue.Text", "--");
            ui.clear("#ClassRows");
            ui.clear("#ClassWeaponEntries");
            ui.clear("#ClassPassiveEntries");
            return;
        }

        boolean operatorBypass = OperatorHelper.isOperator(playerRef);
        CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(playerData);
        CharacterClassDefinition secondary = classManager.getPlayerSecondaryClass(playerData);
        if (selectedClassId == null && primary != null) {
            selectedClassId = primary.getId();
        }

        long cooldownSeconds = classManager.getChooseClassCooldownSeconds();
        long primaryCooldownRemaining = operatorBypass
                ? 0L
                : classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.PRIMARY);
        long secondaryCooldownRemaining = operatorBypass
                ? 0L
                : classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.SECONDARY);

        updateCooldownCard(ui,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                operatorBypass);
        updateStatusCard(ui, primary, secondary);
        buildClassList(ui,
                events,
                playerData,
                primary,
                secondary,
                operatorBypass || primaryCooldownRemaining <= 0L,
                operatorBypass || secondaryCooldownRemaining <= 0L);
        updateClassDetailPanel(ui,
                playerData,
                primary,
                secondary,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                operatorBypass);
    }

    private void updateCooldownCard(UICommandBuilder ui,
            long cooldownSeconds,
            long primaryCooldownRemaining,
            long secondaryCooldownRemaining,
            boolean operatorBypass) {
        if (operatorBypass) {
            ui.set("#ClassSwapCooldownHint.Text", "Operator bypass active; cooldowns ignored.");
            ui.set("#ClassPrimaryCooldownValue.Text", "Bypassed");
            ui.set("#ClassSecondaryCooldownValue.Text", "Bypassed");
            return;
        }
        ui.set("#ClassPrimaryCooldownValue.Text", formatSlotAvailability(primaryCooldownRemaining));
        ui.set("#ClassSecondaryCooldownValue.Text", formatSlotAvailability(secondaryCooldownRemaining));
        if (cooldownSeconds > 0L) {
            ui.set("#ClassSwapCooldownHint.Text",
                    "Each swap triggers a " + formatDuration(cooldownSeconds) + " cooldown per slot.");
        } else {
            ui.set("#ClassSwapCooldownHint.Text", "Class swapping is unrestricted right now.");
        }
    }

    private void updateStatusCard(UICommandBuilder ui,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary) {
        String primaryText = primary != null ? primary.getDisplayName() : "Unassigned";
        String secondaryText = secondary != null ? secondary.getDisplayName() : "None";
        ui.set("#CurrentPrimaryValue.Text", primaryText);
        ui.set("#CurrentSecondaryValue.Text", secondaryText);
    }

    private void buildClassList(UICommandBuilder ui,
            UIEventBuilder events,
            PlayerData data,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary,
            boolean canModifyPrimary,
            boolean canModifySecondary) {
        ui.clear("#ClassRows");

        List<CharacterClassDefinition> classes = new ArrayList<>(classManager.getLoadedClasses());
        classes.sort(Comparator.comparing(def -> def.getDisplayName().toLowerCase(Locale.ROOT)));
        ui.set("#ClassCountLabel.Text", classes.size() + (classes.size() == 1 ? " class" : " classes"));

        for (int index = 0; index < classes.size(); index++) {
            CharacterClassDefinition definition = classes.get(index);
            ui.append("#ClassRows", "Pages/Classes/ClassRow.ui");
            String base = "#ClassRows[" + index + "]";

            boolean isPrimary = primary != null && primary.getId().equalsIgnoreCase(definition.getId());
            boolean isSecondary = secondary != null && secondary.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedClassMatches(definition.getId());

            ui.set(base + " #ClassName.Text", definition.getDisplayName());
            String status = isPrimary ? "PRIMARY" : (isSecondary ? "SECONDARY" : (isSelected ? "VIEWING" : ""));
            boolean hasStatus = !status.isEmpty();
            ui.set(base + " #ClassSelectionStatus.Visible", hasStatus);
            if (hasStatus) {
                ui.set(base + " #ClassSelectionStatus.Text", status);
            }

            events.addEventBinding(Activating,
                    base + " #ViewClassButton",
                    of("Action", "class:view:" + definition.getId()),
                    false);

            boolean showPrimaryButton = !isPrimary;
            ui.set(base + " #SetPrimaryButton.Visible", showPrimaryButton);
            if (showPrimaryButton) {
                events.addEventBinding(Activating,
                        base + " #SetPrimaryButton",
                        of("Action", "class:set_primary:" + definition.getId()),
                        false);
                ui.set(base + " #SetPrimaryButton.Text", canModifyPrimary ? "PRIMARY" : "ON COOLDOWN");
            }

            boolean showSecondaryButton = !isPrimary;
            ui.set(base + " #SetSecondaryButton.Visible", showSecondaryButton);
            if (showSecondaryButton) {
                events.addEventBinding(Activating,
                        base + " #SetSecondaryButton",
                        of("Action", "class:set_secondary:" + definition.getId()),
                        false);
                ui.set(base + " #SetSecondaryButton.Text", canModifySecondary ? "SECONDARY" : "ON COOLDOWN");
            }
        }
    }

    private void updateClassDetailPanel(UICommandBuilder ui,
            PlayerData data,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary,
            long cooldownSeconds,
            long primaryCooldownRemaining,
            long secondaryCooldownRemaining,
            boolean operatorBypass) {
        CharacterClassDefinition selection = resolveSelection(primary);
        if (selection == null) {
            ui.set("#SelectedClassLabel.Text", "Select a Class");
            ui.set("#SelectedClassSubtitle.Text", "Choose a class on the left to preview its role and passives.");
            ui.set("#ClassLoreText.Text", "Lore unavailable.");
            ui.set("#ClassRoleValue.Text", "Role: --");
            ui.set("#ClassWeaponSummary.Visible", true);
            ui.set("#ClassWeaponSummary.Text", "No class selected.");
            ui.clear("#ClassWeaponEntries");
            ui.set("#ClassPassiveSummary.Visible", true);
            ui.set("#ClassPassiveSummary.Text", "No class selected.");
            ui.clear("#ClassPassiveEntries");
            ui.set("#ClassInnateSummary.Visible", true);
            ui.set("#ClassInnateSummary.Text", "No class selected.");
            ui.clear("#ClassInnateEntries");
            ui.set("#ConfirmPrimaryButton.Visible", false);
            ui.set("#ConfirmSecondaryButton.Visible", false);

            String status = "Select a class to preview bonuses.";
            if (operatorBypass) {
                status += " Operator bypass active; swapping is immediate.";
            } else if (primaryCooldownRemaining > 0L || secondaryCooldownRemaining > 0L) {
                status += " Primary " + formatSlotAvailability(primaryCooldownRemaining)
                        + ", Secondary " + formatSlotAvailability(secondaryCooldownRemaining) + ".";
            } else if (cooldownSeconds > 0L) {
                status += " Changing classes triggers a " + formatDuration(cooldownSeconds) + " cooldown.";
            }
            ui.set("#ClassDetailStatus.Text", status);
            return;
        }

        ui.set("#SelectedClassLabel.Text", selection.getDisplayName());
        if (primary != null && primary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text", "Currently your primary class");
        } else if (secondary != null && secondary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text", "Currently your secondary class (50% effect)");
        } else {
            ui.set("#SelectedClassSubtitle.Text", "Preview only");
        }

        String role = selection.getRole();
        ui.set("#ClassRoleValue.Text", role == null || role.isBlank() ? "Role: Unspecified" : "Role: " + role);
        String lore = selection.getDescription();
        ui.set("#ClassLoreText.Text",
                lore == null || lore.isBlank() ? "No lore provided for this class." : lore);

        buildWeaponList(ui, selection);
        buildPassiveList(ui, selection, data);

        boolean canPrimary = primary == null || !primary.getId().equalsIgnoreCase(selection.getId());
        boolean canSecondary = (secondary == null || !secondary.getId().equalsIgnoreCase(selection.getId()))
                && (primary == null || !primary.getId().equalsIgnoreCase(selection.getId()));

        boolean canModifyPrimary = operatorBypass || primaryCooldownRemaining <= 0L;
        boolean canModifySecondary = operatorBypass || secondaryCooldownRemaining <= 0L;
        ui.set("#ConfirmPrimaryButton.Visible", canPrimary && canModifyPrimary);
        ui.set("#ConfirmSecondaryButton.Visible", canSecondary && canModifySecondary);

        StringBuilder statusBuilder = new StringBuilder(
                "Primary classes grant 100% of bonuses. Secondary classes grant 50% of weapon + passive effects.");
        if (operatorBypass) {
            statusBuilder.append(' ').append("Operator bypass active; swapping is immediate.");
        } else if (primaryCooldownRemaining > 0L || secondaryCooldownRemaining > 0L) {
            statusBuilder.append(' ')
                    .append("Primary ")
                    .append(formatSlotAvailability(primaryCooldownRemaining))
                    .append(", Secondary ")
                    .append(formatSlotAvailability(secondaryCooldownRemaining))
                    .append('.');
        } else if (cooldownSeconds > 0L) {
            statusBuilder.append(' ')
                    .append("Changing classes triggers a ")
                    .append(formatDuration(cooldownSeconds))
                    .append(" cooldown.");
        }
        ui.set("#ClassDetailStatus.Text", statusBuilder.toString());
    }

    private void buildWeaponList(UICommandBuilder ui, CharacterClassDefinition selection) {
        Map<ClassWeaponType, Double> weaponMap = selection.getWeaponMultipliers();
        ui.clear("#ClassWeaponEntries");
        if (weaponMap == null || weaponMap.isEmpty()) {
            ui.set("#ClassWeaponSummary.Visible", true);
            ui.set("#ClassWeaponSummary.Text", "This class does not define weapon bonuses.");
            return;
        }
        ui.set("#ClassWeaponSummary.Visible", false);
        List<Map.Entry<ClassWeaponType, Double>> entries = new ArrayList<>(weaponMap.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<ClassWeaponType, Double> e) -> e.getValue()).reversed());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<ClassWeaponType, Double> entry = entries.get(i);
            ui.append("#ClassWeaponEntries", "Pages/Classes/ClassWeaponRow.ui");
            String base = "#ClassWeaponEntries[" + i + "]";
            ui.set(base + " #WeaponName.Text", toDisplay(entry.getKey().name()));
            ui.set(base + " #WeaponMultiplier.Text", formatWeaponMultiplier(entry.getValue()));
        }
    }

    private void buildPassiveList(UICommandBuilder ui, CharacterClassDefinition selection, PlayerData data) {
        List<RacePassiveDefinition> passives = selection.getPassiveDefinitions();
        if (passives == null || passives.isEmpty()) {
            populatePassiveSection(ui,
                    List.of(),
                    data,
                    "#ClassPassiveSummary",
                    "#ClassPassiveEntries",
                    "This class does not define passive bonuses.");
            populatePassiveSection(ui,
                    List.of(),
                    data,
                    "#ClassInnateSummary",
                    "#ClassInnateEntries",
                    "This class does not grant innate attribute bonuses.");
            return;
        }

        List<RacePassiveDefinition> innate = new ArrayList<>();
        List<RacePassiveDefinition> standard = new ArrayList<>();
        for (RacePassiveDefinition passive : passives) {
            if (passive.type() == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                innate.add(passive);
            } else {
                standard.add(passive);
            }
        }

        populatePassiveSection(ui,
                standard,
                data,
                "#ClassPassiveSummary",
                "#ClassPassiveEntries",
                "This class does not define passive bonuses.");
        populatePassiveSection(ui,
                innate,
                data,
                "#ClassInnateSummary",
                "#ClassInnateEntries",
                "This class does not grant innate attribute bonuses.");
    }

    private void populatePassiveSection(UICommandBuilder ui,
            List<RacePassiveDefinition> passives,
            PlayerData data,
            String summarySelector,
            String entriesSelector,
            String emptyText) {
        ui.clear(entriesSelector);
        if (passives == null || passives.isEmpty()) {
            ui.set(summarySelector + ".Visible", true);
            ui.set(summarySelector + ".Text", emptyText);
            return;
        }
        ui.set(summarySelector + ".Visible", false);
        for (int index = 0; index < passives.size(); index++) {
            RacePassiveDefinition passive = passives.get(index);
            ui.append(entriesSelector, "Pages/Profile/ProfileRacePassiveEntry.ui");
            String base = entriesSelector + "[" + index + "]";
            ui.set(base + " #PassiveName.Text", buildPassiveLabel(passive));
            ui.set(base + " #PassiveValue.Text", formatPassiveDescription(passive, data));
        }
    }

    private boolean selectedClassMatches(String classId) {
        return selectedClassId != null && selectedClassId.equalsIgnoreCase(classId);
    }

    private CharacterClassDefinition resolveSelection(CharacterClassDefinition primary) {
        if (classManager == null) {
            return null;
        }
        if (selectedClassId != null) {
            CharacterClassDefinition selected = classManager.getClass(selectedClassId);
            if (selected != null) {
                return selected;
            }
        }
        if (primary != null) {
            selectedClassId = primary.getId();
            return primary;
        }
        CharacterClassDefinition fallback = classManager.getDefaultPrimaryClass();
        if (fallback != null) {
            selectedClassId = fallback.getId();
        }
        return fallback;
    }

    private PlayerData resolvePlayerData() {
        if (playerDataManager == null) {
            LOGGER.atSevere().log("ClassesUIPage: PlayerDataManager unavailable");
            return null;
        }
        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            data = playerDataManager.loadOrCreate(playerRef.getUuid(), playerRef.getUsername());
        }
        return data;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isEmpty()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }
        }

        if (data.action == null || data.action.isBlank() || !data.action.startsWith("class:")) {
            return;
        }

        if (classManager == null || !classManager.isEnabled()) {
            playerRef.sendMessage(Message.raw("Classes are currently disabled.").color("#ff6666"));
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("Unable to load your class info right now.").color("#ff6666"));
            return;
        }

        if (data.action.startsWith("class:view:")) {
            String id = data.action.substring("class:view:".length());
            if (id != null && !id.isBlank()) {
                this.selectedClassId = id.trim();
                rebuild();
            }
            return;
        }

        if (data.action.startsWith("class:set_primary:")) {
            String id = data.action.substring("class:set_primary:".length());
            handlePrimarySelection(id, playerData, ref, store);
            return;
        }

        if (data.action.startsWith("class:set_secondary:")) {
            String id = data.action.substring("class:set_secondary:".length());
            handleSecondarySelection(id, playerData, ref, store);
            return;
        }

        switch (data.action) {
            case "class:confirm_primary" -> handlePrimarySelection(selectedClassId, playerData, ref, store);
            case "class:confirm_secondary" -> handleSecondarySelection(selectedClassId, playerData, ref, store);
            case "class:clear_secondary" -> clearSecondary(playerData, ref, store);
        }
    }

    private void handlePrimarySelection(String targetId,
            PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (targetId == null || targetId.isBlank()) {
            playerRef.sendMessage(Message.raw("Select a class to set as primary.").color("#ff9900"));
            return;
        }
        CharacterClassDefinition desired = classManager.findClassByUserInput(targetId);
        if (desired == null) {
            playerRef.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#ff6666"),
                    Message.raw("Unknown class: ").color("#ffffff"),
                    Message.raw(targetId).color("#ffc300")));
            return;
        }
        CharacterClassDefinition current = classManager.getPlayerPrimaryClass(data);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw("That is already your primary class.").color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.PRIMARY)) {
            return;
        }
        CharacterClassDefinition applied = classManager.setPlayerPrimaryClass(data, desired.getId());
        classManager.markClassChange(data, ClassAssignmentSlot.PRIMARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);
        this.selectedClassId = applied.getId();
        playerRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#4fd7f7"),
                Message.raw("Primary class set to ").color("#ffffff"),
                Message.raw(applied.getDisplayName()).color("#ffc300")));
        rebuild();
    }

    private void handleSecondarySelection(String targetId,
            PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (targetId == null || targetId.isBlank()) {
            playerRef.sendMessage(Message.raw("Select a class to set as secondary.").color("#ff9900"));
            return;
        }
        CharacterClassDefinition desired = classManager.findClassByUserInput(targetId);
        if (desired == null) {
            playerRef.sendMessage(Message.join(
                    Message.raw("[Classes] ").color("#ff6666"),
                    Message.raw("Unknown class: ").color("#ffffff"),
                    Message.raw(targetId).color("#ffc300")));
            return;
        }
        CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(data);
        if (primary != null && primary.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw("Your secondary class cannot match your primary.").color("#ff9900"));
            return;
        }
        CharacterClassDefinition currentSecondary = classManager.getPlayerSecondaryClass(data);
        if (currentSecondary != null && currentSecondary.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw("That is already your secondary class.").color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.SECONDARY)) {
            return;
        }
        CharacterClassDefinition applied = classManager.setPlayerSecondaryClass(data, desired.getId());
        if (applied == null) {
            playerRef.sendMessage(Message.raw("Unable to set that as your secondary class.").color("#ff6666"));
            return;
        }
        classManager.markClassChange(data, ClassAssignmentSlot.SECONDARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);
        playerRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#4fd7f7"),
                Message.raw("Secondary class set to ").color("#ffffff"),
                Message.raw(applied.getDisplayName()).color("#d4b5ff")));
        rebuild();
    }

    private void clearSecondary(PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (data.getSecondaryClassId() == null) {
            playerRef.sendMessage(Message.raw("You do not have a secondary class assigned.").color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.SECONDARY)) {
            return;
        }
        data.setSecondaryClassId(null);
        classManager.markClassChange(data, ClassAssignmentSlot.SECONDARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);
        playerRef.sendMessage(Message.raw("Secondary class cleared.").color("#4fd7f7"));
        rebuild();
    }

    private void reapplyBonuses(PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
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

    private boolean isClassChangeReady(PlayerData data, ClassAssignmentSlot slot) {
        if (classManager == null || slot == null) {
            return true;
        }
        if (OperatorHelper.isOperator(playerRef)) {
            return true;
        }
        long remaining = classManager.getClassCooldownRemaining(data, slot);
        if (remaining <= 0L) {
            return true;
        }
        String slotLabel = slot == ClassAssignmentSlot.PRIMARY ? "primary" : "secondary";
        playerRef.sendMessage(Message.join(
                Message.raw("[Classes] ").color("#ff6666"),
                Message.raw("You can change your ").color("#ffffff"),
                Message.raw(slotLabel).color("#ffc300"),
                Message.raw(" class again in ").color("#ffffff"),
                Message.raw(formatDuration(remaining)).color("#ffc300"),
                Message.raw(".").color("#ffffff")));
        return false;
    }

    private String buildPassiveLabel(RacePassiveDefinition passive) {
        ArchetypePassiveType type = passive.type();
        if (type == null) {
            return "Passive";
        }
        if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN && passive.attributeType() != null) {
            return toDisplay(passive.attributeType().name());
        }
        if (passive.attributeType() != null) {
            return toDisplay(type.name()) + " (" + toDisplay(passive.attributeType().name()) + ")";
        }
        return toDisplay(type.name());
    }

    private String formatPassiveDescription(RacePassiveDefinition passive, PlayerData playerData) {
        ArchetypePassiveType type = passive.type();
        double value = passive.value();
        Map<String, Object> props = passive.properties() == null ? Map.of() : passive.properties();

        Double threshold = getDoubleProp(props, "threshold");
        Double duration = getDoubleProp(props, "duration");
        Double cooldown = getDoubleProp(props, "cooldown");
        Double window = getDoubleProp(props, "window");
        Double stacks = getDoubleProp(props, "max_stacks");

        if (type == null) {
            return value == 0.0D ? "Passive" : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> formatPercentValue(value) + " XP gain";
            case HEALTH_REGEN -> formatPercentValue(value) + " HP/5s";
            case MANA_REGEN -> formatPercentValue(value) + " mana/5s";
            case HEALING_BONUS -> formatPercentValue(value) + " healing";
            case SPECIAL_CHARGE_BONUS -> formatPercentValue(value) + " charge rate";
            case SECOND_WIND -> appendDetails(
                    formatPercentValue(value) + " heal",
                    formatThresholdDetail(threshold, "HP"),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case FIRST_STRIKE -> appendDetails(
                    formatPercentValue(value) + " opener",
                    formatCooldownDetail(cooldown));
            case INNATE_ATTRIBUTE_GAIN -> formatInnatePreview(passive);
            case ADRENALINE -> appendDetails(
                    formatPercentValue(value) + " stamina",
                    formatThresholdDetail(threshold, "stamina"),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case BERZERKER -> appendDetails(
                    formatPercentValue(value) + " damage",
                    formatThresholdDetail(threshold, "HP"));
            case RETALIATION -> appendDetails(
                    formatPercentValue(value) + " reflect",
                    formatWindowDetail(window),
                    formatCooldownDetail(cooldown));
            case EXECUTIONER -> appendDetails(
                    formatPercentValue(value) + " finisher",
                    formatThresholdDetail(threshold, "target HP"),
                    formatCooldownDetail(cooldown));
            case SWIFTNESS -> appendDetails(
                    formatPercentValue(value) + " speed",
                    formatDurationDetail(duration),
                    formatStacksDetail(stacks));
        };
    }

    private String formatInnatePreview(RacePassiveDefinition passive) {
        double perLevel = passive.value();
        String perLevelText = formatSigned(perLevel) + " per level";
        int cap = levelingManager != null ? Math.max(1, levelingManager.getLevelCap()) : 1;
        double total = perLevel * cap;
        String totalText = formatSigned(total);
        return perLevelText + " (Total " + totalText + " @ Lv " + cap + ")";
    }

    private Double getDoubleProp(Map<String, Object> props, String key) {
        Object raw = props.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String appendDetails(String base, String... extra) {
        String detail = joinDetails(extra);
        if (detail.isEmpty()) {
            return base;
        }
        return base + " (" + detail + ")";
    }

    private String joinDetails(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String formatSlotAvailability(long remainingSeconds) {
        if (remainingSeconds <= 0L) {
            return "Ready";
        }
        return formatDuration(remainingSeconds);
    }

    private String formatPercentValue(double ratio) {
        return formatSigned(ratio * 100.0D) + "%";
    }

    private String formatSigned(double number) {
        String prefix = number >= 0 ? "+" : "-";
        return prefix + formatNumber(Math.abs(number));
    }

    private String formatThresholdDetail(Double ratio, String scope) {
        if (ratio == null) {
            return null;
        }
        return "<" + formatNumber(ratio * 100.0D) + "% " + scope;
    }

    private String formatDurationDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s duration";
    }

    private String formatCooldownDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s cd";
    }

    private String formatWindowDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return formatNumber(seconds) + "s window";
    }

    private String formatStacksDetail(Double stacks) {
        if (stacks == null) {
            return null;
        }
        return formatNumber(stacks) + " stacks";
    }

    private String formatWeaponMultiplier(double multiplier) {
        double delta = (multiplier - 1.0D) * 100.0D;
        if (Math.abs(delta) < 0.0001D) {
            return "+0% dmg";
        }
        String prefix = delta > 0 ? "+" : "-";
        return prefix + formatNumber(Math.abs(delta)) + "% dmg";
    }

    private String formatNumber(double value) {
        String formatted = String.format(Locale.US, "%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
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

    private String toDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.toLowerCase(Locale.ROOT).split("[_ ]");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
