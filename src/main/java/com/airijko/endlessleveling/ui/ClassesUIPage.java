package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.WeaponConfig;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassAssignmentSlot;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.enums.themes.EvolutionRequirementTheme;
import com.airijko.endlessleveling.enums.themes.EvolutionStatusTheme;
import com.airijko.endlessleveling.classes.ClassManager;
import com.airijko.endlessleveling.leveling.LevelingManager;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.systems.PlayerRaceStatSystem;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.OperatorHelper;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * UI page for browsing and selecting EndlessLeveling classes.
 */
public class ClassesUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final Map<String, String> DEFAULT_CLASS_ICONS = new HashMap<>();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String EVOLUTION_ENTRY_TEMPLATE = "Pages/Classes/ClassEvolutionEntry.ui";

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
        DEFAULT_CLASS_ICONS.put("slayer", "Weapon_Spear_Tribal");
        DEFAULT_CLASS_ICONS.put("vanguard", "Weapon_Mace_Prisma");
        DEFAULT_CLASS_ICONS.put("example", "Potion_Health");
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Classes/ClassesPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef, "classes",
            "Common/UI/Custom/Pages/Classes/ClassesPage.ui",
            "#ClassTitle");
        applyStaticLabels(ui);
        NavUIHelper.bindNavEvents(events);

        events.addEventBinding(Activating, "#ConfirmPrimaryButton", of("Action", "class:confirm_primary"), false);
        events.addEventBinding(Activating, "#ConfirmSecondaryButton", of("Action", "class:confirm_secondary"), false);
        events.addEventBinding(Activating, "#ViewClassPathsButton", of("Action", "class:open_paths"), false);

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
            ui.clear("#ClassEvolutionEntries");
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
            ui.clear("#ClassEvolutionEntries");
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

        List<CharacterClassDefinition> classes = getBaseClassesForList();
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
        List<CharacterClassDefinition> classes = getBaseClassesForList();
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
            tr("ui.classes.page.secondary_share_hint", "Secondary classes share 50% of passive bonuses."));
        ui.set("#ClassSorceryLabel.Text", tr("ui.classes.page.sorcery_label", "Sorcery Bonus"));
        ui.set("#ClassLoreTitle.Text", tr("ui.classes.page.lore_title", "Lore Preview"));
        ui.set("#ClassWeaponsTitle.Text", tr("ui.classes.page.weapons_title", "Weapon Affinities"));
        ui.set("#ClassInnateTitle.Text", tr("ui.classes.page.innate_title", "Innate Attribute Gains"));
        ui.set("#ClassPassivesTitle.Text", tr("ui.classes.page.passives_title", "Passives"));
        ui.set("#ViewClassPathsButton.Text", tr("ui.classes.actions.view_paths", "VIEW PATHS"));
        ui.set("#ConfirmPrimaryButton.Text", tr("ui.classes.actions.set_primary", "SET PRIMARY"));
        ui.set("#ConfirmSecondaryButton.Text", tr("ui.classes.actions.set_secondary", "SET SECONDARY"));
        ui.set("#ClassEvolutionTitle.Text", tr("ui.classes.page.evolution_title", "Class Paths"));
        ui.set("#ClassEvolutionSummary.Text",
                tr("ui.classes.evolution.summary.placeholder", "Select a class to inspect ascension branches."));
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
            ui.set("#ViewClassPathsButton.Visible", false);
            ui.set("#ClassEvolutionSummary.Text", tr("ui.classes.evolution.none_selected", "No class selected."));
            ui.clear("#ClassEvolutionEntries");

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
        ui.set("#ViewClassPathsButton.Visible", true);
        if (primary != null && primary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text", tr("ui.classes.subtitle.primary", "Currently your primary class"));
        } else if (secondary != null && secondary.getId().equalsIgnoreCase(selection.getId())) {
            ui.set("#SelectedClassSubtitle.Text",
                    tr("ui.classes.subtitle.secondary", "Currently your secondary class"));
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
        updateClassEvolutionPreview(ui, data, selection, primary);

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
                    "Secondary classes grant 50% of passive effects."));
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
        Map<String, Double> weaponMap = selection.getWeaponMultipliers();
        ui.clear("#ClassWeaponEntries");
        if (weaponMap == null || weaponMap.isEmpty()) {
            ui.set("#ClassWeaponSummary.Visible", true);
            ui.set("#ClassWeaponSummary.Text",
                    tr("ui.classes.weapons.none_defined", "This class does not define weapon bonuses."));
            return;
        }
        ui.set("#ClassWeaponSummary.Visible", false);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weaponMap.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<String, Double> e) -> e.getValue()).reversed());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Double> entry = entries.get(i);
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
            ui.append(entriesSelector, "Pages/Classes/ClassPassiveEntry.ui");
            String base = entriesSelector + "[" + index + "]";
            String label = buildPassiveLabel(passive);
            String value = formatPassiveDescription(passive, data);
            ui.set(base + " #PassiveName.Text", label);
            ui.set(base + " #PassiveValue.Text", trimDuplicatePassiveHeader(label, value));
        }
    }

    private String trimDuplicatePassiveHeader(String label, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalizedLabel = normalizePassiveText(label);
        if (normalizedLabel.isEmpty()) {
            return value;
        }

        int newline = value.indexOf('\n');
        String firstLine = (newline >= 0 ? value.substring(0, newline) : value).trim();
        if (!normalizePassiveText(firstLine).equals(normalizedLabel)) {
            return value;
        }

        if (newline < 0) {
            return value;
        }

        String remaining = value.substring(newline + 1).stripLeading();
        return remaining.isBlank() ? value : remaining;
    }

    private String normalizePassiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toLowerCase(text.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private boolean selectedClassMatches(String classId) {
        return selectedClassId != null && selectedClassId.equalsIgnoreCase(classId);
    }

    private List<CharacterClassDefinition> getBaseClassesForList() {
        List<CharacterClassDefinition> classes = new ArrayList<>();
        for (CharacterClassDefinition definition : classManager.getLoadedClasses()) {
            if (definition == null) {
                continue;
            }
            String stage = definition.getAscension() != null ? definition.getAscension().getStage() : null;
            if (stage == null || stage.isBlank() || "base".equalsIgnoreCase(stage.trim())) {
                classes.add(definition);
            }
        }
        classes.sort(Comparator.comparing(def -> def.getDisplayName().toLowerCase(Locale.ROOT)));
        return classes;
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

        if (data.action.equals("class:open_paths")) {
            openClassPathsPage(ref, store,
                    classManager == null ? null : classManager.getPlayerPrimaryClass(playerData));
            return;
        }

        switch (data.action) {
            case "class:confirm_primary" -> handlePrimarySelection(selectedClassId, playerData, ref, store);
            case "class:confirm_secondary" -> handleSecondarySelection(selectedClassId, playerData, ref, store);
            case "class:clear_secondary" -> clearSecondary(playerData, ref, store);
        }
    }

    private void openClassPathsPage(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            CharacterClassDefinition primaryClass) {
        if (classManager == null || !classManager.isEnabled()) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.error.disabled", "Classes are currently disabled.")).color("#ff6666"));
            return;
        }

        CharacterClassDefinition selection = resolveSelection(primaryClass);
        if (selection == null) {
            playerRef.sendMessage(
                    Message.raw(tr("ui.classes.none_selected", "No class selected.")).color("#ff9900"));
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        player.getPageManager().openCustomPage(ref, store,
                new ClassPathsUIPage(playerRef, CustomPageLifetime.CanDismiss, selection.getId()));
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
        if (current != null) {
            data.addCompletedClassForm(classManager.resolveAscensionPathId(current.getId()));
        }
        CharacterClassDefinition applied = classManager.setPlayerPrimaryClass(data, desired.getId());
        if (applied != null) {
            data.addCompletedClassForm(classManager.resolveAscensionPathId(applied.getId()));
        }
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
        if (currentSecondary != null) {
            data.addCompletedClassForm(classManager.resolveAscensionPathId(currentSecondary.getId()));
        }
        CharacterClassDefinition applied = classManager.setPlayerSecondaryClass(data, desired.getId());
        if (applied == null) {
            playerRef.sendMessage(Message
                    .raw(tr("ui.classes.error.secondary_set_failed", "Unable to set that as your secondary class."))
                    .color("#ff6666"));
            return;
        }
        data.addCompletedClassForm(classManager.resolveAscensionPathId(applied.getId()));
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
        Map<String, Object> props = passive.properties() == null ? Map.of() : passive.properties();
        String customName = getStringProp(props, "display_name");
        if (customName == null) {
            customName = getStringProp(props, "name");
        }
        if (customName != null && !customName.isBlank()) {
            return customName;
        }

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
        if (threshold == null) {
            threshold = getDoubleProp(props, "threshold_percent");
        }
        Double duration = getDoubleProp(props, "duration");
        if (duration == null) {
            duration = getDoubleProp(props, "target_haste_slow_duration");
        }
        Double restoreDuration = getDoubleProp(props, "restore_duration_seconds");
        if (restoreDuration == null) {
            restoreDuration = duration;
        }
        Double cooldown = getDoubleProp(props, "cooldown");
        Double window = getDoubleProp(props, "window");
        Double stacks = getDoubleProp(props, "max_stacks");
        Double flatTrueDamage = getDoubleProp(props, "flat_true_damage");
        if (flatTrueDamage == null) {
            flatTrueDamage = value;
        }
        Double trueDamagePercent = getDoubleProp(props, "true_damage_percent");
        Double maxHealthTrueDamage = getDoubleProp(props, "max_health_true_damage_percent");
        if (maxHealthTrueDamage == null) {
            maxHealthTrueDamage = getDoubleProp(props, "max_health_true_damage");
        }
        Double baseSummonAmount = getDoubleProp(props, "base_summon_amount");
        if (baseSummonAmount == null) {
            baseSummonAmount = getDoubleProp(props, "base_summons");
        }
        Double manaPerSummon = getDoubleProp(props, "mana_per_summon");
        if (manaPerSummon == null) {
            manaPerSummon = getNestedDoubleProp(props, "summon_amount_scaling", "mana_per_summon");
        }
        if (manaPerSummon == null) {
            manaPerSummon = getNestedDoubleProp(props, "summon_amount_scaling", "mana_ratio");
        }
        Double summonLifetime = getDoubleProp(props, "minion_lifetime_seconds");
        if (summonLifetime == null) {
            summonLifetime = getDoubleProp(props, "lifetime_seconds");
        }
        Double summonCooldown = getDoubleProp(props, "cooldown_seconds");
        if (summonCooldown == null) {
            summonCooldown = cooldown;
        }
        Double summonStatInheritance = getDoubleProp(props, "summon_stat_inheritance");
        if (summonStatInheritance == null) {
            summonStatInheritance = getDoubleProp(props, "stat_inheritance");
        }
        Double maxSummons = getDoubleProp(props, "max_summons");
        if (maxSummons == null) {
            maxSummons = getDoubleProp(props, "summon_cap");
        }
        Double slowPercent = getDoubleProp(props, "slow_percent");
        if (slowPercent == null) {
            slowPercent = getDoubleProp(props, "target_haste_slow_on_hit");
        }
        Double healingChance = getDoubleProp(props, "healing_chance");
        Double selfHealEffectiveness = getDoubleProp(props, "self_heal_effectiveness");
        if (selfHealEffectiveness == null) {
            selfHealEffectiveness = getDoubleProp(props, "self_heal_ratio");
        }
        Double selfShieldEffectiveness = getDoubleProp(props, "self_shield_effectiveness");
        if (selfShieldEffectiveness == null) {
            selfShieldEffectiveness = getDoubleProp(props, "self_shield_ratio");
        }
        Double maxBuffPerAlly = getDoubleProp(props, "max_buffed_value_per_ally");
        if (maxBuffPerAlly == null) {
            maxBuffPerAlly = getDoubleProp(props, "max_buff_per_ally");
        }
        Double selfBuffEffectiveness = getDoubleProp(props, "self_buff_effectiveness");
        if (selfBuffEffectiveness == null) {
            selfBuffEffectiveness = getDoubleProp(props, "self_buff_ratio");
        }
        Double restorePercent = getDoubleProp(props, "restore_percent");
        if (restorePercent == null) {
            restorePercent = getDoubleProp(props, "restore_ratio");
        }
        if (restorePercent == null) {
            restorePercent = value;
        }
        Double flatBonusDamage = getDoubleProp(props, "flat_bonus_damage");
        Double trueDamageFlatBonus = getDoubleProp(props, "true_damage_flat_bonus");
        Double trueDamageConversionPercent = getDoubleProp(props, "true_damage_conversion_percent");
        Double damageBonusPerStack = getDoubleProp(props, "damage_bonus_per_stack");
        if (damageBonusPerStack == null) {
            damageBonusPerStack = getDoubleProp(props, "damage_bonus_percent_per_stack");
        }
        if (damageBonusPerStack == null) {
            damageBonusPerStack = getDoubleProp(props, "damage_bonus_percent");
        }
        Boolean triggerOnHit = getBooleanProp(props, "trigger_on_hit");
        Double strengthFromTotalHealthPercent = getDoubleProp(props, "strength_from_total_health_percent");
        if (strengthFromTotalHealthPercent == null) {
            strengthFromTotalHealthPercent = value;
        }
        Double sorceryFromTotalHealthPercent = getDoubleProp(props, "sorcery_from_total_health_percent");
        if (sorceryFromTotalHealthPercent == null) {
            sorceryFromTotalHealthPercent = value;
        }
        String activation = getStringProp(props, "activation");
        String scalingStat = getStringProp(props, "scaling_stat");
        String sourceAttribute = getStringProp(props, "source_attribute");

        if (type == null) {
            return value == 0.0D ? tr("ui.races.passive.default_name", "Passive") : formatSigned(value);
        }

        return switch (type) {
            case XP_BONUS -> tr("ui.races.passive.desc.xp_bonus", "{0} XP gain", formatPercentValue(value));
            case HEALTH_REGEN -> tr("ui.races.passive.desc.health_regen", "{0} HP/5s", formatPercentValue(value));
            case MANA_REGEN -> tr("ui.races.passive.desc.mana_regen", "{0} mana/5s", formatPercentValue(value));
            case MANA_REGEN_FLAT -> tr("ui.races.passive.desc.mana_regen_flat", "{0} mana/s", formatSigned(value));
                case ARCANE_WISDOM -> appendLines(
                    tr("ui.classes.passive.pretty.arcane_wisdom.title", "Arcane Wisdom"),
                    restorePercent == null
                        ? null
                        : threshold == null
                            ? tr("ui.classes.passive.pretty.arcane_wisdom.restore_only",
                                "- Emergency restore: {0} mana over {1}",
                                formatPercentValue(restorePercent),
                                formatDurationDetail(restoreDuration))
                            : tr("ui.classes.passive.pretty.arcane_wisdom.restore_threshold",
                                "- Emergency restore: {0} mana over {1} when below {2}",
                                formatPercentValue(restorePercent),
                                formatDurationDetail(restoreDuration),
                                formatThresholdPercent(threshold, "mana")),
                    cooldown == null
                        ? null
                        : tr("ui.classes.passive.pretty.arcane_wisdom.cooldown",
                                "- Cooldown: {0}",
                                formatCooldownDetail(cooldown)));
                case TRUE_EDGE -> appendLines(
                    tr("ui.classes.passive.pretty.true_edge.title", "Defense-piercing strikes"),
                    flatTrueDamage == null || flatTrueDamage <= 0.0D
                            ? null
                            : tr("ui.classes.passive.pretty.true_edge.flat",
                                    "- Flat true damage: {0} per hit",
                                    formatSigned(flatTrueDamage)),
                    trueDamagePercent == null || trueDamagePercent <= 0.0D
                            ? null
                            : tr("ui.classes.passive.pretty.true_edge.ratio",
                                    "- Bonus true damage: {0} of pre-defense hit damage",
                                    formatPercentValue(trueDamagePercent)),
                    tr("ui.classes.passive.pretty.true_edge.note",
                            "- Applies as direct health loss after the hit"));
                            case TRUE_BOLTS -> appendLines(
                                tr("ui.classes.passive.pretty.true_bolts.title", "True Bolts"),
                                flatTrueDamage == null || flatTrueDamage <= 0.0D
                                    ? null
                                    : tr("ui.classes.passive.pretty.true_edge.flat",
                                        "- Flat true damage: {0} per hit",
                                        formatSigned(flatTrueDamage)),
                                trueDamagePercent == null || trueDamagePercent <= 0.0D
                                    ? null
                                    : tr("ui.classes.passive.pretty.true_edge.ratio",
                                        "- Bonus true damage: {0} of pre-defense hit damage",
                                        formatPercentValue(trueDamagePercent)),
                                maxHealthTrueDamage == null || maxHealthTrueDamage <= 0.0D
                                    ? null
                                    : tr("ui.classes.passive.pretty.true_bolts.max_health",
                                        "- Bonus true damage: {0} of target max health",
                                        formatPercentValue(maxHealthTrueDamage)),
                                tr("ui.classes.passive.pretty.true_edge.note",
                                    "- Applies as direct health loss after the hit"));
            case ARMY_OF_THE_DEAD -> appendLines(
                    tr("ui.classes.passive.pretty.army_of_the_dead.title", "Undead summon command"),
                    baseSummonAmount == null
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.base",
                                    "- Base summons: {0}",
                                    formatNumber(baseSummonAmount)),
                    manaPerSummon == null
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.mana_scaling",
                                    "- Scaling: +1 summon per {0} mana",
                                    formatNumber(manaPerSummon)),
                    maxSummons == null || maxSummons <= 0.0D
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.cap",
                                    "- Summon cap: {0}",
                                    formatNumber(maxSummons)),
                    activation == null || activation.isBlank()
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.trigger",
                                    "- Trigger: {0}",
                                    "on_hit".equalsIgnoreCase(activation)
                                            ? tr("ui.classes.passive.pretty.army_of_the_dead.trigger_on_hit", "On hit")
                                            : toDisplay(activation)),
                    summonLifetime == null
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.lifetime",
                                    "- Lifetime: {0}s each",
                                    formatNumber(summonLifetime)),
                    summonCooldown == null
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.cooldown",
                                    "- Respawn cooldown: {0}s per summon",
                                    formatNumber(summonCooldown)),
                    summonStatInheritance == null
                            ? null
                            : tr("ui.classes.passive.pretty.army_of_the_dead.inheritance",
                                    "- Stat inheritance: {0}% of your stats",
                                    formatNumber(summonStatInheritance * 100.0D)));
            case HEALING_TOUCH -> appendLines(
                    tr("ui.classes.passive.pretty.healing_touch.title", "On-hit burst heal"),
                    tr("ui.classes.passive.pretty.healing_touch.amount", "- Heal: {0} of source",
                            formatPercentValue(value)),
                    healingChance == null ? null
                            : tr("ui.classes.passive.pretty.healing_touch.chance", "- Trigger: {0}",
                                    formatPercentValue(healingChance)),
                    tr("ui.classes.passive.pretty.healing_touch.self",
                            "- Self effectiveness: {0}",
                            selfHealEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfHealEffectiveness)),
                    sourceAttribute == null || sourceAttribute.isBlank() ? null
                            : tr("ui.classes.passive.pretty.healing_touch.source", "- Source: {0}",
                                    localizeAttributeName(sourceAttribute)));
            case HEALING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.healing_aura.title", "Party healing pulse"),
                    tr("ui.classes.passive.pretty.healing_aura.effect", "- Heals from mana + stamina"),
                    tr("ui.classes.passive.pretty.healing_aura.scope", "- Party-only, radius-scaled"),
                    tr("ui.classes.passive.pretty.healing_aura.self",
                            "- Self effectiveness: {0}",
                            selfHealEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfHealEffectiveness)));
            case SHIELDING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.shielding_aura.title", "Party shielding aura"),
                    tr("ui.classes.passive.pretty.shielding_aura.effect", "- Shields from flat + stamina"),
                    tr("ui.classes.passive.pretty.shielding_aura.self",
                            "- Self effectiveness: {0}",
                            selfShieldEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfShieldEffectiveness)),
                    "on_hit".equalsIgnoreCase(activation)
                            ? tr("ui.classes.passive.pretty.shielding_aura.trigger_on_hit",
                                    "- On-hit trigger, then duration/cooldown")
                            : tr("ui.classes.passive.pretty.shielding_aura.trigger_always",
                                    "- Auto pulse with duration/cooldown"));
            case BUFFING_AURA -> appendLines(
                    tr("ui.classes.passive.pretty.buffing_aura.title", "Party buffing aura"),
                    tr("ui.classes.passive.pretty.buffing_aura.effect", "- Shares stamina-based damage"),
                    tr("ui.classes.passive.pretty.buffing_aura.cap",
                            "- Bonus damage cap: {0}",
                            maxBuffPerAlly == null ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(maxBuffPerAlly)),
                    tr("ui.classes.passive.pretty.buffing_aura.self",
                            "- Self effectiveness: {0}",
                            selfBuffEffectiveness == null
                                    ? tr("ui.classes.passive.pretty.na", "n/a")
                                    : formatPercentValue(selfBuffEffectiveness)));
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
                case FOCUSED_STRIKE -> appendLines(
                    tr("ui.classes.passive.pretty.focused_strike.title", "Focused Strike"),
                    tr("ui.classes.passive.pretty.focused_strike.bonus",
                        "- First-hit bonus: {0} bonus damage",
                        formatPercentValue(value)),
                    flatBonusDamage == null || flatBonusDamage <= 0.0D
                        ? null
                        : tr("ui.classes.passive.pretty.focused_strike.flat",
                            "- Flat bonus damage: {0}",
                            formatSigned(flatBonusDamage)),
                    trueDamageFlatBonus == null || trueDamageFlatBonus <= 0.0D
                        ? null
                        : tr("ui.classes.passive.pretty.focused_strike.true_flat",
                            "- Bonus true damage: {0}",
                            formatSigned(trueDamageFlatBonus)),
                    trueDamageConversionPercent == null || trueDamageConversionPercent <= 0.0D
                        ? null
                        : tr("ui.classes.passive.pretty.focused_strike.true_conversion",
                            "- True-damage conversion: {0} of hit damage",
                            formatPercentValue(trueDamageConversionPercent)),
                    cooldown == null
                        ? null
                        : tr("ui.classes.passive.pretty.focused_strike.cooldown",
                            "- Cooldown: {0}s",
                            formatNumber(cooldown)));
            case INNATE_ATTRIBUTE_GAIN -> formatInnatePreview(passive, playerData);
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
                case PRIMAL_DOMINANCE -> appendLines(
                    tr("ui.classes.passive.pretty.primal_dominance.title", "Primal Dominance"),
                    tr("ui.classes.passive.pretty.primal_dominance.scaling",
                        "- Strength bonus: {0} of total health",
                        formatPercentValue(strengthFromTotalHealthPercent)),
                    slowPercent == null
                        ? null
                        : tr("ui.classes.passive.pretty.primal_dominance.slow",
                            "- On-hit debuff: -{0} haste",
                            formatPercentMagnitude(slowPercent)),
                    duration == null
                        ? null
                        : tr("ui.classes.passive.pretty.primal_dominance.duration",
                            "- Debuff duration: {0}s",
                            formatNumber(duration)));
                case ARCANE_DOMINANCE -> appendLines(
                    tr("ui.classes.passive.pretty.arcane_dominance.title", "Arcane Dominance"),
                    tr("ui.classes.passive.pretty.arcane_dominance.scaling",
                        "- Sorcery bonus: {0} of total health",
                        formatPercentValue(sorceryFromTotalHealthPercent)),
                    slowPercent == null
                        ? null
                        : tr("ui.classes.passive.pretty.arcane_dominance.slow",
                            "- On-hit debuff: -{0} haste",
                            formatPercentMagnitude(slowPercent)),
                    duration == null
                        ? null
                        : tr("ui.classes.passive.pretty.arcane_dominance.duration",
                            "- Debuff duration: {0}s",
                            formatNumber(duration)));
            case ABSORB -> appendDetails(
                    tr("ui.races.passive.desc.absorb", "{0} dmg reduction", formatPercentValue(value)),
                    formatCooldownDetail(cooldown));
                case FINAL_INCANTATION -> appendLines(
                    tr("ui.classes.passive.pretty.final_incantation.title", "Final Incantation"),
                    tr("ui.classes.passive.pretty.final_incantation.bonus",
                        "- Bonus damage: {0} to low-health targets",
                        formatPercentValue(value)),
                    threshold == null
                        ? null
                        : tr("ui.classes.passive.pretty.final_incantation.threshold",
                            "- Trigger: target below {0}",
                            formatThresholdPercent(threshold, "target HP")),
                    cooldown == null
                        ? null
                        : tr("ui.classes.passive.pretty.final_incantation.cooldown",
                            "- Cooldown: {0}s",
                            formatNumber(cooldown)));
            case SWIFTNESS -> appendDetails(
                    tr("ui.races.passive.desc.swiftness", "{0} speed", formatPercentValue(value)),
                    formatDurationDetail(duration),
                    formatStacksDetail(stacks));
                case BLADE_DANCE -> appendLines(
                    tr("ui.classes.passive.pretty.blade_dance.title", "Blade Dance"),
                    tr("ui.classes.passive.pretty.blade_dance.speed",
                        "- Move speed per stack: {0}",
                        formatPercentValue(value)),
                    damageBonusPerStack == null || damageBonusPerStack <= 0.0D
                        ? null
                        : tr("ui.classes.passive.pretty.blade_dance.damage",
                            "- Damage per stack: {0}",
                            formatPercentValue(damageBonusPerStack)),
                    stacks == null
                        ? null
                        : tr("ui.classes.passive.pretty.blade_dance.stacks",
                            "- Max stacks: {0}",
                            formatNumber(stacks)),
                    duration == null
                        ? null
                        : tr("ui.classes.passive.pretty.blade_dance.duration",
                            "- Stack duration: {0}s",
                            formatNumber(duration)),
                    triggerOnHit == null
                        ? null
                        : triggerOnHit
                            ? tr("ui.classes.passive.pretty.blade_dance.trigger_hit", "- Trigger: on hit")
                            : tr("ui.classes.passive.pretty.blade_dance.trigger_other",
                                "- Trigger: passive events"));
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

    private Boolean getBooleanProp(Map<String, Object> props, String key) {
        Object raw = props.get(key);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        if (raw instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("yes") || trimmed.equals("1")) {
                return Boolean.TRUE;
            }
            if (trimmed.equalsIgnoreCase("false") || trimmed.equalsIgnoreCase("no") || trimmed.equals("0")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private String formatInnatePreview(RacePassiveDefinition passive, PlayerData playerData) {
        double perLevel = passive.value();
        String perLevelText = tr("ui.races.passive.detail.per_level", "{0} per level", formatSigned(perLevel));
        int cap = 1;
        if (levelingManager != null) {
            cap = playerData != null
                    ? Math.max(1, levelingManager.getLevelCap(playerData))
                    : Math.max(1, levelingManager.getLevelCap());
        }
        if (skillManager != null && passive.attributeType() != null) {
            cap = skillManager.applyClassInnateAttributeLevelCap(passive.attributeType(), cap);
        }
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

    private Double getNestedDoubleProp(Map<String, Object> props, String parentKey, String childKey) {
        Object parent = props.get(parentKey);
        if (!(parent instanceof Map<?, ?> nested)) {
            return null;
        }
        Object raw = nested.get(childKey);
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

    private String appendLines(String base, String... extra) {
        String detail = joinLines(extra);
        if (detail.isEmpty()) {
            return base;
        }
        return base + "\n" + detail;
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

    private String joinLines(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
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
        return tr("ui.classes.passive.detail.threshold",
                "<{0}% {1}",
                formatNumber(normalizePercentRatio(ratio) * 100.0D),
                scope);
    }

    private String formatThresholdPercent(Double ratio, String scope) {
        if (ratio == null) {
            return null;
        }
        return formatNumber(normalizePercentRatio(ratio) * 100.0D) + "% " + scope;
    }

    private String formatPercentMagnitude(Double ratio) {
        if (ratio == null) {
            return "0%";
        }
        return formatNumber(normalizePercentRatio(ratio) * 100.0D) + "%";
    }

    private double normalizePercentRatio(Double ratio) {
        if (ratio == null) {
            return 0.0D;
        }
        double normalized = Math.abs(ratio);
        if (normalized > 1.0D) {
            normalized = normalized / 100.0D;
        }
        return normalized;
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

    private String localizeWeaponType(String typeKey) {
        String normalized = WeaponConfig.normalizeCategoryKey(typeKey);
        if (normalized == null) {
            return tr("hud.class.none", "None");
        }
        return tr("ui.classes.weapon." + normalized, toDisplay(normalized));
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

    // -------------------------------------------------------------------------
    // Class evolution preview (mirrors RacesUIPage.updateEvolutionPreview)
    // -------------------------------------------------------------------------

    private void updateClassEvolutionPreview(@Nonnull UICommandBuilder ui,
            @Nonnull PlayerData data,
            @Nonnull CharacterClassDefinition selection,
            CharacterClassDefinition primaryClass) {
        RaceAscensionDefinition ascension = selection.getAscension();
        String stageLabel = ascension == null ? "" : toDisplay(ascension.getStage());
        if (stageLabel.isBlank()) {
            stageLabel = tr("ui.classes.evolution.stage.base", "Base");
        }
        String pathLabel = ascension == null ? "" : toDisplay(ascension.getPath());
        if (pathLabel.isBlank()) {
            pathLabel = tr("ui.classes.evolution.path.none", "None");
        }

        ui.clear("#ClassEvolutionEntries");
        List<ClassEvolutionNode> evolutionNodes = collectClassEvolutionNodes(selection);

        if (ascension != null && ascension.isFinalForm()) {
            ui.set("#ClassEvolutionSummary.Text",
                    tr("ui.classes.evolution.summary.final", "Stage: {0} - Final form reached.", stageLabel));
        } else if (evolutionNodes.isEmpty()) {
            ui.set("#ClassEvolutionSummary.Text",
                    tr("ui.classes.evolution.summary", "Stage: {0} - Path: {1}", stageLabel, pathLabel));
        } else {
            String pathTypes = collectClassPathTypeSummary(evolutionNodes);
            ui.set("#ClassEvolutionSummary.Text",
                    tr("ui.classes.evolution.summary.expanded",
                            "Stage: {0} - Path: {1} - Paths: {2} ({3})",
                            stageLabel, pathLabel, evolutionNodes.size(), pathTypes));
        }

        if (evolutionNodes.isEmpty()) {
            ui.append("#ClassEvolutionEntries", EVOLUTION_ENTRY_TEMPLATE);
            ui.set("#ClassEvolutionEntries[0] #EvolutionName.Text",
                    tr("ui.classes.evolution.none", "No further class paths"));
            ui.set("#ClassEvolutionEntries[0] #EvolutionStatus.Text",
                    tr("ui.classes.evolution.none_value", "FINAL"));
            ui.set("#ClassEvolutionEntries[0] #EvolutionStatus.Style.TextColor",
                    EvolutionStatusTheme.FINAL.statusColor());
            ui.set("#ClassEvolutionEntries[0] #EvolutionMeta.Text",
                    tr("ui.classes.evolution.none_meta", "This class is at the end of its ascension branch."));
            ui.set("#ClassEvolutionEntries[0] #EvolutionCriteria.Text",
                    tr("ui.classes.evolution.none_criteria", "No additional upgrade criteria."));
            ui.set("#ClassEvolutionEntries[0] #EvolutionCriteria.Style.TextColor",
                    EvolutionRequirementTheme.NEUTRAL.color());
            ui.set("#ClassEvolutionEntries[0] #EvolutionSkillCriteria.Text", "");
            ui.set("#ClassEvolutionEntries[0] #EvolutionSkillCriteria.Visible", false);
            ui.set("#ClassEvolutionEntries[0] #EvolutionProgress.Style.TextColor",
                    EvolutionRequirementTheme.NEUTRAL.color());
            ui.set("#ClassEvolutionEntries[0] #EvolutionProgress.Text", "");
            return;
        }

        int rowIndex = 0;
        for (ClassEvolutionNode node : evolutionNodes) {
            CharacterClassDefinition nextClass = node.classDef();
            if (nextClass == null) {
                continue;
            }

            RaceAscensionEligibility eligibility = classManager == null
                    ? null
                    : classManager.evaluateAscensionEligibility(data, selection.getId(), nextClass.getId(), false);

            boolean active = isActiveClassForm(nextClass, primaryClass);
            boolean unlocked = isClassFormCompleted(nextClass, primaryClass, data);
            boolean available = eligibility != null && eligibility.isEligible();
            boolean directPath = isDirectClassEvolutionPath(selection, nextClass);

            EvolutionStatusTheme statusTheme = resolveClassEvolutionStatusTheme(active, unlocked, available,
                    directPath);
            String statusLabel = localizeClassEvolutionStatus(statusTheme);
            ClassEvolutionCriteriaContent criteriaContent = buildClassEvolutionCriteriaContent(nextClass);

            String progressText;
            String progressColor = statusTheme.progressColor();
            if (active) {
                progressText = tr("ui.classes.evolution.progress.active", "Currently active class form.");
            } else if (unlocked) {
                progressText = tr("ui.classes.evolution.progress.unlocked", "Previously completed and unlocked.");
            } else if (eligibility == null) {
                progressText = tr("ui.classes.evolution.progress.unknown", "Unable to evaluate current progress.");
                progressColor = EvolutionRequirementTheme.NEUTRAL.color();
            } else if (eligibility.isEligible() && directPath) {
                progressText = tr("ui.classes.evolution.progress.ready", "All criteria met. Can switch immediately.");
                progressColor = EvolutionRequirementTheme.READY.color();
            } else if (eligibility.isEligible()) {
                progressText = tr("ui.classes.evolution.progress.ready_after",
                        "All criteria met. Reach this branch by progressing through earlier path nodes.");
                progressColor = EvolutionRequirementTheme.READY.color();
            } else {
                progressText = formatClassMissingProgress(eligibility, nextClass);
                progressColor = EvolutionRequirementTheme.MISSING.color();
            }

            ui.append("#ClassEvolutionEntries", EVOLUTION_ENTRY_TEMPLATE);
            String base = "#ClassEvolutionEntries[" + rowIndex + "]";
            ui.set(base + " #EvolutionName.Text", buildClassEvolutionLabel(nextClass));
            ui.set(base + " #EvolutionStatus.Text", statusLabel);
            ui.set(base + " #EvolutionStatus.Style.TextColor", statusTheme.statusColor());
            ui.set(base + " #EvolutionMeta.Text", buildClassEvolutionMeta(nextClass, node));
            ui.set(base + " #EvolutionCriteria.Text", criteriaContent.generalText());
            ui.set(base + " #EvolutionCriteria.Style.TextColor", EvolutionRequirementTheme.GENERAL.color());
            ui.set(base + " #EvolutionSkillCriteria.Text", criteriaContent.skillText());
            ui.set(base + " #EvolutionSkillCriteria.Visible", !criteriaContent.skillText().isBlank());
            ui.set(base + " #EvolutionSkillCriteria.Style.TextColor",
                    EvolutionRequirementTheme.TRAINABLE_SKILLS.color());
            ui.set(base + " #EvolutionProgress.Text", progressText);
            ui.set(base + " #EvolutionProgress.Style.TextColor", progressColor);
            rowIndex++;
        }
    }

    private EvolutionStatusTheme resolveClassEvolutionStatusTheme(boolean active,
            boolean unlocked, boolean available, boolean directPath) {
        if (active)
            return EvolutionStatusTheme.ACTIVE;
        if (unlocked)
            return EvolutionStatusTheme.UNLOCKED;
        if (available && directPath)
            return EvolutionStatusTheme.AVAILABLE;
        if (available)
            return EvolutionStatusTheme.ELIGIBLE;
        return EvolutionStatusTheme.LOCKED;
    }

    private String localizeClassEvolutionStatus(@Nonnull EvolutionStatusTheme theme) {
        return switch (theme) {
            case ACTIVE -> tr("ui.classes.evolution.status.active", "ACTIVE");
            case UNLOCKED -> tr("ui.classes.evolution.status.unlocked", "UNLOCKED");
            case AVAILABLE -> tr("ui.classes.evolution.status.available", "AVAILABLE");
            case ELIGIBLE -> tr("ui.classes.evolution.status.eligible", "ELIGIBLE");
            case FINAL -> tr("ui.classes.evolution.none_value", "FINAL");
            case LOCKED, UNKNOWN -> tr("ui.classes.evolution.status.locked", "LOCKED");
        };
    }

    private List<ClassEvolutionNode> collectClassEvolutionNodes(@Nonnull CharacterClassDefinition root) {
        if (classManager == null || root == null) {
            return List.of();
        }

        List<ClassEvolutionNode> nodes = new ArrayList<>();
        ArrayDeque<ClassEvolutionNode> frontier = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();

        for (CharacterClassDefinition next : classManager.getNextAscensionClasses(root.getId())) {
            if (next == null)
                continue;
            String key = classEvolutionPathKey(next);
            if (!seen.add(key))
                continue;
            ClassEvolutionNode node = new ClassEvolutionNode(next, 1, root.getId());
            frontier.addLast(node);
            nodes.add(node);
        }

        while (!frontier.isEmpty()) {
            ClassEvolutionNode current = frontier.removeFirst();
            for (CharacterClassDefinition child : classManager.getNextAscensionClasses(current.classDef().getId())) {
                if (child == null)
                    continue;
                String key = classEvolutionPathKey(child);
                if (!seen.add(key))
                    continue;
                ClassEvolutionNode childNode = new ClassEvolutionNode(child, current.depth() + 1,
                        current.classDef().getId());
                frontier.addLast(childNode);
                nodes.add(childNode);
            }
        }

        return nodes;
    }

    private String classEvolutionPathKey(@Nonnull CharacterClassDefinition classDef) {
        if (classDef == null)
            return "";
        String key = classManager != null ? classManager.resolveAscensionPathId(classDef.getId()) : classDef.getId();
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDirectClassEvolutionPath(@Nonnull CharacterClassDefinition source,
            @Nonnull CharacterClassDefinition target) {
        if (classManager == null)
            return false;
        for (CharacterClassDefinition candidate : classManager.getNextAscensionClasses(source.getId())) {
            if (candidate != null && candidate.getId().equalsIgnoreCase(target.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isActiveClassForm(@Nonnull CharacterClassDefinition candidate,
            CharacterClassDefinition primaryClass) {
        if (candidate == null || primaryClass == null)
            return false;
        String candidatePath = classManager == null ? candidate.getId()
                : classManager.resolveAscensionPathId(candidate.getId());
        String activePath = classManager == null ? primaryClass.getId()
                : classManager.resolveAscensionPathId(primaryClass.getId());
        return candidatePath != null && activePath != null && candidatePath.equalsIgnoreCase(activePath);
    }

    private boolean isClassFormCompleted(@Nonnull CharacterClassDefinition classDef,
            CharacterClassDefinition primaryClass, PlayerData data) {
        if (classDef == null)
            return false;
        if (isActiveClassForm(classDef, primaryClass))
            return true;
        if (data == null || classManager == null)
            return false;
        String pathId = classManager.resolveAscensionPathId(classDef.getId());
        if (pathId != null && data.hasCompletedClassForm(pathId))
            return true;
        return data.hasCompletedClassForm(classDef.getId());
    }

    private String buildClassEvolutionLabel(@Nonnull CharacterClassDefinition classDef) {
        String display = classDef.getDisplayName();
        if (display == null || display.isBlank())
            display = classDef.getId();
        RaceAscensionDefinition ascension = classDef.getAscension();
        if (ascension == null)
            return display;
        if (ascension.isFinalForm())
            return display + " (Final)";
        String pathLabel = toDisplay(ascension.getPath());
        return pathLabel.isBlank() ? display : display + " (" + pathLabel + ")";
    }

    private String buildClassEvolutionMeta(@Nonnull CharacterClassDefinition classDef,
            @Nonnull ClassEvolutionNode node) {
        String pathType = resolveClassPathType(classDef);
        RaceAscensionDefinition ascension = classDef.getAscension();
        String stage = ascension == null ? tr("ui.classes.evolution.stage.base", "Base")
                : toDisplay(ascension.getStage());
        if (stage.isBlank())
            stage = tr("ui.classes.evolution.stage.base", "Base");
        String parentName = resolveClassDisplayName(node.parentClassId());
        return tr("ui.classes.evolution.meta",
                "Type: {0} | Stage: {1} | Tier: {2} | From: {3}",
                pathType, stage, node.depth(), parentName);
    }

    private ClassEvolutionCriteriaContent buildClassEvolutionCriteriaContent(
            @Nonnull CharacterClassDefinition classDef) {
        RaceAscensionDefinition ascension = classDef.getAscension();
        RaceAscensionRequirements requirements = ascension == null
                ? RaceAscensionRequirements.none()
                : ascension.getRequirements();

        List<String> generalSections = new ArrayList<>();
        List<String> skillSections = new ArrayList<>();

        if (requirements.getRequiredPrestige() > 0) {
            generalSections.add(tr("ui.classes.evolution.criteria.prestige",
                    "- Prestige: >= {0}", requirements.getRequiredPrestige()));
        }

        String minSkillLine = formatClassSkillThresholds(requirements.getMinSkillLevels(), ">=");
        if (!minSkillLine.isBlank()) {
            skillSections.add(tr("ui.classes.evolution.criteria.min_skills",
                    "- Minimum skill levels: {0}", minSkillLine));
        }

        String maxSkillLine = formatClassSkillThresholds(requirements.getMaxSkillLevels(), "<=");
        if (!maxSkillLine.isBlank()) {
            skillSections.add(tr("ui.classes.evolution.criteria.max_skills",
                    "- Skill caps: {0}", maxSkillLine));
        }

        if (!requirements.getMinAnySkillLevels().isEmpty()) {
            List<String> anyGroups = new ArrayList<>();
            for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
                String renderedGroup = formatClassSkillThresholds(group, ">=");
                if (!renderedGroup.isBlank())
                    anyGroups.add(renderedGroup);
            }
            if (!anyGroups.isEmpty()) {
                skillSections.add(tr("ui.classes.evolution.criteria.any_skills",
                        "- Any one set: {0}", String.join(" OR ", anyGroups)));
            }
        }

        if (!requirements.getRequiredForms().isEmpty()) {
            List<String> forms = new ArrayList<>();
            for (String form : requirements.getRequiredForms()) {
                forms.add(resolveClassDisplayName(form));
            }
            generalSections.add(tr("ui.classes.evolution.criteria.forms",
                    "- Required forms: {0}", String.join(", ", forms)));
        }

        if (!requirements.getRequiredAnyForms().isEmpty()) {
            List<String> forms = new ArrayList<>();
            for (String form : requirements.getRequiredAnyForms()) {
                forms.add(resolveClassDisplayName(form));
            }
            generalSections.add(tr("ui.classes.evolution.criteria.any_forms",
                    "- Any one form: {0}", String.join(" OR ", forms)));
        }

        if (!requirements.getRequiredAugments().isEmpty()) {
            generalSections.add(tr("ui.classes.evolution.criteria.augments",
                    "- Required augments: {0}", String.join(", ", requirements.getRequiredAugments())));
        }

        if (generalSections.isEmpty() && skillSections.isEmpty()) {
            return new ClassEvolutionCriteriaContent(
                    tr("ui.classes.evolution.criteria.none", "Requirements: none"), "");
        }

        String generalText = generalSections.isEmpty()
                ? tr("ui.classes.evolution.criteria.general.none", "Requirements: skill targets only")
                : tr("ui.classes.evolution.criteria.general.prefix",
                        "Requirements:\n{0}", String.join("\n", generalSections));

        String skillText = skillSections.isEmpty() ? ""
                : tr("ui.classes.evolution.criteria.skills.prefix",
                        "Trainable Skill Targets:\n{0}", String.join("\n", skillSections));

        return new ClassEvolutionCriteriaContent(generalText, skillText);
    }

    private String formatClassSkillThresholds(Map<SkillAttributeType, Integer> thresholds, String operator) {
        if (thresholds == null || thresholds.isEmpty())
            return "";
        List<Map.Entry<SkillAttributeType, Integer>> entries = new ArrayList<>(thresholds.entrySet());
        entries.removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (entries.isEmpty())
            return "";
        entries.sort(Comparator.comparingInt(e -> e.getKey().ordinal()));
        List<String> rendered = new ArrayList<>();
        for (Map.Entry<SkillAttributeType, Integer> entry : entries) {
            rendered.add(localizeAttributeName(entry.getKey()) + " " + operator + " " + entry.getValue());
        }
        return String.join(", ", rendered);
    }

    private String formatClassMissingProgress(@Nonnull RaceAscensionEligibility eligibility,
            @Nonnull CharacterClassDefinition targetClass) {
        List<String> blockers = eligibility.getBlockers();
        if (blockers == null || blockers.isEmpty()) {
            return tr("ui.classes.evolution.progress.missing_generic", "Missing requirements.");
        }
        List<String> lines = new ArrayList<>();
        lines.add(tr("ui.classes.evolution.progress.missing_header", "Missing requirements:"));
        for (String blocker : blockers) {
            if (blocker == null || blocker.isBlank())
                continue;
            String normalized = blocker.trim();
            if (isAnySkillSetBlocker(normalized)) {
                String anySkillOptions = buildAnyClassSkillOptions(targetClass);
                if (!anySkillOptions.isBlank()) {
                    normalized = tr("ui.classes.evolution.progress.missing_any_skill_specific",
                            "Requires at least one skill option set to be met: {0}.", anySkillOptions);
                }
            }
            lines.add("- " + abbreviateText(normalized, 180));
        }
        if (lines.size() == 1) {
            lines.add(tr("ui.classes.evolution.progress.missing_generic", "- Requirement unmet."));
        }
        return String.join("\n", lines);
    }

    private boolean isAnySkillSetBlocker(@Nonnull String blocker) {
        String normalized = blocker.toLowerCase(Locale.ROOT);
        return normalized.contains("at least one or skill requirement set")
                || normalized.contains("at least one skill requirement set")
                || normalized.contains("any one skill requirement set")
                || normalized.contains("one or skill requirement set");
    }

    private String buildAnyClassSkillOptions(@Nonnull CharacterClassDefinition targetClass) {
        RaceAscensionDefinition ascension = targetClass.getAscension();
        RaceAscensionRequirements requirements = ascension == null
                ? RaceAscensionRequirements.none()
                : ascension.getRequirements();
        List<String> groups = new ArrayList<>();
        for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
            String rendered = formatClassSkillThresholds(group, ">=");
            if (!rendered.isBlank())
                groups.add(rendered);
        }
        return String.join(" OR ", groups);
    }

    private String resolveClassDisplayName(String classIdOrPathId) {
        if (classIdOrPathId == null || classIdOrPathId.isBlank()) {
            return tr("hud.common.unavailable", "--");
        }
        if (classManager != null) {
            CharacterClassDefinition direct = classManager.getClass(classIdOrPathId);
            if (direct != null && direct.getDisplayName() != null && !direct.getDisplayName().isBlank()) {
                return direct.getDisplayName();
            }
            String normalizedTarget = classIdOrPathId.trim().toLowerCase(Locale.ROOT);
            for (CharacterClassDefinition candidate : classManager.getLoadedClasses()) {
                if (candidate == null)
                    continue;
                String candidatePath = classManager.resolveAscensionPathId(candidate.getId());
                if (candidatePath != null && candidatePath.equalsIgnoreCase(normalizedTarget)) {
                    String displayName = candidate.getDisplayName();
                    return displayName == null || displayName.isBlank() ? candidate.getId() : displayName;
                }
            }
        }
        return toDisplay(classIdOrPathId);
    }

    private String collectClassPathTypeSummary(@Nonnull List<ClassEvolutionNode> nodes) {
        Set<String> uniqueTypes = new LinkedHashSet<>();
        for (ClassEvolutionNode node : nodes) {
            if (node == null || node.classDef() == null)
                continue;
            uniqueTypes.add(resolveClassPathType(node.classDef()));
        }
        if (uniqueTypes.isEmpty())
            return tr("ui.classes.evolution.path.none", "None");
        return String.join(", ", uniqueTypes);
    }

    private String resolveClassPathType(@Nonnull CharacterClassDefinition classDef) {
        RaceAscensionDefinition ascension = classDef.getAscension();
        if (ascension == null)
            return tr("ui.classes.evolution.path.none", "None");
        String path = ascension.getPath();
        if (path == null || path.isBlank() || "none".equalsIgnoreCase(path)) {
            return tr("ui.classes.evolution.path.none", "None");
        }
        return switch (path.trim().toLowerCase(Locale.ROOT)) {
            case "damage" -> tr("ui.classes.evolution.path.damage", "Damage");
            case "sorcery" -> tr("ui.classes.evolution.path.sorcery", "Sorcery");
            case "tank" -> tr("ui.classes.evolution.path.tank", "Tank");
            case "support" -> tr("ui.classes.evolution.path.support", "Support");
            default -> toDisplay(path);
        };
    }

    private String abbreviateText(String value, int maxLength) {
        if (value == null)
            return "";
        String normalized = value.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength)
            return normalized;
        if (maxLength <= 3)
            return normalized.substring(0, Math.max(0, maxLength));
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private static final class ClassEvolutionNode {
        private final CharacterClassDefinition classDef;
        private final int depth;
        private final String parentClassId;

        private ClassEvolutionNode(CharacterClassDefinition classDef, int depth, String parentClassId) {
            this.classDef = classDef;
            this.depth = Math.max(1, depth);
            this.parentClassId = parentClassId;
        }

        private CharacterClassDefinition classDef() {
            return classDef;
        }

        private int depth() {
            return depth;
        }

        private String parentClassId() {
            return parentClassId;
        }
    }

    private record ClassEvolutionCriteriaContent(String generalText, String skillText) {
    }
}
