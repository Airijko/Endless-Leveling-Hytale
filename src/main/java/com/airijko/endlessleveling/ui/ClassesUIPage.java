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
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.util.Lang;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page for browsing and selecting EndlessLeveling classes.
 */
public class ClassesUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final Map<String, String> DEFAULT_CLASS_ICONS = new HashMap<>();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final ClassManager classManager;
    private final PlayerDataManager playerDataManager;
    private final LevelingManager levelingManager;
    private final SkillManager skillManager;
    private String selectedClassId;

    public ClassesUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.classManager = plugin != null ? plugin.getClassManager() : null;
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.levelingManager = plugin != null ? plugin.getLevelingManager() : null;
        this.skillManager = plugin != null ? plugin.getSkillManager() : null;
        this.selectedClassId = null;
    }

    static {
        DEFAULT_CLASS_ICONS.put("*", "Weapon_Longsword_Adamantite_Saurian");
        DEFAULT_CLASS_ICONS.put("adventurer", "Ingredient_Life_Essence");
        DEFAULT_CLASS_ICONS.put("assassin", "Weapon_Daggers_Mithril");
        DEFAULT_CLASS_ICONS.put("juggernaut", "Weapon_Battleaxe_Mithril");
        DEFAULT_CLASS_ICONS.put("mage", "Weapon_Spellbook_Grimoire_Brown");
        DEFAULT_CLASS_ICONS.put("arcanist", "Weapon_Staff_Bronze");
        DEFAULT_CLASS_ICONS.put("battlemage", "Weapon_Staff_Onyxium");
        DEFAULT_CLASS_ICONS.put("oracle", "Weapon_Daggers_Mithril");
        DEFAULT_CLASS_ICONS.put("marksman", "Weapon_Shortbow_Combat");
        DEFAULT_CLASS_ICONS.put("skirmisher", "Weapon_Longsword_Mithril");
        DEFAULT_CLASS_ICONS.put("vanguard", "Weapon_Mace_Prisma");
        DEFAULT_CLASS_ICONS.put("example", "Potion_Health");
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Classes/ClassesPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef);
        applyStaticLabels(ui);
        NavUIHelper.bindNavEvents(events);

        events.addEventBinding(Activating, "#ConfirmPrimaryButton", of("Action", "class:confirm_primary"), false);
        events.addEventBinding(Activating, "#ConfirmSecondaryButton", of("Action", "class:confirm_secondary"), false);

        if (classManager == null || !classManager.isEnabled()) {
            ui.set("#SelectedClassLabel.Text", tr("ui.classes.offline.title", "Classes Offline"));
            ui.set("#SelectedClassSubtitle.Text",
                    tr("ui.classes.offline.subtitle", "Classes are currently disabled in config.yml."));
            ui.set("#ClassLoreText.Text", tr("ui.classes.offline.lore", "Enable classes to browse archetypes."));
            ui.set("#ClassCountLabel.Text", tr("ui.classes.count_none", "0 available"));
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.offline.hint", "Enable classes to manage archetypes."));
            ui.set("#ClassPrimaryCooldownValue.Text", tr("hud.common.unavailable", "--"));
            ui.set("#ClassSecondaryCooldownValue.Text", tr("hud.common.unavailable", "--"));
            ui.clear("#ClassRows");
            ui.clear("#ClassWeaponEntries");
            ui.clear("#ClassPassiveEntries");
            ui.set("#ClassWeaponSummary.Text", tr("ui.classes.offline.weapons", "Weapon bonuses unavailable."));
            ui.set("#ClassPassiveSummary.Text", tr("ui.classes.offline.passives", "Passive bonuses unavailable."));
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#SelectedClassLabel.Text", tr("ui.classes.playerdata.title", "Player data unavailable"));
            ui.set("#SelectedClassSubtitle.Text",
                    tr("ui.classes.playerdata.subtitle", "Unable to load your class information right now."));
            ui.set("#ClassLoreText.Text",
                    tr("ui.classes.playerdata.lore", "Try reopening this page in a few moments."));
            ui.set("#ClassCountLabel.Text", tr("ui.classes.count_none", "0 available"));
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.playerdata.hint", "Try reopening this page in a few moments."));
            ui.set("#ClassPrimaryCooldownValue.Text", tr("hud.common.unavailable", "--"));
            ui.set("#ClassSecondaryCooldownValue.Text", tr("hud.common.unavailable", "--"));
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

        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        boolean primaryChangesRemaining = classManager.hasClassSwitchesRemaining(playerData,
                ClassAssignmentSlot.PRIMARY);
        boolean secondaryChangesRemaining = secondaryEnabled
                && classManager.hasClassSwitchesRemaining(playerData, ClassAssignmentSlot.SECONDARY);

        long cooldownSeconds = classManager.getChooseClassCooldownSeconds();
        long primaryCooldownRemaining = operatorBypass
                ? 0L
                : (primaryChangesRemaining
                        ? classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.PRIMARY)
                        : 0L);
        long secondaryCooldownRemaining = operatorBypass
                ? 0L
                : (secondaryChangesRemaining
                        ? classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.SECONDARY)
                        : 0L);

        updateCooldownCard(ui,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                primaryChangesRemaining,
                secondaryChangesRemaining,
                secondaryEnabled,
                operatorBypass);
        updateStatusCard(ui, primary, secondary);
        buildClassList(ui,
                events,
                playerData,
                primary,
                secondary,
                operatorBypass || (primaryChangesRemaining && primaryCooldownRemaining <= 0L),
                operatorBypass || (secondaryChangesRemaining && secondaryCooldownRemaining <= 0L));
        updateClassDetailPanel(ui,
                playerData,
                primary,
                secondary,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                primaryChangesRemaining,
                secondaryChangesRemaining,
                operatorBypass);
    }

    private void refreshClassUi(@Nonnull PlayerData playerData, boolean operatorBypass) {
        if (classManager == null || !classManager.isEnabled()) {
            return;
        }

        CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(playerData);
        CharacterClassDefinition secondary = classManager.getPlayerSecondaryClass(playerData);
        if (selectedClassId == null && primary != null) {
            selectedClassId = primary.getId();
        }

        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        boolean primaryChangesRemaining = classManager.hasClassSwitchesRemaining(playerData,
                ClassAssignmentSlot.PRIMARY);
        boolean secondaryChangesRemaining = secondaryEnabled
                && classManager.hasClassSwitchesRemaining(playerData, ClassAssignmentSlot.SECONDARY);

        long cooldownSeconds = classManager.getChooseClassCooldownSeconds();
        long primaryCooldownRemaining = operatorBypass
                ? 0L
                : (primaryChangesRemaining
                        ? classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.PRIMARY)
                        : 0L);
        long secondaryCooldownRemaining = operatorBypass
                ? 0L
                : (secondaryChangesRemaining
                        ? classManager.getClassCooldownRemaining(playerData, ClassAssignmentSlot.SECONDARY)
                        : 0L);

        UICommandBuilder ui = new UICommandBuilder();
        updateCooldownCard(ui,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                primaryChangesRemaining,
                secondaryChangesRemaining,
                secondaryEnabled,
                operatorBypass);
        updateStatusCard(ui, primary, secondary);
        refreshClassList(ui, primary, secondary);
        updateClassDetailPanel(ui,
                playerData,
                primary,
                secondary,
                cooldownSeconds,
                primaryCooldownRemaining,
                secondaryCooldownRemaining,
                primaryChangesRemaining,
                secondaryChangesRemaining,
                operatorBypass);
        sendUpdate(ui, false);
    }

    private void updateCooldownCard(UICommandBuilder ui,
            long cooldownSeconds,
            long primaryCooldownRemaining,
            long secondaryCooldownRemaining,
            boolean primaryChangesRemaining,
            boolean secondaryChangesRemaining,
            boolean secondaryEnabled,
            boolean operatorBypass) {
        if (operatorBypass) {
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.cooldown.bypassed_hint", "Operator bypass active; cooldowns ignored."));
            ui.set("#ClassPrimaryCooldownValue.Text", tr("ui.classes.cooldown.bypassed", "Bypassed"));
            ui.set("#ClassSecondaryCooldownValue.Text", tr("ui.classes.cooldown.bypassed", "Bypassed"));
            return;
        }
        boolean allChangesExhausted = !primaryChangesRemaining && (!secondaryEnabled || !secondaryChangesRemaining);

        ui.set("#ClassPrimaryCooldownValue.Text",
                primaryChangesRemaining
                        ? formatSlotAvailability(primaryCooldownRemaining)
                        : tr("ui.classes.cooldown.exhausted", "No changes left"));

        if (!secondaryEnabled) {
            ui.set("#ClassSecondaryCooldownValue.Text", tr("ui.classes.detail.secondary_disabled", "Disabled"));
        } else {
            ui.set("#ClassSecondaryCooldownValue.Text",
                    secondaryChangesRemaining
                            ? formatSlotAvailability(secondaryCooldownRemaining)
                            : tr("ui.classes.cooldown.exhausted", "No changes left"));
        }

        if (allChangesExhausted) {
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.error.no_changes_remaining", "You have no class changes remaining."));
            return;
        }

        if (cooldownSeconds > 0L) {
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.cooldown.each_swap", "Each swap triggers a {0} cooldown per slot.",
                            formatDuration(cooldownSeconds)));
        } else {
            ui.set("#ClassSwapCooldownHint.Text",
                    tr("ui.classes.cooldown.unrestricted", "Class swapping is unrestricted right now."));
        }
    }

    private void updateStatusCard(UICommandBuilder ui,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary) {
        String primaryText = primary != null ? primary.getDisplayName()
                : tr("ui.classes.current.primary_none", "Unassigned");
        String secondaryText = secondary != null ? secondary.getDisplayName() : tr("hud.class.none", "None");
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
        ui.set("#ClassCountLabel.Text",
                tr("ui.classes.count", "{0} {1}", classes.size(), classes.size() == 1
                        ? tr("ui.classes.count_word.singular", "class")
                        : tr("ui.classes.count_word.plural", "classes")));

        for (int index = 0; index < classes.size(); index++) {
            CharacterClassDefinition definition = classes.get(index);
            ui.append("#ClassRows", "Pages/Classes/ClassRow.ui");
            String base = "#ClassRows[" + index + "]";

            boolean isPrimary = primary != null && primary.getId().equalsIgnoreCase(definition.getId());
            boolean isSecondary = secondary != null && secondary.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedClassMatches(definition.getId());

            ui.set(base + " #ClassName.Text", definition.getDisplayName());
            String status = isPrimary ? tr("ui.classes.status.primary", "PRIMARY")
                    : (isSecondary ? tr("ui.classes.status.secondary", "SECONDARY")
                            : (isSelected ? tr("ui.classes.status.viewing", "VIEWING") : ""));
            boolean hasStatus = !status.isEmpty();
            ui.set(base + " #ClassSelectionStatus.Visible", hasStatus);
            if (hasStatus) {
                ui.set(base + " #ClassSelectionStatus.Text", status);
            }

            ui.set(base + " #ViewClassButton.Text", tr("ui.classes.actions.view", "VIEW"));

            applyClassIcon(ui, base, definition);

            events.addEventBinding(Activating,
                    base + " #ViewClassButton",
                    of("Action", "class:view:" + definition.getId()),
                    false);

        }
    }

    private void refreshClassList(UICommandBuilder ui,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary) {
        List<CharacterClassDefinition> classes = new ArrayList<>(classManager.getLoadedClasses());
        classes.sort(Comparator.comparing(def -> def.getDisplayName().toLowerCase(Locale.ROOT)));
        ui.set("#ClassCountLabel.Text",
                tr("ui.classes.count", "{0} {1}", classes.size(), classes.size() == 1
                        ? tr("ui.classes.count_word.singular", "class")
                        : tr("ui.classes.count_word.plural", "classes")));

        for (int index = 0; index < classes.size(); index++) {
            CharacterClassDefinition definition = classes.get(index);
            String base = "#ClassRows[" + index + "]";

            boolean isPrimary = primary != null && primary.getId().equalsIgnoreCase(definition.getId());
            boolean isSecondary = secondary != null && secondary.getId().equalsIgnoreCase(definition.getId());
            boolean isSelected = selectedClassMatches(definition.getId());

            ui.set(base + " #ClassName.Text", definition.getDisplayName());
            String status = isPrimary ? tr("ui.classes.status.primary", "PRIMARY")
                    : (isSecondary ? tr("ui.classes.status.secondary", "SECONDARY")
                            : (isSelected ? tr("ui.classes.status.viewing", "VIEWING") : ""));
            boolean hasStatus = !status.isEmpty();
            ui.set(base + " #ClassSelectionStatus.Visible", hasStatus);
            if (hasStatus) {
                ui.set(base + " #ClassSelectionStatus.Text", status);
            }

            ui.set(base + " #ViewClassButton.Text", tr("ui.classes.actions.view", "VIEW"));

            applyClassIcon(ui, base, definition);
        }
    }

    private void applyClassIcon(UICommandBuilder ui, String baseSelector, CharacterClassDefinition definition) {
        String selector = baseSelector + " #ClassIcon";
        String iconId = resolveClassIcon(definition);
        if (iconId == null || iconId.isBlank()) {
            ui.set(selector + ".Visible", false);
            return;
        }
        ui.set(selector + ".ItemId", iconId);
        ui.set(selector + ".Visible", true);
    }

    private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
        ui.set("#ClassesTitleLabel.Text", tr("ui.classes.page.title", "Class Codex"));
        ui.set("#ClassSwapCooldownCardTitle.Text", tr("ui.classes.page.cooldown_title", "Class Swap Cooldown"));
        ui.set("#ClassPrimaryCooldownLabel.Text", tr("ui.classes.detail.primary_slot", "Primary"));
        ui.set("#ClassSecondaryCooldownLabel.Text", tr("ui.classes.detail.secondary_slot", "Secondary"));
        ui.set("#ClassListTitle.Text", tr("ui.classes.page.available", "Available Classes"));
        ui.set("#ClassAssignmentsTitle.Text", tr("ui.classes.page.assignments", "Class Assignments"));
        ui.set("#ClassPrimaryStatusLabel.Text", tr("ui.classes.detail.primary_slot", "Primary"));
        ui.set("#ClassSecondaryStatusLabel.Text", tr("ui.classes.detail.secondary_slot", "Secondary"));
        ui.set("#ClassSecondaryShareHint.Text",
                tr("ui.classes.page.secondary_share_hint", "Secondary classes share 50% of bonuses."));
        ui.set("#ClassSorceryLabel.Text", tr("ui.classes.page.sorcery_label", "Sorcery Bonus"));
        ui.set("#ClassLoreTitle.Text", tr("ui.classes.page.lore_title", "Lore Preview"));
        ui.set("#ClassWeaponsTitle.Text", tr("ui.classes.page.weapons_title", "Weapon Affinities"));
        ui.set("#ClassInnateTitle.Text", tr("ui.classes.page.innate_title", "Innate Attribute Gains"));
        ui.set("#ClassPassivesTitle.Text", tr("ui.classes.page.passives_title", "Passives"));
        ui.set("#ConfirmPrimaryButton.Text", tr("ui.classes.actions.set_primary", "SET PRIMARY"));
        ui.set("#ConfirmSecondaryButton.Text", tr("ui.classes.actions.set_secondary", "SET SECONDARY"));
    }

    private String resolveClassIcon(CharacterClassDefinition definition) {
        if (definition == null) {
            return null;
        }
        String configured = definition.getIconItemId();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String fallback = DEFAULT_CLASS_ICONS.get(normalizeClassId(definition.getId()));
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return DEFAULT_CLASS_ICONS.get("*");
    }

    private static String normalizeClassId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private void updateClassDetailPanel(UICommandBuilder ui,
            PlayerData data,
            CharacterClassDefinition primary,
            CharacterClassDefinition secondary,
            long cooldownSeconds,
            long primaryCooldownRemaining,
            long secondaryCooldownRemaining,
            boolean primaryChangesRemaining,
            boolean secondaryChangesRemaining,
            boolean operatorBypass) {
        boolean secondaryEnabled = classManager != null && classManager.isSecondaryClassEnabled();
        boolean allChangesExhausted = !primaryChangesRemaining && (!secondaryEnabled || !secondaryChangesRemaining);
        updateSorceryDisplay(ui, data);
        CharacterClassDefinition selection = resolveSelection(primary);
        if (selection == null) {
            ui.set("#SelectedClassLabel.Text", tr("ui.classes.select.title", "Select a Class"));
            ui.set("#SelectedClassSubtitle.Text",
                    tr("ui.classes.select.subtitle", "Choose a class on the left to preview its role and passives."));
            ui.set("#ClassLoreText.Text", tr("ui.classes.lore.unavailable", "Lore unavailable."));
            ui.set("#ClassRoleValue.Text", tr("ui.classes.role.empty", "Role: --"));
            ui.set("#ClassWeaponSummary.Visible", true);
            ui.set("#ClassWeaponSummary.Text", tr("ui.classes.none_selected", "No class selected."));
            ui.clear("#ClassWeaponEntries");
            ui.set("#ClassPassiveSummary.Visible", true);
            ui.set("#ClassPassiveSummary.Text", tr("ui.classes.none_selected", "No class selected."));
            ui.clear("#ClassPassiveEntries");
            ui.set("#ClassInnateSummary.Visible", true);
            ui.set("#ClassInnateSummary.Text", tr("ui.classes.none_selected", "No class selected."));
            ui.clear("#ClassInnateEntries");
            ui.set("#ConfirmPrimaryButton.Visible", false);
            ui.set("#ConfirmSecondaryButton.Visible", false);

            String status = tr("ui.classes.detail.select_prompt", "Select a class to preview bonuses.");
            if (operatorBypass) {
                status += " "
                        + tr("ui.classes.cooldown.bypassed_detail", "Operator bypass active; swapping is immediate.");
            } else if (allChangesExhausted) {
                status += " " + tr("ui.classes.error.no_changes_remaining", "You have no class changes remaining.");
            } else if (primaryCooldownRemaining > 0L || (secondaryEnabled && secondaryCooldownRemaining > 0L)) {
                status += " " + tr("ui.classes.detail.primary_slot", "Primary") + " "
                        + formatSlotAvailability(primaryCooldownRemaining);
                if (secondaryEnabled) {
                    status += ", " + tr("ui.classes.detail.secondary_slot", "Secondary") + " "
                            + formatSlotAvailability(secondaryCooldownRemaining);
                }
                status += ".";
            } else if (cooldownSeconds > 0L) {
                status += " " + tr("ui.classes.detail.swap_triggers", "Changing classes triggers a {0} cooldown.",
                        formatDuration(cooldownSeconds));
            }
            ui.set("#ClassDetailStatus.Text", status);
            return;
        }

        ui.set("#SelectedClassLabel.Text", selection.getDisplayName());
        if (primary != null && primary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text", tr("ui.classes.subtitle.primary", "Currently your primary class"));
        } else if (secondary != null && secondary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text",
                    tr("ui.classes.subtitle.secondary", "Currently your secondary class (50% effect)"));
        } else {
            ui.set("#SelectedClassSubtitle.Text", tr("ui.classes.subtitle.preview", "Preview only"));
        }

        String role = selection.getRole();
        ui.set("#ClassRoleValue.Text", role == null || role.isBlank()
                ? tr("ui.classes.role.unspecified", "Role: Unspecified")
                : tr("ui.classes.role.value", "Role: {0}", role));
        String lore = selection.getDescription();
        ui.set("#ClassLoreText.Text",
                lore == null || lore.isBlank() ? tr("ui.classes.lore.missing", "No lore provided for this class.")
                        : lore);

        buildWeaponList(ui, selection);
        buildPassiveList(ui, selection, data);

        boolean canPrimary = primary == null || !primary.getId().equalsIgnoreCase(selection.getId());
        boolean canSecondary = (secondary == null || !secondary.getId().equalsIgnoreCase(selection.getId()))
                && (primary == null || !primary.getId().equalsIgnoreCase(selection.getId()));

        boolean canModifyPrimary = operatorBypass || (primaryChangesRemaining && primaryCooldownRemaining <= 0L);
        boolean canModifySecondary = operatorBypass
                || (secondaryEnabled && secondaryChangesRemaining && secondaryCooldownRemaining <= 0L);
        ui.set("#ConfirmPrimaryButton.Visible", canPrimary && canModifyPrimary);
        ui.set("#ConfirmSecondaryButton.Visible", secondaryEnabled && canSecondary && canModifySecondary);

        StringBuilder statusBuilder = new StringBuilder(
                tr("ui.classes.detail.primary_bonus", "Primary classes grant 100% of bonuses."));
        if (secondaryEnabled) {
            statusBuilder.append(' ').append(
                    tr("ui.classes.detail.secondary_bonus",
                            "Secondary classes grant 50% of weapon + passive effects."));
        } else {
            statusBuilder.append(' ')
                    .append(tr("ui.classes.detail.secondary_disabled", "Secondary classes are disabled in config."));
        }
        if (operatorBypass) {
            statusBuilder.append(' ').append(
                    tr("ui.classes.cooldown.bypassed_detail", "Operator bypass active; swapping is immediate."));
        } else if (allChangesExhausted) {
            statusBuilder.append(' ')
                    .append(tr("ui.classes.error.no_changes_remaining", "You have no class changes remaining."));
        } else if (primaryCooldownRemaining > 0L || (secondaryEnabled && secondaryCooldownRemaining > 0L)) {
            statusBuilder.append(' ')
                    .append(tr("ui.classes.detail.primary_slot", "Primary"))
                    .append(' ')
                    .append(formatSlotAvailability(primaryCooldownRemaining));
            if (secondaryEnabled) {
                statusBuilder.append(", ")
                        .append(tr("ui.classes.detail.secondary_slot", "Secondary"))
                        .append(' ')
                        .append(formatSlotAvailability(secondaryCooldownRemaining));
            }
            statusBuilder.append('.');
        } else if (cooldownSeconds > 0L) {
            statusBuilder.append(' ').append(
                    tr("ui.classes.detail.swap_triggers", "Changing classes triggers a {0} cooldown.",
                            formatDuration(cooldownSeconds)));
        }
        ui.set("#ClassDetailStatus.Text", statusBuilder.toString());
    }

    private void updateSorceryDisplay(@Nonnull UICommandBuilder ui, PlayerData data) {
        if (skillManager == null || data == null) {
            ui.set("#ClassSorceryValue.Text", tr("hud.common.unavailable", "--"));
            return;
        }
        float sorcery = skillManager.calculatePlayerSorcery(data);
        ui.set("#ClassSorceryValue.Text", tr("ui.skills.value.sorcery", "+{0}% Magic Damage", formatNumber(sorcery)));
    }

    private void buildWeaponList(UICommandBuilder ui, CharacterClassDefinition selection) {
        Map<ClassWeaponType, Double> weaponMap = selection.getWeaponMultipliers();
        ui.clear("#ClassWeaponEntries");
        if (weaponMap == null || weaponMap.isEmpty()) {
            ui.set("#ClassWeaponSummary.Visible", true);
            ui.set("#ClassWeaponSummary.Text",
                    tr("ui.classes.weapons.none_defined", "This class does not define weapon bonuses."));
            return;
        }
        ui.set("#ClassWeaponSummary.Visible", false);
        List<Map.Entry<ClassWeaponType, Double>> entries = new ArrayList<>(weaponMap.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<ClassWeaponType, Double> e) -> e.getValue()).reversed());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<ClassWeaponType, Double> entry = entries.get(i);
            ui.append("#ClassWeaponEntries", "Pages/Classes/ClassWeaponRow.ui");
            String base = "#ClassWeaponEntries[" + i + "]";
            ui.set(base + " #WeaponName.Text", localizeWeaponType(entry.getKey()));
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
                    tr("ui.classes.passives.none_defined", "This class does not define passive bonuses."));
            populatePassiveSection(ui,
                    List.of(),
                    data,
                    "#ClassInnateSummary",
                    "#ClassInnateEntries",
                    tr("ui.classes.passives.none_innate", "This class does not grant innate attribute bonuses."));
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
                tr("ui.classes.passives.none_defined", "This class does not define passive bonuses."));
        populatePassiveSection(ui,
                innate,
                data,
                "#ClassInnateSummary",
                "#ClassInnateEntries",
                tr("ui.classes.passives.none_innate", "This class does not grant innate attribute bonuses."));
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
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.disabled", "Classes are currently disabled.")).color("#ff6666"));
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.playerdata", "Unable to load your class info right now."))
                            .color("#ff6666"));
            return;
        }

        if (data.action.startsWith("class:view:")) {
            String id = data.action.substring("class:view:".length());
            if (id != null && !id.isBlank()) {
                this.selectedClassId = id.trim();
                refreshClassUi(playerData, OperatorHelper.isOperator(playerRef));
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
            playerRef.sendMessage(Message
                    .raw(tr("ui.classes.error.select_primary", "Select a class to set as primary.")).color("#ff9900"));
            return;
        }
        CharacterClassDefinition desired = classManager.findClassByUserInput(targetId);
        if (desired == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.unknown", "Unknown class: {0}", targetId)).color("#ff6666"));
            return;
        }
        CharacterClassDefinition current = classManager.getPlayerPrimaryClass(data);
        if (current != null && current.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.primary_already", "That is already your primary class."))
                            .color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.PRIMARY)) {
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
        this.selectedClassId = applied.getId();
        playerRef.sendMessage(
                Message.raw(tr("ui.classes.info.primary_set", "Primary class set to {0}.", applied.getDisplayName()))
                        .color("#4fd7f7"));
        refreshClassUi(data, OperatorHelper.isOperator(playerRef));
    }

    private void handleSecondarySelection(String targetId,
            PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (classManager == null || !classManager.isSecondaryClassEnabled()) {
            if (data.getSecondaryClassId() != null) {
                data.setSecondaryClassId(null);
                playerDataManager.save(data);
                reapplyBonuses(data, ref, store);
            }
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.secondary_disabled", "Secondary classes are currently disabled."))
                            .color("#ff6666"));
            refreshClassUi(data, OperatorHelper.isOperator(playerRef));
            return;
        }

        if (targetId == null || targetId.isBlank()) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.select_secondary", "Select a class to set as secondary."))
                            .color("#ff9900"));
            return;
        }
        CharacterClassDefinition desired = classManager.findClassByUserInput(targetId);
        if (desired == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.unknown", "Unknown class: {0}", targetId)).color("#ff6666"));
            return;
        }
        CharacterClassDefinition primary = classManager.getPlayerPrimaryClass(data);
        if (primary != null && primary.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(Message.raw(
                    tr("ui.classes.error.secondary_matches_primary", "Your secondary class cannot match your primary."))
                    .color("#ff9900"));
            return;
        }
        CharacterClassDefinition currentSecondary = classManager.getPlayerSecondaryClass(data);
        if (currentSecondary != null && currentSecondary.getId().equalsIgnoreCase(desired.getId())) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.secondary_already", "That is already your secondary class."))
                            .color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.SECONDARY)) {
            return;
        }
        CharacterClassDefinition applied = classManager.setPlayerSecondaryClass(data, desired.getId());
        if (applied == null) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.classes.error.secondary_set_failed", "Unable to set that as your secondary class."))
                    .color("#ff6666"));
            return;
        }
        classManager.markClassChange(data, ClassAssignmentSlot.SECONDARY);
        playerDataManager.save(data);
        reapplyBonuses(data, ref, store);
        var partyManager = EndlessLeveling.getInstance().getPartyManager();
        if (partyManager != null) {
            partyManager.updatePartyHudCustomText(data);
        }
        playerRef.sendMessage(Message
                .raw(tr("ui.classes.info.secondary_set", "Secondary class set to {0}.", applied.getDisplayName()))
                .color("#4fd7f7"));
        refreshClassUi(data, OperatorHelper.isOperator(playerRef));
    }

    private void clearSecondary(PlayerData data,
            Ref<EntityStore> ref,
            Store<EntityStore> store) {
        if (classManager == null || !classManager.isSecondaryClassEnabled()) {
            if (data.getSecondaryClassId() != null) {
                data.setSecondaryClassId(null);
                playerDataManager.save(data);
                reapplyBonuses(data, ref, store);
            }
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.secondary_disabled", "Secondary classes are currently disabled."))
                            .color("#ff6666"));
            refreshClassUi(data, OperatorHelper.isOperator(playerRef));
            return;
        }

        if (data.getSecondaryClassId() == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.secondary_none", "You do not have a secondary class assigned."))
                            .color("#ff9900"));
            return;
        }
        if (!isClassChangeReady(data, ClassAssignmentSlot.SECONDARY)) {
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
        playerRef.sendMessage(
                Message.raw(tr("ui.classes.secondary_cleared", "Secondary class cleared.")).color("#4fd7f7"));
        refreshClassUi(data, OperatorHelper.isOperator(playerRef));
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
        if (!classManager.hasClassSwitchesRemaining(data, slot)) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.no_changes_remaining", "You have no class changes remaining."))
                            .color("#ff6666"));
            return false;
        }
        long remaining = classManager.getClassCooldownRemaining(data, slot);
        if (remaining <= 0L) {
            return true;
        }
        String slotLabel = slot == ClassAssignmentSlot.PRIMARY
                ? tr("ui.classes.slot.primary", "primary")
                : tr("ui.classes.slot.secondary", "secondary");
        playerRef.sendMessage(
                Message.raw(tr("ui.classes.error.cooldown_remaining",
                        "You can change your {0} class again in {1}.",
                        slotLabel,
                        formatDuration(remaining))).color("#ff6666"));
        return false;
    }

    private String tr(String key, String fallback, Object... args) {
        return Lang.tr(playerRef.getUuid(), key, fallback, args);
    }

    private String buildPassiveLabel(RacePassiveDefinition passive) {
        ArchetypePassiveType type = passive.type();
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN && passive.attributeType() != null) {
            return localizeAttributeName(passive.attributeType());
        }
        if (passive.attributeType() != null) {
            return tr("ui.races.passive.label.with_attribute",
                    "{0} ({1})",
                    localizePassiveType(type),
                    localizeAttributeName(passive.attributeType()));
        }
        return localizePassiveType(type);
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
        Double slowPercent = getDoubleProp(props, "slow_percent");
        String scalingStat = getStringProp(props, "scaling_stat");

        if (type == null) {
            return value == 0.0D ? tr("ui.races.passive.default_name", "Passive") : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> tr("ui.races.passive.desc.xp_bonus", "{0} XP gain", formatPercentValue(value));
            case HEALTH_REGEN -> tr("ui.races.passive.desc.health_regen", "{0} HP/5s", formatPercentValue(value));
            case MANA_REGEN -> tr("ui.races.passive.desc.mana_regen", "{0} mana/5s", formatPercentValue(value));
            case MANA_REGEN_FLAT -> tr("ui.races.passive.desc.mana_regen_flat", "{0} mana/s", formatSigned(value));
            case REGENERATION -> tr("ui.races.passive.desc.regeneration", "{0} HP/s", formatSigned(value));
            case HEALING_BONUS -> tr("ui.races.passive.desc.healing_bonus", "{0} healing", formatPercentValue(value));
            case LIFE_STEAL -> tr("ui.races.passive.desc.life_steal", "{0} life steal", formatPercentValue(value));
            case SPECIAL_CHARGE_BONUS ->
                tr("ui.races.passive.desc.charge_bonus", "{0} charge rate", formatPercentValue(value));
            case STAMINA_GAIN_BONUS ->
                tr("ui.races.passive.desc.stamina_gain", "{0} stamina gain", formatPercentValue(value));
            case LUCK -> tr("ui.races.passive.desc.luck", "{0} luck", formatPercentValue(value));
            case SECOND_WIND -> appendDetails(
                    tr("ui.races.passive.desc.second_wind", "{0} heal", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.hp", "HP")),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case FIRST_STRIKE -> appendDetails(
                    tr("ui.races.passive.desc.first_strike", "{0} opener", formatPercentValue(value)),
                    formatCooldownDetail(cooldown));
            case INNATE_ATTRIBUTE_GAIN -> formatInnatePreview(passive);
            case ADRENALINE -> appendDetails(
                    tr("ui.races.passive.desc.adrenaline", "{0} stamina", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.stamina", "stamina")),
                    formatDurationDetail(duration),
                    formatCooldownDetail(cooldown));
            case BERZERKER -> appendDetails(
                    tr("ui.races.passive.desc.berzerker", "{0} damage", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.hp", "HP")));
            case RETALIATION -> appendDetails(
                    tr("ui.races.passive.desc.retaliation", "{0} reflect", formatPercentValue(value)),
                    formatWindowDetail(window),
                    formatCooldownDetail(cooldown));
            case ABSORB -> appendDetails(
                    tr("ui.races.passive.desc.absorb", "{0} dmg reduction", formatPercentValue(value)),
                    formatCooldownDetail(cooldown));
            case EXECUTIONER -> appendDetails(
                    tr("ui.races.passive.desc.executioner", "{0} finisher", formatPercentValue(value)),
                    formatThresholdDetail(threshold, tr("ui.races.passive.scope.target_hp", "target HP")),
                    formatCooldownDetail(cooldown));
            case SWIFTNESS -> appendDetails(
                    tr("ui.races.passive.desc.swiftness", "{0} speed", formatPercentValue(value)),
                    formatDurationDetail(duration),
                    formatStacksDetail(stacks));
            case WITHER -> appendDetails(
                    tr("ui.races.passive.desc.wither", "{0} max HP/sec", formatPercentValue(value)),
                    formatDurationDetail(duration),
                    formatSlowDetail(slowPercent));
            case CRIT_DEFENSE -> appendDetails(
                    tr("ui.races.passive.desc.crit_defense", "{0} dmg reduction", formatPercentValue(value)),
                    formatScalingDetail(scalingStat));
            default -> formatSigned(value);
        };
    }

    private String formatSlowDetail(Double slowPercent) {
        if (slowPercent == null) {
            return null;
        }
        return tr("ui.races.passive.detail.slow", "{0} slow", formatPercentValue(slowPercent));
    }

    private String formatScalingDetail(String scalingStat) {
        if (scalingStat == null || scalingStat.isBlank()) {
            return null;
        }
        return tr("ui.races.passive.detail.scales_with", "scales with {0}", localizeAttributeName(scalingStat));
    }

    private String getStringProp(Map<String, Object> props, String key) {
        Object raw = props.get(key);
        if (raw instanceof String str) {
            String trimmed = str.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private String formatInnatePreview(RacePassiveDefinition passive) {
        double perLevel = passive.value();
        String perLevelText = tr("ui.races.passive.detail.per_level", "{0} per level", formatSigned(perLevel));
        int cap = levelingManager != null ? Math.max(1, levelingManager.getLevelCap()) : 1;
        double total = perLevel * cap;
        String totalText = formatSigned(total);
        return tr("ui.races.passive.detail.total_at_level", "{0} (Total {1} @ Lv {2})", perLevelText, totalText,
                cap);
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
            return tr("ui.classes.cooldown.ready", "Ready");
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
        return tr("ui.classes.passive.detail.threshold", "<{0}% {1}", formatNumber(ratio * 100.0D), scope);
    }

    private String formatDurationDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.duration", "{0}s duration", formatNumber(seconds));
    }

    private String formatCooldownDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.cooldown", "{0}s cd", formatNumber(seconds));
    }

    private String formatWindowDetail(Double seconds) {
        if (seconds == null) {
            return null;
        }
        return tr("ui.races.passive.detail.window", "{0}s window", formatNumber(seconds));
    }

    private String formatStacksDetail(Double stacks) {
        if (stacks == null) {
            return null;
        }
        return tr("ui.races.passive.detail.stacks", "{0} stacks", formatNumber(stacks));
    }

    private String formatWeaponMultiplier(double multiplier) {
        double delta = (multiplier - 1.0D) * 100.0D;
        if (Math.abs(delta) < 0.0001D) {
            return tr("ui.classes.value.weapon_damage", "+{0}% dmg", 0);
        }
        if (delta > 0) {
            return tr("ui.classes.value.weapon_damage", "+{0}% dmg", formatNumber(delta));
        }
        return tr("ui.classes.value.weapon_damage_negative", "-{0}% dmg", formatNumber(Math.abs(delta)));
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
            return tr("ui.time.seconds", "{0}s", 0);
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(tr("ui.time.hours", "{0}h", hours));
        }
        if (minutes > 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tr("ui.time.minutes", "{0}m", minutes));
        }
        if (remainingSeconds > 0 || builder.length() == 0) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tr("ui.time.seconds", "{0}s", remainingSeconds));
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

    private String localizeWeaponType(ClassWeaponType type) {
        if (type == null) {
            return tr("hud.class.none", "None");
        }
        String keySuffix = type.name().toLowerCase(Locale.ROOT);
        return tr("ui.classes.weapon." + keySuffix, toDisplay(type.name()));
    }

    private String localizePassiveType(ArchetypePassiveType type) {
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        String keySuffix = type.name().toLowerCase(Locale.ROOT);
        return tr("ui.races.passive.type." + keySuffix, toDisplay(type.name()));
    }

    private String localizeAttributeName(SkillAttributeType type) {
        if (type == null) {
            return tr("ui.races.passive.default_name", "Passive");
        }
        return tr("ui.skills.label." + type.getConfigKey(), toDisplay(type.name()));
    }

    private String localizeAttributeName(String rawAttribute) {
        if (rawAttribute == null || rawAttribute.isBlank()) {
            return "";
        }
        SkillAttributeType attributeType = SkillAttributeType.fromConfigKey(rawAttribute);
        if (attributeType != null) {
            return localizeAttributeName(attributeType);
        }
        return toDisplay(rawAttribute);
    }
}
