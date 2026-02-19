package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.ClassAssignmentSlot;
import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.airijko.endlessleveling.passives.PassiveDefinitionParser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads and resolves EndlessLeveling character class definitions.
 */
public class ClassManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final int BUILTIN_CLASSES_VERSION = 8;
    private static final String CLASSES_VERSION_FILE = "classes.version";

    private final PluginFilesManager filesManager;
    private final ConfigManager configManager;
    private final boolean classesEnabled;
    private final boolean forceBuiltinClasses;
    private final Map<String, CharacterClassDefinition> classesByKey = new HashMap<>();
    private final Yaml yaml = new Yaml();

    private String defaultPrimaryClassId = PlayerData.DEFAULT_PRIMARY_CLASS_ID;
    private String defaultSecondaryClassId = null;
    private final double secondaryPassiveScale = 0.5D;
    private final double secondaryWeaponScale = 0.5D;
    private final long chooseClassCooldownSeconds;
    private final int maxClassSwitches;

    private void reloadDefaultsFromConfig() {
        if (configManager == null) {
            return;
        }

        String configuredPrimary = safeString(
                configManager.get("default_primary_class", defaultPrimaryClassId, false));
        if (configuredPrimary != null) {
            defaultPrimaryClassId = configuredPrimary;
        }

        String configuredSecondary = safeString(configManager.get("default_secondary_class", defaultSecondaryClassId,
                false));
        defaultSecondaryClassId = configuredSecondary;
    }

    public ClassManager(ConfigManager configManager, PluginFilesManager filesManager) {
        Objects.requireNonNull(configManager, "ConfigManager is required");
        this.filesManager = Objects.requireNonNull(filesManager, "PluginFilesManager is required");
        this.configManager = configManager;
        this.classesEnabled = parseBoolean(configManager.get("enable_classes", Boolean.TRUE, false), true);
        this.forceBuiltinClasses = parseBoolean(configManager.get("force_builtin_classes", Boolean.FALSE, false),
                false);

        Object primaryConfig = configManager.get("default_primary_class", PlayerData.DEFAULT_PRIMARY_CLASS_ID, false);
        String configuredPrimary = safeString(primaryConfig);
        if (configuredPrimary != null) {
            this.defaultPrimaryClassId = configuredPrimary;
        }

        Object secondaryConfig = configManager.get("default_secondary_class", null, false);
        this.defaultSecondaryClassId = safeString(secondaryConfig);

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
        classesByKey.clear();
        syncBuiltinClassesIfNeeded();
        loadClasses();
    }

    public boolean isEnabled() {
        return classesEnabled && !classesByKey.isEmpty();
    }

    public Collection<CharacterClassDefinition> getLoadedClasses() {
        return Collections.unmodifiableCollection(classesByKey.values());
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
            data.setPrimaryClassId(PlayerData.DEFAULT_PRIMARY_CLASS_ID);
        }
        return resolved;
    }

    public CharacterClassDefinition setPlayerSecondaryClass(PlayerData data, String requestedValue) {
        if (data == null) {
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
        CharacterClassDefinition configured = getClass(defaultPrimaryClassId);
        if (configured != null) {
            return configured;
        }
        return classesByKey.values().stream().findFirst().orElse(null);
    }

    public double getWeaponDamageMultiplier(PlayerData data, ClassWeaponType weaponType) {
        if (!isEnabled() || data == null) {
            return 1.0D;
        }
        double totalBonus = 0.0D;
        CharacterClassDefinition primary = getPlayerPrimaryClass(data);
        if (primary != null) {
            double primaryBonus = Math.max(0.0D, primary.getWeaponMultiplier(weaponType) - 1.0D);
            totalBonus += primaryBonus;
        }
        CharacterClassDefinition secondary = getPlayerSecondaryClass(data);
        if (secondary != null && secondary != primary) {
            double secondaryBonus = Math.max(0.0D, secondary.getWeaponMultiplier(weaponType) - 1.0D);
            if (secondaryBonus > 0.0D) {
                totalBonus += secondaryBonus * secondaryWeaponScale;
            }
        }
        return 1.0D + totalBonus;
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
        return data.getClassSwitchCount() < maxClassSwitches;
    }

    public int getRemainingClassSwitches(PlayerData data) {
        if (maxClassSwitches < 0 || data == null)
            return Integer.MAX_VALUE;
        return Math.max(0, maxClassSwitches - data.getClassSwitchCount());
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
        long now = Instant.now().getEpochSecond();
        if (slot == ClassAssignmentSlot.PRIMARY) {
            data.setLastPrimaryClassChangeEpochSeconds(now);
        } else {
            data.setLastSecondaryClassChangeEpochSeconds(now);
        }
        data.incrementClassSwitchCount();
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
            return PlayerData.DEFAULT_PRIMARY_CLASS_ID;
        }
        if (requestedValue == null || requestedValue.isBlank()) {
            CharacterClassDefinition fallback = getDefaultPrimaryClass();
            return fallback != null ? fallback.getId() : PlayerData.DEFAULT_PRIMARY_CLASS_ID;
        }
        CharacterClassDefinition byId = findClassByUserInput(requestedValue);
        if (byId != null) {
            return byId.getId();
        }
        CharacterClassDefinition fallback = getDefaultPrimaryClass();
        return fallback != null ? fallback.getId() : PlayerData.DEFAULT_PRIMARY_CLASS_ID;
    }

    public String resolveSecondaryClassIdentifier(String requestedValue) {
        if (!isEnabled()) {
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

    private void loadClasses() {
        File classesFolder = filesManager.getClassesFolder();
        if (classesFolder == null || !classesFolder.exists()) {
            LOGGER.atWarning().log("Classes folder is missing; cannot load class definitions.");
            return;
        }
        try (Stream<Path> files = Files.walk(classesFolder.toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .forEach(this::loadClassFromFile);
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to walk classes directory: %s", e.getMessage());
        }

        if (!classesByKey.containsKey(normalizeKey(defaultPrimaryClassId)) && !classesByKey.isEmpty()) {
            defaultPrimaryClassId = classesByKey.values().iterator().next().getId();
            LOGGER.atInfo().log("Default primary class set to %s", defaultPrimaryClassId);
        }
        if (defaultSecondaryClassId != null
                && !classesByKey.containsKey(normalizeKey(defaultSecondaryClassId))) {
            defaultSecondaryClassId = null;
        }

        LOGGER.atInfo().log("Loaded %d class definition(s).", classesByKey.size());
    }

    private void syncBuiltinClassesIfNeeded() {
        if (!forceBuiltinClasses) {
            return;
        }
        File classesFolder = filesManager.getClassesFolder();
        if (classesFolder == null) {
            LOGGER.atWarning().log("Classes folder is null; cannot sync built-in classes.");
            return;
        }

        int storedVersion = readClassesVersion(classesFolder);
        if (storedVersion == BUILTIN_CLASSES_VERSION) {
            return; // up to date
        }

        clearDirectory(classesFolder.toPath());
        filesManager.exportResourceDirectory("classes", classesFolder, true);
        writeClassesVersion(classesFolder, BUILTIN_CLASSES_VERSION);
        LOGGER.atInfo().log("Synced built-in classes to version %d (force_builtin_classes=true)",
                BUILTIN_CLASSES_VERSION);
    }

    private int readClassesVersion(File classesFolder) {
        Path versionPath = classesFolder.toPath().resolve(CLASSES_VERSION_FILE);
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
        Path versionPath = classesFolder.toPath().resolve(CLASSES_VERSION_FILE);
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
            classesByKey.put(normalizeKey(definition.getId()), definition);
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
        String role = safeString(yamlData.get("role"));
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        String iconItemId = parseIconId(yamlData);
        Map<ClassWeaponType, Double> weaponMultipliers = parseWeaponSection(yamlData);
        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(classId, passives);

        return new CharacterClassDefinition(classId,
                displayName,
                description,
                role,
                enabled,
                iconItemId,
                weaponMultipliers,
                passives,
                passiveDefinitions);
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

    private Map<ClassWeaponType, Double> parseWeaponSection(Map<String, Object> yamlData) {
        Object node = yamlData.get("Weapon");
        if (node == null) {
            node = yamlData.get("weapon");
        }
        if (node == null) {
            node = yamlData.get("weapons");
        }
        Map<ClassWeaponType, Double> result = new EnumMap<>(ClassWeaponType.class);
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
            ClassWeaponType weaponType = ClassWeaponType.fromConfigKey(typeKey);
            if (weaponType == null) {
                continue;
            }
            double multiplier = parseDouble(weaponMap.get("damage"), 1.0D);
            if (multiplier <= 0.0D) {
                multiplier = 1.0D;
            }
            result.put(weaponType, multiplier);
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
            PassiveCategory category = PassiveCategory.fromConfig(passive.get("category"), null);
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
