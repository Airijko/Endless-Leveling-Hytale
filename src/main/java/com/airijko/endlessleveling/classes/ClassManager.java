package com.airijko.endlessleveling.classes;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.WeaponConfig;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassAssignmentSlot;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionEligibility;
import com.airijko.endlessleveling.races.RaceAscensionPathLink;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.passives.util.PassiveDefinitionParser;
import com.airijko.endlessleveling.managers.ConfigManager;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import com.airijko.endlessleveling.managers.PluginFilesManager;
import com.airijko.endlessleveling.managers.VersionRegistry;

/**
 * Loads and resolves EndlessLeveling character class definitions.
 */
public class ClassManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT = 10;
    private static final int SWAP_CONSUME_COUNT = 1;
    private static final double DEFAULT_OFF_CLASS_WEAPON_DAMAGE_PENALTY = -0.40D;

    private final PluginFilesManager filesManager;
    private final ConfigManager configManager;
    private final boolean classesEnabled;
    private boolean secondaryClassEnabled = true;
    private final boolean forceBuiltinClasses;
    private final boolean enableBuiltinClasses;
    private final Map<String, CharacterClassDefinition> fileClassesByKey = new LinkedHashMap<>();
    private final Map<String, CharacterClassDefinition> externalClassesByKey = new LinkedHashMap<>();
    private final Map<String, CharacterClassDefinition> classesByKey = new HashMap<>();
    private final Map<String, CharacterClassDefinition> classesByAscensionId = new HashMap<>();
    private final Map<String, List<String>> ascensionParentsByChild = new HashMap<>();
    private final Yaml yaml = new Yaml();

    private String defaultPrimaryClassId = PlayerData.DEFAULT_PRIMARY_CLASS_ID;
    private boolean hasConfiguredDefaultPrimaryClass = true;
    private String defaultSecondaryClassId = null;
    private double offClassWeaponDamageMultiplier = 1.0D + DEFAULT_OFF_CLASS_WEAPON_DAMAGE_PENALTY;
    private final double secondaryPassiveScale = 0.5D;
    private final long chooseClassCooldownSeconds;
    private final int maxClassSwitches;

    private void reloadDefaultsFromConfig() {
        if (configManager == null) {
            return;
        }

        this.secondaryClassEnabled = parseBoolean(configManager.get("enable_secondary_class", Boolean.TRUE, false),
                true);
        this.offClassWeaponDamageMultiplier = resolveOffClassWeaponDamageMultiplier(
            configManager.get("weapon_off_class_damage_penalty", "-40%", false));

        Object primaryNode = configManager.get("default_primary_class", defaultPrimaryClassId, false);
        if (isNoneLiteral(primaryNode)) {
            defaultPrimaryClassId = null;
            hasConfiguredDefaultPrimaryClass = false;
        } else {
            String configuredPrimary = parseConfiguredDefaultClass(primaryNode);
            if (configuredPrimary != null) {
                defaultPrimaryClassId = configuredPrimary;
                hasConfiguredDefaultPrimaryClass = true;
            }
        }

        Object secondaryNode = configManager.get("default_secondary_class", defaultSecondaryClassId, false);
        if (isNoneLiteral(secondaryNode)) {
            defaultSecondaryClassId = null;
        } else {
            String configuredSecondary = parseConfiguredDefaultClass(secondaryNode);
            defaultSecondaryClassId = configuredSecondary;
        }
    }

    public ClassManager(ConfigManager configManager, PluginFilesManager filesManager) {
        Objects.requireNonNull(configManager, "ConfigManager is required");
        this.filesManager = Objects.requireNonNull(filesManager, "PluginFilesManager is required");
        this.configManager = configManager;
        this.classesEnabled = parseBoolean(configManager.get("enable_classes", Boolean.TRUE, false), true);
        this.secondaryClassEnabled = parseBoolean(configManager.get("enable_secondary_class", Boolean.TRUE, false),
                true);
        this.offClassWeaponDamageMultiplier = resolveOffClassWeaponDamageMultiplier(
            configManager.get("weapon_off_class_damage_penalty", "-40%", false));
        this.forceBuiltinClasses = parseBoolean(configManager.get("force_builtin_classes", Boolean.FALSE, false),
                false);
        this.enableBuiltinClasses = parseBoolean(configManager.get("enable_builtin_classes", Boolean.TRUE, false),
                true);

        Object primaryConfig = configManager.get("default_primary_class", PlayerData.DEFAULT_PRIMARY_CLASS_ID, false);
        if (isNoneLiteral(primaryConfig)) {
            this.defaultPrimaryClassId = null;
            this.hasConfiguredDefaultPrimaryClass = false;
        } else {
            String configuredPrimary = parseConfiguredDefaultClass(primaryConfig);
            if (configuredPrimary != null) {
                this.defaultPrimaryClassId = configuredPrimary;
                this.hasConfiguredDefaultPrimaryClass = true;
            }
        }

        Object secondaryConfig = configManager.get("default_secondary_class", null, false);
        this.defaultSecondaryClassId = isNoneLiteral(secondaryConfig)
                ? null
                : parseConfiguredDefaultClass(secondaryConfig);

        Object classCooldownConfig = configManager.get("choose_class_cooldown", 0, false);
        this.chooseClassCooldownSeconds = parseCooldownSeconds(classCooldownConfig);
        this.maxClassSwitches = parseMaxSwitches(configManager.get("class_max_switches", -1, false));

        if (!classesEnabled) {
            LOGGER.atInfo().log("Class system disabled via config.yml (enable_classes=false).");
            return;
        }

        syncBuiltinClassesIfNeeded();
        loadClasses();
    }

    /** Reload class definitions and refresh defaults from config.yml. */
    public synchronized void reload() {
        if (!classesEnabled) {
            LOGGER.atInfo().log("Class system is disabled; skipping reload.");
            return;
        }

        reloadDefaultsFromConfig();
        syncBuiltinClassesIfNeeded();
        loadClasses();
    }

    public boolean isEnabled() {
        return classesEnabled && !classesByKey.isEmpty();
    }

    public boolean isSecondaryClassEnabled() {
        return isEnabled() && secondaryClassEnabled;
    }

    public Collection<CharacterClassDefinition> getLoadedClasses() {
        return Collections.unmodifiableCollection(classesByKey.values());
    }

    public synchronized boolean canRegisterExternalClass(String id, boolean replaceExisting) {
        String classId = normalizeKey(id);
        if (classId == null || classId.isBlank()) {
            return false;
        }
        return replaceExisting || !classesByKey.containsKey(classId);
    }

    public synchronized void registerExternalClass(CharacterClassDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        String classId = requireExternalClassId(definition.getId());
        boolean overridingFileDefinition = fileClassesByKey.containsKey(classId);
        externalClassesByKey.put(classId, definition);
        rebuildMergedClassCache();
        if (overridingFileDefinition) {
            LOGGER.atInfo().log("Registered API class '%s' and overrode the file-backed definition", classId);
        } else {
            LOGGER.atInfo().log("Registered API class '%s'", classId);
        }
    }

    public synchronized boolean unregisterExternalClass(String id) {
        String classId = normalizeKey(id);
        if (classId == null || classId.isBlank()) {
            return false;
        }
        CharacterClassDefinition removed = externalClassesByKey.remove(classId);
        if (removed == null) {
            return false;
        }
        rebuildMergedClassCache();
        LOGGER.atInfo().log("Unregistered API class '%s'", classId);
        return true;
    }

    public String resolveAscensionPathId(String classInput) {
        if (classInput == null || classInput.isBlank()) {
            return null;
        }
        CharacterClassDefinition definition = findClassByUserInput(classInput);
        if (definition == null) {
            definition = getClass(classInput);
        }
        if (definition != null) {
            return definition.getAscension().getId();
        }
        return normalizeKey(classInput);
    }

    public RaceAscensionDefinition getAscensionDefinition(String classInput) {
        if (classInput == null || classInput.isBlank()) {
            return null;
        }
        CharacterClassDefinition definition = findClassByUserInput(classInput);
        if (definition == null) {
            definition = getClass(classInput);
        }
        return definition != null ? definition.getAscension() : null;
    }

    public List<CharacterClassDefinition> getNextAscensionClasses(String classInput) {
        CharacterClassDefinition sourceClass = findClassByUserInput(classInput);
        if (sourceClass == null) {
            sourceClass = getClass(classInput);
        }
        if (sourceClass == null) {
            return Collections.emptyList();
        }
        List<CharacterClassDefinition> results = new ArrayList<>();
        for (RaceAscensionPathLink link : sourceClass.getAscension().getNextPaths()) {
            CharacterClassDefinition child = getClassByAscensionPathId(link.getId());
            if (child != null) {
                results.add(child);
            }
        }
        return Collections.unmodifiableList(results);
    }

    public RaceAscensionEligibility evaluateAscensionEligibility(PlayerData data, String targetClassInput) {
        String sourceClass = data != null ? data.getPrimaryClassId() : null;
        return evaluateAscensionEligibility(data, sourceClass, targetClassInput, true);
    }

    public RaceAscensionEligibility evaluateAscensionEligibility(PlayerData data,
            String sourceClassInput,
            String targetClassInput,
            boolean requireDirectPath) {
        List<String> blockers = new ArrayList<>();
        if (data == null) {
            blockers.add("Player data is unavailable.");
            return RaceAscensionEligibility.denied(blockers);
        }

        CharacterClassDefinition targetClass = findClassByUserInput(targetClassInput);
        if (targetClass == null) {
            targetClass = getClass(targetClassInput);
        }
        if (targetClass == null) {
            blockers.add("Target class was not found.");
            return RaceAscensionEligibility.denied(blockers);
        }

        CharacterClassDefinition sourceClass = findClassByUserInput(sourceClassInput);
        if (sourceClass == null) {
            sourceClass = getClass(sourceClassInput);
        }

        if (sourceClass != null && sourceClass.getId().equalsIgnoreCase(targetClass.getId())) {
            blockers.add("You are already in that class form.");
            return RaceAscensionEligibility.denied(blockers);
        }

        if (requireDirectPath && !isDirectAscensionTransition(sourceClass, targetClass)) {
            blockers.add("Target class is not in your current ascension path options.");
        }

        RaceAscensionRequirements requirements = targetClass.getAscension().getRequirements();
        if (data.getPrestigeLevel() < requirements.getRequiredPrestige()) {
            blockers.add("Requires prestige " + requirements.getRequiredPrestige() + ".");
        }

        for (Map.Entry<SkillAttributeType, Integer> requirement : requirements.getMinSkillLevels().entrySet()) {
            int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
            if (current < requirement.getValue()) {
                blockers.add("Requires " + requirement.getKey().getConfigKey() + " >= " + requirement.getValue());
            }
        }

        for (Map.Entry<SkillAttributeType, Integer> requirement : requirements.getMaxSkillLevels().entrySet()) {
            int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
            if (current > requirement.getValue()) {
                blockers.add("Requires " + requirement.getKey().getConfigKey() + " <= " + requirement.getValue());
            }
        }

        if (!requirements.getMinAnySkillLevels().isEmpty()) {
            boolean anySatisfied = false;
            for (Map<SkillAttributeType, Integer> group : requirements.getMinAnySkillLevels()) {
                if (group == null || group.isEmpty()) {
                    continue;
                }
                boolean groupSatisfied = true;
                for (Map.Entry<SkillAttributeType, Integer> requirement : group.entrySet()) {
                    int current = data.getPlayerSkillAttributeLevel(requirement.getKey());
                    if (current < requirement.getValue()) {
                        groupSatisfied = false;
                        break;
                    }
                }
                if (groupSatisfied) {
                    anySatisfied = true;
                    break;
                }
            }
            if (!anySatisfied) {
                blockers.add("Requires at least one OR skill requirement set to be met.");
            }
        }

        if (!requirements.getRequiredAugments().isEmpty()) {
            Set<String> selectedAugments = new HashSet<>();
            data.getSelectedAugmentsSnapshot().values().forEach(augment -> {
                if (augment != null && !augment.isBlank()) {
                    selectedAugments.add(normalizeKey(augment));
                }
            });
            for (String augment : requirements.getRequiredAugments()) {
                if (!selectedAugments.contains(normalizeKey(augment))) {
                    blockers.add("Requires augment: " + augment);
                }
            }
        }

        if (!requirements.getRequiredForms().isEmpty()) {
            Set<String> completed = new LinkedHashSet<>(data.getCompletedClassFormsSnapshot());
            completed.addAll(collectAscensionLineageIds(sourceClass));

            for (String form : requirements.getRequiredForms()) {
                if (!completed.contains(normalizeKey(form))) {
                    blockers.add("Requires completed form: " + form);
                }
            }
        }

        if (!requirements.getRequiredAnyForms().isEmpty()) {
            Set<String> completed = new LinkedHashSet<>(data.getCompletedClassFormsSnapshot());
            completed.addAll(collectAscensionLineageIds(sourceClass));

            boolean anyFormMet = false;
            for (String form : requirements.getRequiredAnyForms()) {
                if (completed.contains(normalizeKey(form))) {
                    anyFormMet = true;
                    break;
                }
            }
            if (!anyFormMet) {
                List<String> formNames = new ArrayList<>();
                for (String form : requirements.getRequiredAnyForms()) {
                    CharacterClassDefinition def = getClass(form);
                    formNames.add(def != null ? def.getDisplayName() : form);
                }
                blockers.add("Requires at least one completed form: " + String.join(" OR ", formNames));
            }
        }

        if (blockers.isEmpty()) {
            return RaceAscensionEligibility.allowed();
        }
        return RaceAscensionEligibility.denied(blockers);
    }

    public boolean canAscend(PlayerData data, String targetClassInput) {
        return evaluateAscensionEligibility(data, targetClassInput).isEligible();
    }

    public CharacterClassDefinition getClass(String classId) {
        if (classId == null) {
            return null;
        }
        return classesByKey.get(normalizeKey(classId));
    }

    public CharacterClassDefinition getPlayerPrimaryClass(PlayerData data) {
        if (data == null) {
            return null;
        }
        String currentPrimaryId = data.getPrimaryClassId();
        if (isMissingAssignedClass(currentPrimaryId)) {
            clearMissingClassAssignment(data, ClassAssignmentSlot.PRIMARY, currentPrimaryId);
            return null;
        }
        String resolvedId = resolvePrimaryClassIdentifier(data.getPrimaryClassId());
        CharacterClassDefinition resolved = getClass(resolvedId);
        if (resolved != null && !resolved.getId().equals(data.getPrimaryClassId())) {
            data.setPrimaryClassId(resolved.getId());
        }
        return resolved;
    }

    public CharacterClassDefinition getPlayerSecondaryClass(PlayerData data) {
        if (data == null) {
            return null;
        }
        if (!isSecondaryClassEnabled()) {
            if (data.getSecondaryClassId() != null) {
                data.setSecondaryClassId(null);
            }
            return null;
        }
        String currentSecondaryId = data.getSecondaryClassId();
        if (isMissingAssignedClass(currentSecondaryId)) {
            clearMissingClassAssignment(data, ClassAssignmentSlot.SECONDARY, currentSecondaryId);
            return null;
        }
        String resolvedId = resolveSecondaryClassIdentifier(data.getSecondaryClassId());
        if (resolvedId == null) {
            if (data.getSecondaryClassId() != null) {
                data.setSecondaryClassId(null);
            }
            return null;
        }
        CharacterClassDefinition resolved = getClass(resolvedId);
        if (resolved != null && !resolved.getId().equals(data.getSecondaryClassId())) {
            data.setSecondaryClassId(resolved.getId());
        }
        if (resolved != null && resolved.getId().equals(data.getPrimaryClassId())) {
            // prevent duplicate stacking
            data.setSecondaryClassId(null);
            return null;
        }
        return resolved;
    }

    public CharacterClassDefinition setPlayerPrimaryClass(PlayerData data, String requestedValue) {
        if (data == null) {
            return null;
        }
        String resolvedId = resolvePrimaryClassIdentifier(requestedValue);
        CharacterClassDefinition resolved = getClass(resolvedId);
        if (resolved == null) {
            resolved = getDefaultPrimaryClass();
        }
        if (resolved != null) {
            data.setPrimaryClassId(resolved.getId());
        } else {
            data.setPrimaryClassId(null);
        }
        return resolved;
    }

    public CharacterClassDefinition setPlayerSecondaryClass(PlayerData data, String requestedValue) {
        if (data == null) {
            return null;
        }
        if (!isSecondaryClassEnabled()) {
            data.setSecondaryClassId(null);
            return null;
        }
        String resolvedId = resolveSecondaryClassIdentifier(requestedValue);
        if (resolvedId == null) {
            data.setSecondaryClassId(null);
            return null;
        }
        CharacterClassDefinition resolved = getClass(resolvedId);
        if (resolved == null) {
            data.setSecondaryClassId(null);
            return null;
        }
        if (resolved.getId().equalsIgnoreCase(data.getPrimaryClassId())) {
            data.setSecondaryClassId(null);
            return null;
        }
        data.setSecondaryClassId(resolved.getId());
        return resolved;
    }

    public CharacterClassDefinition getDefaultPrimaryClass() {
        if (!hasConfiguredDefaultPrimaryClass) {
            return null;
        }
        if (defaultPrimaryClassId == null || defaultPrimaryClassId.isBlank()) {
            return null;
        }
        CharacterClassDefinition configured = getClass(defaultPrimaryClassId);
        if (configured != null) {
            return configured;
        }
        return classesByKey.values().stream().findFirst().orElse(null);
    }

    public double getWeaponDamageMultiplier(PlayerData data, String weaponCategoryKey) {
        if (!isEnabled() || data == null) {
            return 1.0D;
        }

        CharacterClassDefinition primary = getPlayerPrimaryClass(data);
        if (primary == null) {
            return 1.0D;
        }

        String normalizedCategory = WeaponConfig.normalizeCategoryKey(weaponCategoryKey);
        if (normalizedCategory == null || normalizedCategory.isBlank()) {
            return 1.0D;
        }

        // Weapon damage bonuses are sourced from primary class only.
        if (primary.getWeaponMultipliers().containsKey(normalizedCategory)) {
            return Math.max(0.0D, primary.getWeaponMultiplier(normalizedCategory));
        }

        return offClassWeaponDamageMultiplier;
    }

    private double resolveOffClassWeaponDamageMultiplier(Object rawPenalty) {
        double penaltyFraction = parsePenaltyFraction(rawPenalty, DEFAULT_OFF_CLASS_WEAPON_DAMAGE_PENALTY);
        return Math.max(0.0D, 1.0D + penaltyFraction);
    }

    private double parsePenaltyFraction(Object rawPenalty, double defaultPenaltyFraction) {
        if (rawPenalty instanceof Number number) {
            return normalizePenaltyInput(number.doubleValue(), defaultPenaltyFraction);
        }
        if (rawPenalty instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return defaultPenaltyFraction;
            }

            boolean hasPercentSuffix = trimmed.endsWith("%");
            String numeric = hasPercentSuffix ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
            try {
                double parsed = Double.parseDouble(numeric);
                if (hasPercentSuffix) {
                    return parsed / 100.0D;
                }
                return normalizePenaltyInput(parsed, defaultPenaltyFraction);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultPenaltyFraction;
    }

    private double normalizePenaltyInput(double raw, double defaultPenaltyFraction) {
        if (!Double.isFinite(raw)) {
            return defaultPenaltyFraction;
        }
        if (Math.abs(raw) > 1.0D) {
            return raw / 100.0D;
        }
        return raw;
    }

    public double getSecondaryPassiveScale() {
        return secondaryPassiveScale;
    }

    public long getChooseClassCooldownSeconds() {
        return Math.max(0L, chooseClassCooldownSeconds);
    }

    public int getMaxClassSwitches() {
        return maxClassSwitches;
    }

    public boolean hasClassSwitchesRemaining(PlayerData data) {
        if (data == null)
            return false;
        if (maxClassSwitches < 0)
            return true;
        applyLevelThresholdSwapConsumption(data);
        boolean primaryRemaining = hasClassSwitchesRemaining(data, ClassAssignmentSlot.PRIMARY);
        if (!isSecondaryClassEnabled()) {
            return primaryRemaining;
        }
        boolean secondaryRemaining = hasClassSwitchesRemaining(data, ClassAssignmentSlot.SECONDARY);
        return primaryRemaining || secondaryRemaining;
    }

    public boolean hasClassSwitchesRemaining(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null)
            return false;
        if (maxClassSwitches < 0)
            return true;
        applyLevelThresholdSwapConsumption(data);
        if (slot == ClassAssignmentSlot.SECONDARY && !isSecondaryClassEnabled()) {
            return false;
        }
        return getClassSwitchCount(data, slot) > 0;
    }

    public int getRemainingClassSwitches(PlayerData data) {
        if (maxClassSwitches < 0 || data == null)
            return Integer.MAX_VALUE;
        applyLevelThresholdSwapConsumption(data);
        int primaryRemaining = getRemainingClassSwitches(data, ClassAssignmentSlot.PRIMARY);
        if (!isSecondaryClassEnabled()) {
            return primaryRemaining;
        }
        int secondaryRemaining = getRemainingClassSwitches(data, ClassAssignmentSlot.SECONDARY);
        if (primaryRemaining == Integer.MAX_VALUE || secondaryRemaining == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, primaryRemaining + secondaryRemaining);
    }

    public int getRemainingClassSwitches(PlayerData data, ClassAssignmentSlot slot) {
        if (maxClassSwitches < 0 || data == null || slot == null)
            return Integer.MAX_VALUE;
        applyLevelThresholdSwapConsumption(data);
        if (slot == ClassAssignmentSlot.SECONDARY && !isSecondaryClassEnabled()) {
            return 0;
        }
        return Math.max(0, getClassSwitchCount(data, slot));
    }

    public long getClassCooldownRemaining(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null) {
            return 0L;
        }
        long lastChange = slot == ClassAssignmentSlot.PRIMARY
                ? data.getLastPrimaryClassChangeEpochSeconds()
                : data.getLastSecondaryClassChangeEpochSeconds();
        return calculateRemainingCooldown(lastChange);
    }

    public void markClassChange(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null) {
            return;
        }
        applyLevelThresholdSwapConsumption(data);
        long now = Instant.now().getEpochSecond();
        if (slot == ClassAssignmentSlot.PRIMARY) {
            data.setLastPrimaryClassChangeEpochSeconds(now);
            data.decrementRemainingPrimaryClassSwitches();
        } else {
            data.setLastSecondaryClassChangeEpochSeconds(now);
            data.decrementRemainingSecondaryClassSwitches();
        }
    }

    private int getClassSwitchCount(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null) {
            return 0;
        }
        applyLevelThresholdSwapConsumption(data);
        return slot == ClassAssignmentSlot.PRIMARY
                ? data.getRemainingPrimaryClassSwitches()
                : data.getRemainingSecondaryClassSwitches();
    }

    private void applyLevelThresholdSwapConsumption(PlayerData data) {
        if (data == null || maxClassSwitches < 0) {
            return;
        }
        if (!isSwapAntiExploitEnabled()) {
            return;
        }
        if (data.getLevel() < getSwapConsumeLevelThreshold()) {
            return;
        }
        int consumeFloor = Math.max(0, maxClassSwitches - SWAP_CONSUME_COUNT);
        if (hasAssignedClassInSlot(data, ClassAssignmentSlot.PRIMARY)
                && data.getRemainingPrimaryClassSwitches() > consumeFloor) {
            data.setRemainingPrimaryClassSwitches(consumeFloor);
        }
        if (isSecondaryClassEnabled()
                && hasAssignedClassInSlot(data, ClassAssignmentSlot.SECONDARY)
                && data.getRemainingSecondaryClassSwitches() > consumeFloor) {
            data.setRemainingSecondaryClassSwitches(consumeFloor);
        }

        // If a slot is unassigned (None) and fully exhausted, grant exactly one
        // emergency swap so players can recover from a None state.
        grantEmergencySwapIfNoneAndExhausted(data, ClassAssignmentSlot.PRIMARY);
        if (isSecondaryClassEnabled()) {
            grantEmergencySwapIfNoneAndExhausted(data, ClassAssignmentSlot.SECONDARY);
        }
    }

    private boolean hasAssignedClassInSlot(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null) {
            return false;
        }
        String classId = slot == ClassAssignmentSlot.PRIMARY ? data.getPrimaryClassId() : data.getSecondaryClassId();
        return classId != null && !classId.isBlank() && !"none".equalsIgnoreCase(classId.trim());
    }

    private boolean isSwapAntiExploitEnabled() {
        Object raw = configManager.get("swap_anti_exploit.consume_at_level_enabled", Boolean.TRUE, false);
        return parseBoolean(raw, true);
    }

    private int getSwapConsumeLevelThreshold() {
        Object raw = configManager.get(
                "swap_anti_exploit.consume_at_level_threshold",
                SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT,
                false);
        if (raw instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (raw instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT;
            }
        }
        return SWAP_CONSUME_LEVEL_THRESHOLD_DEFAULT;
    }

    private long calculateRemainingCooldown(long lastChangeEpochSeconds) {
        long cooldown = getChooseClassCooldownSeconds();
        if (cooldown <= 0L || lastChangeEpochSeconds <= 0L) {
            return 0L;
        }
        long availableAt = lastChangeEpochSeconds + cooldown;
        long now = Instant.now().getEpochSecond();
        if (availableAt <= now) {
            return 0L;
        }
        return availableAt - now;
    }

    private int parseMaxSwitches(Object value) {
        if (value == null)
            return -1;
        try {
            if (value instanceof Number n)
                return n.intValue();
            return Integer.parseInt(value.toString().trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    public String resolvePrimaryClassIdentifier(String requestedValue) {
        if (!isEnabled()) {
            return null;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            if (!hasConfiguredDefaultPrimaryClass) {
                return null;
            }
            CharacterClassDefinition fallback = getDefaultPrimaryClass();
            return fallback != null ? fallback.getId() : null;
        }
        CharacterClassDefinition byId = findClassByUserInput(requestedValue);
        if (byId != null) {
            return byId.getId();
        }
        CharacterClassDefinition fallback = getDefaultPrimaryClass();
        return fallback != null ? fallback.getId() : null;
    }

    public boolean hasConfiguredDefaultPrimaryClass() {
        return hasConfiguredDefaultPrimaryClass;
    }

    public String resolveSecondaryClassIdentifier(String requestedValue) {
        if (!isSecondaryClassEnabled()) {
            return null;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            CharacterClassDefinition fallback = getClass(defaultSecondaryClassId);
            return fallback != null ? fallback.getId() : null;
        }
        CharacterClassDefinition byId = findClassByUserInput(requestedValue);
        return byId != null ? byId.getId() : null;
    }

    public CharacterClassDefinition findClassByUserInput(String userInput) {
        if (!isEnabled() || userInput == null) {
            return null;
        }
        CharacterClassDefinition byId = getClass(userInput);
        if (byId != null) {
            return byId;
        }
        String normalizedName = normalizeKey(userInput);
        if (normalizedName.isEmpty()) {
            return null;
        }
        for (CharacterClassDefinition definition : classesByKey.values()) {
            String displayName = definition.getDisplayName();
            if (displayName != null && normalizeKey(displayName).equals(normalizedName)) {
                return definition;
            }
        }
        return null;
    }

    private boolean isMissingAssignedClass(String classId) {
        if (classId == null || classId.isBlank()) {
            return false;
        }
        if ("none".equalsIgnoreCase(classId.trim())) {
            return false;
        }
        return getClass(classId) == null;
    }

    private void clearMissingClassAssignment(PlayerData data, ClassAssignmentSlot slot, String missingClassId) {
        if (data == null || slot == null) {
            return;
        }

        if (slot == ClassAssignmentSlot.PRIMARY) {
            data.setPrimaryClassId(null);
        } else {
            data.setSecondaryClassId(null);
        }

        LOGGER.atInfo().log("Cleared missing class assignment '%s' for %s slot.",
                missingClassId,
                slot.name().toLowerCase(Locale.ROOT));
    }

    private void grantEmergencySwapIfNoneAndExhausted(PlayerData data, ClassAssignmentSlot slot) {
        if (data == null || slot == null || maxClassSwitches <= 0) {
            return;
        }

        if (hasAssignedClassInSlot(data, slot)) {
            return;
        }

        int currentRemaining;
        if (slot == ClassAssignmentSlot.PRIMARY) {
            currentRemaining = data.getRemainingPrimaryClassSwitches();
        } else {
            currentRemaining = data.getRemainingSecondaryClassSwitches();
        }

        // Only grant an emergency swap when the slot has no swaps remaining.
        if (currentRemaining > 0) {
            return;
        }

        if (slot == ClassAssignmentSlot.PRIMARY) {
            data.setRemainingPrimaryClassSwitches(1);
        } else {
            data.setRemainingSecondaryClassSwitches(1);
        }

        LOGGER.atInfo().log("Granted one emergency class swap for %s slot after clearing missing class.",
                slot.name().toLowerCase(Locale.ROOT));
    }

    public boolean isSwapAntiExploitConsumeEnabled() {
        return isSwapAntiExploitEnabled();
    }

    public int getSwapAntiExploitConsumeLevelThreshold() {
        return getSwapConsumeLevelThreshold();
    }

    private void loadClasses() {
        fileClassesByKey.clear();
        File classesFolder = filesManager.getClassesFolder();
        if (classesFolder == null || !classesFolder.exists()) {
            LOGGER.atWarning().log("Classes folder is missing; cannot load class definitions.");
            rebuildMergedClassCache();
            return;
        }
        try (Stream<Path> files = Files.walk(classesFolder.toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(this::loadClassFromFile);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to walk classes directory: %s", e.getMessage());
        }

        rebuildMergedClassCache();
        LOGGER.atInfo().log("Loaded %d class definition(s).", classesByKey.size());
    }

    private void syncBuiltinClassesIfNeeded() {
        if (!enableBuiltinClasses) {
            // Builtin classes are disabled; don't sync them
            LOGGER.atInfo().log("Builtin classes are disabled (enable_builtin_classes=false)");
            return;
        }
        if (!forceBuiltinClasses) {
            return;
        }
        File classesFolder = filesManager.getClassesFolder();
        if (classesFolder == null) {
            LOGGER.atWarning().log("Classes folder is null; cannot sync built-in classes.");
            return;
        }

        int storedVersion = readClassesVersion(classesFolder);
        if (storedVersion == VersionRegistry.BUILTIN_CLASSES_VERSION) {
            return; // up to date
        }

        filesManager.archivePathIfExists(classesFolder.toPath(), "classes", "classes.version:" + storedVersion);
        clearDirectory(classesFolder.toPath());
        filesManager.exportResourceDirectory("classes", classesFolder, true);
        writeClassesVersion(classesFolder, VersionRegistry.BUILTIN_CLASSES_VERSION);
        LOGGER.atInfo().log("Synced built-in classes to version %d (force_builtin_classes=true)",
                VersionRegistry.BUILTIN_CLASSES_VERSION);
    }

    private int readClassesVersion(File classesFolder) {
        Path versionPath = classesFolder.toPath().resolve(VersionRegistry.CLASSES_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read classes version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeClassesVersion(File classesFolder, int version) {
        Path versionPath = classesFolder.toPath().resolve(VersionRegistry.CLASSES_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write classes version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear classes directory: %s", e.getMessage());
        }
    }

    private void loadClassFromFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Object> yamlData = yaml.load(reader);
            if (yamlData == null) {
                LOGGER.atWarning().log("Class file %s was empty.", path.getFileName());
                return;
            }

            CharacterClassDefinition definition = buildDefinition(path, yamlData);
            if (!definition.isEnabled()) {
                LOGGER.atInfo().log("Skipping disabled class %s from %s", definition.getId(), path.getFileName());
                return;
            }
            fileClassesByKey.put(normalizeKey(definition.getId()), definition);
            LOGGER.atInfo().log("Loaded class %s", definition.getId());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to read class file %s: %s", path.getFileName(), e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.atSevere().log("Failed to parse class file %s: %s", path.getFileName(), e.getMessage());
        }
    }

    private CharacterClassDefinition buildDefinition(Path file, Map<String, Object> yamlData) {
        String classId = deriveClassId(file, yamlData);
        String displayName = safeString(yamlData.getOrDefault("class_name", yamlData.get("name")));
        if (displayName == null) {
            displayName = classId;
        }
        String description = safeString(yamlData.get("description"));
        List<String> roles = parseClassRoles(yamlData, classId);
        String damageType = parseClassDamageType(yamlData, classId);
        String rangeType = parseClassRangeType(yamlData, classId);
        String category = parseClassCategory(yamlData, classId);
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        String iconItemId = parseIconId(yamlData);
        Map<String, Double> weaponMultipliers = parseWeaponSection(yamlData);
        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(classId, passives);
        RaceAscensionDefinition ascension = parseAscensionDefinition(classId, yamlData.get("ascension"));

        return new CharacterClassDefinition(classId,
                displayName,
                description,
                roles,
                damageType,
                rangeType,
                category,
                enabled,
                iconItemId,
                weaponMultipliers,
                passives,
                passiveDefinitions,
                ascension);
    }

    private List<String> parseClassRoles(Map<String, Object> yamlData, String classId) {
        List<String> configuredRoles = parseStringList(yamlData.get("roles"));
        if (!configuredRoles.isEmpty()) {
            return configuredRoles;
        }

        String singleRole = safeString(yamlData.get("role"));
        if (singleRole != null) {
            return List.of(singleRole);
        }

        return inferLegacyClassRoles(classId);
    }

    private String parseClassDamageType(Map<String, Object> yamlData, String classId) {
        String configuredDamageType = safeString(yamlData.get("damage_type"));
        if (configuredDamageType != null) {
            return configuredDamageType;
        }
        return inferLegacyDamageType(classId);
    }

    private String parseClassRangeType(Map<String, Object> yamlData, String classId) {
        String configuredRangeType = safeString(yamlData.get("range_type"));
        if (configuredRangeType != null) {
            return configuredRangeType;
        }
        return inferLegacyRangeType(classId);
    }

    private String parseClassCategory(Map<String, Object> yamlData, String classId) {
        String configured = safeString(yamlData.get("category"));
        if (configured != null) {
            return normalizeKey(configured);
        }

        String fallback = inferLegacyClassCategory(classId);
        LOGGER.atFine().log("Class %s missing category in yaml; using fallback category '%s'", classId, fallback);
        return fallback;
    }

    private String inferLegacyClassCategory(String classId) {
        String normalized = normalizeKey(classId);
        String baseId = normalized;
        int firstUnderscore = normalized.indexOf('_');
        if (firstUnderscore > 0) {
            baseId = normalized.substring(0, firstUnderscore);
        }

        return switch (baseId) {
            case "mage", "arcanist", "marksman", "assassin", "oracle", "healer", "necromancer" ->
                "glass_cannon";
            case "battlemage", "duelist", "brawler", "adventurer", "slayer" -> "fighter";
            case "juggernaut", "vanguard" -> "tank";
            default -> "default";
        };
    }

    private List<String> inferLegacyClassRoles(String classId) {
        String baseId = normalizeBaseClassId(classId);
        return switch (baseId) {
            case "mage", "arcanist", "oracle", "necromancer" -> List.of("Mage");
            case "healer" -> List.of("Support");
            case "assassin" -> List.of("Assassin", "Diver");
            case "marksman" -> List.of("Marksman");
            case "battlemage" -> List.of("BattleMage", "Mage");
            case "duelist" -> List.of("Skirmisher", "Diver");
            case "brawler" -> List.of("Skirmisher", "Juggernaut");
            case "slayer" -> List.of("Diver", "Skirmisher");
            case "juggernaut" -> List.of("Juggernaut");
            case "vanguard" -> List.of("Vanguard");
            default -> List.of("Skirmisher");
        };
    }

    private String inferLegacyDamageType(String classId) {
        String baseId = normalizeBaseClassId(classId);
        return switch (baseId) {
            case "mage", "arcanist", "oracle", "healer", "necromancer" -> "Magic";
            case "vanguard", "adventurer" -> "Hybrid";
            default -> "Physical";
        };
    }

    private String inferLegacyRangeType(String classId) {
        String baseId = normalizeBaseClassId(classId);
        return switch (baseId) {
            case "marksman", "mage", "arcanist", "oracle", "healer", "necromancer" -> "range";
            case "adventurer" -> "melee/range";
            default -> "melee";
        };
    }

    private String normalizeBaseClassId(String classId) {
        String normalized = normalizeKey(classId);
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        int firstUnderscore = normalized.indexOf('_');
        if (firstUnderscore > 0) {
            return normalized.substring(0, firstUnderscore);
        }
        return normalized;
    }

    private RaceAscensionDefinition parseAscensionDefinition(String classId, Object node) {
        Map<String, Object> ascensionNode = castToStringObjectMap(node);
        if (ascensionNode == null) {
            return RaceAscensionDefinition.baseFallback(normalizeKey(classId));
        }

        String ascensionId = safeString(ascensionNode.get("id"));
        if (ascensionId == null) {
            ascensionId = normalizeKey(classId);
        }
        String stage = safeString(ascensionNode.get("stage"));
        String path = safeString(ascensionNode.get("path"));
        boolean finalForm = parseBoolean(ascensionNode.get("final_form"), false);
        boolean singleRouteOnly = parseBoolean(ascensionNode.get("single_route_only"), true);
        if (ascensionNode.containsKey("allow_all_routes")) {
            singleRouteOnly = !parseBoolean(ascensionNode.get("allow_all_routes"), false);
        }
        RaceAscensionRequirements requirements = parseAscensionRequirements(ascensionNode.get("requirements"));
        List<RaceAscensionPathLink> nextPaths = parseAscensionNextPaths(ascensionNode.get("next_paths"));

        return new RaceAscensionDefinition(ascensionId,
                stage,
                path,
                finalForm,
                singleRouteOnly,
                requirements,
                nextPaths);
    }

    private RaceAscensionRequirements parseAscensionRequirements(Object node) {
        Map<String, Object> requirementsNode = castToStringObjectMap(node);
        if (requirementsNode == null) {
            return RaceAscensionRequirements.none();
        }

        int requiredPrestige = parseInt(requirementsNode.get("required_prestige"), 0);
        Map<SkillAttributeType, Integer> minSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("min_skill_levels"));
        Map<SkillAttributeType, Integer> maxSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("max_skill_levels"));
        List<Map<SkillAttributeType, Integer>> minAnySkillLevels = parseMinAnySkillLevels(
                requirementsNode.get("min_any_skill_levels"));
        List<String> requiredAugments = parseStringList(requirementsNode.get("required_augments"));
        List<String> requiredForms = parseStringList(requirementsNode.get("required_forms"));
        List<String> requiredAnyForms = parseStringList(requirementsNode.get("required_any_forms"));

        return new RaceAscensionRequirements(
                requiredPrestige,
                minSkillLevels,
                maxSkillLevels,
                minAnySkillLevels,
                requiredAugments,
                requiredForms,
                requiredAnyForms);
    }

    private Map<SkillAttributeType, Integer> parseSkillLevelRequirements(Object node) {
        Map<SkillAttributeType, Integer> result = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> map = castToStringObjectMap(node);
        if (map == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            SkillAttributeType attributeType = SkillAttributeType.fromConfigKey(entry.getKey());
            if (attributeType == null) {
                continue;
            }
            int value = Math.max(0, parseInt(entry.getValue(), 0));
            result.put(attributeType, value);
        }
        return result;
    }

    private List<Map<SkillAttributeType, Integer>> parseMinAnySkillLevels(Object node) {
        List<Map<SkillAttributeType, Integer>> groups = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return groups;
        }

        for (Object entry : iterable) {
            Map<String, Object> groupNode = castToStringObjectMap(entry);
            if (groupNode == null || groupNode.isEmpty()) {
                continue;
            }
            Map<SkillAttributeType, Integer> group = new EnumMap<>(SkillAttributeType.class);
            for (Map.Entry<String, Object> requirement : groupNode.entrySet()) {
                SkillAttributeType type = SkillAttributeType.fromConfigKey(requirement.getKey());
                if (type == null) {
                    continue;
                }
                group.put(type, Math.max(0, parseInt(requirement.getValue(), 0)));
            }
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        return groups;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (trimmed.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private List<String> parseStringList(Object node) {
        List<String> values = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return values;
        }

        for (Object entry : iterable) {
            String value = safeString(entry);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private List<RaceAscensionPathLink> parseAscensionNextPaths(Object node) {
        List<RaceAscensionPathLink> nextPaths = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return nextPaths;
        }

        for (Object entry : iterable) {
            Map<String, Object> linkMap = castToStringObjectMap(entry);
            if (linkMap == null) {
                continue;
            }
            String id = safeString(linkMap.get("id"));
            if (id == null) {
                continue;
            }
            String name = safeString(linkMap.get("name"));
            nextPaths.add(new RaceAscensionPathLink(id, name));
        }

        return nextPaths;
    }

    private String parseIconId(Map<String, Object> yamlData) {
        if (yamlData == null) {
            return null;
        }
        Object value = yamlData.get("icon");
        if (value == null) {
            value = yamlData.get("icon_id");
        }
        if (value == null) {
            value = yamlData.get("item_icon");
        }
        return safeString(value);
    }

    private Map<String, Double> parseWeaponSection(Map<String, Object> yamlData) {
        Object node = yamlData.get("Weapon");
        if (node == null) {
            node = yamlData.get("weapon");
        }
        if (node == null) {
            node = yamlData.get("weapons");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return result;
        }
        for (Object entry : iterable) {
            Map<String, Object> weaponMap = castToStringObjectMap(entry);
            if (weaponMap == null) {
                continue;
            }
            String typeKey = safeString(weaponMap.get("type"));
            if (typeKey == null) {
                continue;
            }
            String weaponCategory = WeaponConfig.normalizeCategoryKey(typeKey);
            if (weaponCategory == null) {
                continue;
            }
            double multiplier = parseDouble(weaponMap.get("damage"), 1.0D);
            if (multiplier <= 0.0D) {
                multiplier = 1.0D;
            }
            result.put(weaponCategory, multiplier);
        }
        return result;
    }

    private List<Map<String, Object>> parsePassives(Object node) {
        List<Map<String, Object>> passives = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return passives;
        }
        for (Object entry : iterable) {
            Map<String, Object> passive = castToStringObjectMap(entry);
            if (passive != null && passive.containsKey("type")) {
                passives.add(passive);
            }
        }
        return passives;
    }

    private List<RacePassiveDefinition> buildPassiveDefinitions(String classId, List<Map<String, Object>> passives) {
        List<RacePassiveDefinition> definitions = new ArrayList<>();
        if (passives == null) {
            return definitions;
        }
        for (int index = 0; index < passives.size(); index++) {
            Map<String, Object> passive = passives.get(index);
            if (passive == null) {
                continue;
            }
            String rawType = safeString(passive.get("type"));
            if (rawType == null) {
                LOGGER.atWarning().log("Class %s passive entry %d is missing a type", classId, index + 1);
                continue;
            }
            ArchetypePassiveType type = ArchetypePassiveType.fromConfigKey(rawType);
            if (type == null) {
                LOGGER.atWarning().log("Class %s passive type '%s' is not recognized", classId, rawType);
                continue;
            }
            double value = parseDouble(passive.get("value"), 0.0D);
            SkillAttributeType attributeType = null;
            if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                String attributeKey = safeString(passive.get("attribute"));
                attributeType = SkillAttributeType.fromConfigKey(attributeKey);
                if (attributeType == null) {
                    LOGGER.atWarning().log(
                            "Class %s passive entry %d has INNATE_ATTRIBUTE_GAIN without a valid attribute key",
                            classId, index + 1);
                    continue;
                }
            }
            DamageLayer damageLayer = PassiveDefinitionParser.resolveDamageLayer(type, passive);
            String tag = PassiveDefinitionParser.resolveTag(type, passive);
            PassiveStackingStyle stacking = PassiveDefinitionParser.resolveStacking(type, passive);
            PassiveTier tier = PassiveTier.fromConfig(passive.get("tier"), PassiveTier.COMMON);
                Object rawCategory = passive.get("category");
                PassiveCategory category = PassiveCategory.fromConfigOrNull(rawCategory);
                if (rawCategory != null && category == null) {
                LOGGER.atWarning().log("Class %s passive %s has unknown category '%s'",
                    classId,
                    type,
                    rawCategory);
                }
            Map<String, Double> classValues = parseClassValues(passive.get("class_values"));
            definitions.add(new RacePassiveDefinition(type,
                    value,
                    passive,
                    attributeType,
                    damageLayer,
                    tag,
                    category,
                    stacking,
                    tier,
                    classValues));
        }
        return definitions;
    }

    private Map<String, Double> parseClassValues(Object node) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object rawKey = entry.getKey();
            Object rawVal = entry.getValue();
            if (!(rawKey instanceof String key)) {
                continue;
            }
            String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (normalizedKey.isEmpty()) {
                continue;
            }
            Double value = extractValue(rawVal);
            if (value != null) {
                result.put(normalizedKey, value);
            }
        }
        return result;
    }

    private Double extractValue(Object rawVal) {
        if (rawVal instanceof Number number) {
            return number.doubleValue();
        }
        if (rawVal instanceof Map<?, ?> map) {
            Object inner = map.get("value");
            if (inner instanceof Number number) {
                return number.doubleValue();
            }
            if (inner instanceof String str) {
                return parseNumericString(str);
            }
        }
        if (rawVal instanceof String str) {
            return parseNumericString(str);
        }
        return null;
    }

    private Double parseNumericString(String str) {
        if (str == null) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void rebuildMergedClassCache() {
        classesByKey.clear();
        classesByKey.putAll(fileClassesByKey);
        classesByKey.putAll(externalClassesByKey);

        rebuildAscensionIndexes();

        if (!classesByKey.containsKey(normalizeKey(defaultPrimaryClassId)) && !classesByKey.isEmpty()) {
            defaultPrimaryClassId = classesByKey.values().iterator().next().getId();
            LOGGER.atInfo().log("Default primary class set to %s", defaultPrimaryClassId);
        }
        if (defaultSecondaryClassId != null
                && !classesByKey.containsKey(normalizeKey(defaultSecondaryClassId))) {
            defaultSecondaryClassId = null;
        }
    }

    private String requireExternalClassId(String id) {
        String classId = normalizeKey(id);
        if (classId == null || classId.isBlank()) {
            throw new IllegalArgumentException("class id cannot be null or blank");
        }
        return classId;
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (trimmed.isEmpty()) {
                return defaultValue;
            }
            int spaceIndex = trimmed.indexOf(' ');
            String numericPortion = spaceIndex >= 0 ? trimmed.substring(0, spaceIndex) : trimmed;
            try {
                return Double.parseDouble(numericPortion);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private long parseCooldownSeconds(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (trimmed.isEmpty()) {
                return 0L;
            }
            try {
                return Math.max(0L, Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return defaultValue;
    }

    private void rebuildAscensionIndexes() {
        classesByAscensionId.clear();
        ascensionParentsByChild.clear();

        for (CharacterClassDefinition definition : classesByKey.values()) {
            if (definition == null || definition.getAscension() == null) {
                continue;
            }
            classesByAscensionId.put(normalizeKey(definition.getAscension().getId()), definition);
        }

        for (CharacterClassDefinition definition : classesByKey.values()) {
            if (definition == null || definition.getAscension() == null) {
                continue;
            }
            String parentPathId = normalizeKey(definition.getAscension().getId());
            for (RaceAscensionPathLink link : definition.getAscension().getNextPaths()) {
                if (link == null || link.getId() == null || link.getId().isBlank()) {
                    continue;
                }
                String childPathId = normalizeKey(link.getId());
                ascensionParentsByChild.computeIfAbsent(childPathId, key -> new ArrayList<>()).add(parentPathId);
            }
        }
    }

    private CharacterClassDefinition getClassByAscensionPathId(String ascensionPathId) {
        if (ascensionPathId == null || ascensionPathId.isBlank()) {
            return null;
        }
        CharacterClassDefinition byAscensionId = classesByAscensionId.get(normalizeKey(ascensionPathId));
        if (byAscensionId != null) {
            return byAscensionId;
        }
        CharacterClassDefinition byClassId = getClass(ascensionPathId);
        if (byClassId != null) {
            return byClassId;
        }
        return findClassByUserInput(ascensionPathId);
    }

    private boolean isDirectAscensionTransition(CharacterClassDefinition sourceClass,
            CharacterClassDefinition targetClass) {
        if (targetClass == null || targetClass.getAscension() == null) {
            return false;
        }
        String targetPathId = normalizeKey(targetClass.getAscension().getId());

        if (sourceClass == null || sourceClass.getAscension() == null) {
            List<String> parents = ascensionParentsByChild.get(targetPathId);
            return parents == null || parents.isEmpty();
        }

        for (RaceAscensionPathLink nextPath : sourceClass.getAscension().getNextPaths()) {
            if (nextPath == null) {
                continue;
            }
            if (normalizeKey(nextPath.getId()).equals(targetPathId)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectAscensionLineageIds(CharacterClassDefinition sourceClass) {
        if (sourceClass == null || sourceClass.getAscension() == null) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> lineage = new LinkedHashSet<>();
        String currentPathId = normalizeKey(sourceClass.getAscension().getId());
        while (currentPathId != null && !currentPathId.isBlank() && lineage.add(currentPathId)) {
            List<String> parents = ascensionParentsByChild.get(currentPathId);
            if (parents == null || parents.isEmpty()) {
                break;
            }
            currentPathId = parents.get(0);
        }
        return lineage;
    }

    private String deriveClassId(Path file, Map<String, Object> yamlData) {
        String explicitId = safeString(yamlData.get("id"));
        if (explicitId != null) {
            return explicitId;
        }
        String className = safeString(yamlData.get("class_name"));
        if (className != null) {
            return className;
        }
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        if (base.isBlank()) {
            return PlayerData.DEFAULT_PRIMARY_CLASS_ID;
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private String safeString(Object value) {
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private String parseConfiguredDefaultClass(Object value) {
        String parsed = safeString(value);
        if (parsed == null) {
            return null;
        }
        return "none".equalsIgnoreCase(parsed) ? null : parsed;
    }

    private boolean isNoneLiteral(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return "none".equalsIgnoreCase(text.trim());
    }

    private Map<String, Object> castToStringObjectMap(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String normalizeKey(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
