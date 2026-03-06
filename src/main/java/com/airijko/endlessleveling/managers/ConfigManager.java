package com.airijko.endlessleveling.managers;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.hypixel.hytale.logger.HytaleLogger;

public class ConfigManager {

    private static final Pattern KEY_LINE_PATTERN = Pattern.compile("^([A-Za-z0-9_\\-\\\"']+)\\s*:\\s*(.*)$");

    private final PluginFilesManager filesManager;
    private final File configFile;
    private final Yaml yaml;
    private final Yaml flowYaml;
    private Map<String, Object> configMap = new LinkedHashMap<>();
    private final int bundledConfigVersion;
    private final boolean createBackupOnRefresh;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private final String resourceName;

    public ConfigManager(File configFile) {
        this(null, configFile, true);
    }

    public ConfigManager(File configFile, boolean createBackupOnRefresh) {
        this(null, configFile, createBackupOnRefresh);
    }

    public ConfigManager(PluginFilesManager filesManager, File configFile) {
        this(filesManager, configFile, true);
    }

    public ConfigManager(PluginFilesManager filesManager, File configFile, boolean createBackupOnRefresh) {
        this.filesManager = filesManager;
        this.configFile = configFile;
        this.resourceName = configFile.getName();
        this.createBackupOnRefresh = createBackupOnRefresh;

        // Setup SnakeYAML
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
        DumperOptions flowOptions = new DumperOptions();
        flowOptions.setIndent(2);
        flowOptions.setPrettyFlow(false);
        flowOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        this.flowYaml = new Yaml(flowOptions);

        this.bundledConfigVersion = resolveBundledConfigVersion();

        load(); // load config when manager is created
    }

    /** Load config from disk */
    public void load() {
        ensureConfigFileExists();
        readConfigFromDisk();
        ensureConfigUpToDate();
    }

    /** Save current configMap to disk */
    public void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            yaml.dump(configMap, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Get a value from config */
    public Object get(String path, Object defaultValue) {
        return get(path, defaultValue, true);
    }

    /** Get a value from config with optional logging */
    public Object get(String path, Object defaultValue, boolean logAccess) {
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;
        Object value = null;

        if (currentMap == null) {
            return defaultValue;
        }

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (i == keys.length - 1) {
                // Last key, return value or default
                value = currentMap.getOrDefault(key, defaultValue);
            } else {
                Object next = currentMap.get(key);
                if (next instanceof Map<?, ?> map) {
                    // noinspection unchecked
                    currentMap = (Map<String, Object>) map;
                } else {
                    if (logAccess) {
                        LOGGER.atWarning().log("ConfigManager.get: Path broken at '%s', returning default", key);
                    }
                    return defaultValue;
                }
            }
        }

        if (logAccess) {
            // Intentionally silent to avoid log spam on frequent config lookups
        }
        return value;
    }

    /** Set a value in config */
    public void set(String path, Object value) {
        configMap.put(path, value);
    }

    /** Check whether a nested path exists in the loaded config map. */
    public boolean hasPath(String path) {
        if (configMap == null || path == null || path.isEmpty()) {
            return false;
        }
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (i == keys.length - 1) {
                return currentMap.containsKey(key);
            }
            Object next = currentMap.get(key);
            if (next instanceof Map<?, ?> map) {
                // noinspection unchecked
                currentMap = (Map<String, Object>) map;
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * Ensure a nested path exists; create intermediate maps if necessary. Returns
     * true if the map was modified.
     */
    public boolean ensurePath(String path, Object defaultValue) {
        if (configMap == null || path == null || path.isEmpty()) {
            return false;
        }
        boolean modified = false;
        String[] keys = path.split("\\.");
        Map<String, Object> currentMap = configMap;

        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (i == keys.length - 1) {
                if (!currentMap.containsKey(key)) {
                    currentMap.put(key, defaultValue);
                    modified = true;
                }
            } else {
                Object next = currentMap.get(key);
                if (next instanceof Map<?, ?> map) {
                    // noinspection unchecked
                    currentMap = (Map<String, Object>) map;
                } else {
                    Map<String, Object> child = new LinkedHashMap<>();
                    currentMap.put(key, child);
                    currentMap = child;
                    modified = true;
                }
            }
        }
        return modified;
    }

    private void ensureConfigFileExists() {
        if (configFile.exists()) {
            return;
        }
        try {
            copyBundledConfigToFile();
            LOGGER.atInfo().log("Generated default config at %s", configFile.getAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create default config file", e);
        }
    }

    private void readConfigFromDisk() {
        try (FileReader reader = new FileReader(configFile)) {
            Object loaded = yaml.load(reader);
            configMap = toMutableMap(loaded);
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to read config file: %s", e.getMessage());
            configMap = new LinkedHashMap<>();
        }
    }

    private Map<String, Object> toMutableMap(Object loaded) {
        Map<String, Object> target = new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    target.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        return target;
    }

    private void ensureConfigUpToDate() {
        Integer currentVersion = extractConfigVersion(configMap);
        if (currentVersion != null && currentVersion >= bundledConfigVersion) {
            ensureInlineVersionMarker();
            return;
        }

        String foundVersion = currentVersion == null ? "missing" : Integer.toString(currentVersion);
        String expectedVersion = Integer.toString(bundledConfigVersion);
        LOGGER.atWarning().log(
                "Config version is missing/outdated (found=%s, expected=%s). Refreshing config...",
                foundVersion, expectedVersion);

        if (createBackupOnRefresh)
            backupCurrentConfig();
        try {
            mergeBundledDefaultsPreservingUserValues();
        } catch (IOException e) {
            LOGGER.atSevere().log("Unable to refresh config: %s", e.getMessage());
            return;
        }
        readConfigFromDisk();
        ensureInlineVersionMarker();
    }

    private void backupCurrentConfig() {
        if (!createBackupOnRefresh)
            return;
        if (!configFile.exists())
            return;
        if (filesManager == null) {
            LOGGER.atWarning().log("Skipped centralized config backup for %s because PluginFilesManager is unavailable",
                    configFile.getName());
            return;
        }
        Integer currentVersion = extractConfigVersion(configMap);
        String priorVersion = currentVersion == null ? "config_version:missing" : "config_version:" + currentVersion;
        filesManager.archiveFileIfExists(configFile, configFile.getName(), priorVersion);
    }

    private void copyBundledConfigToFile() throws IOException {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new FileNotFoundException("Bundled config resource not found: " + resourceName);
            }
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void mergeBundledDefaultsPreservingUserValues() throws IOException {
        Map<String, Object> bundledMap = loadBundledConfigMap();
        Map<String, Object> merged = mergeMapsPreservingUserValues(bundledMap, configMap);
        merged.put(VersionRegistry.CONFIG_VERSION_KEY, bundledConfigVersion);
        writeMergedWithBundledTemplate(merged, bundledMap);
        configMap = merged;
        LOGGER.atInfo().log("Rebuilt %s from bundled resource template and migrated existing values (version=%d)",
                resourceName, bundledConfigVersion);
    }

    private Map<String, Object> loadBundledConfigMap() throws IOException {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new FileNotFoundException("Bundled config resource not found: " + resourceName);
            }
            return toMutableMap(yaml.load(in));
        }
    }

    private Map<String, Object> mergeMapsPreservingUserValues(Map<String, Object> bundled,
            Map<String, Object> existing) {
        ExistingValueIndex existingValueIndex = new ExistingValueIndex(existing);
        return mergeMapsPreservingUserValues(bundled, existing, new ArrayList<>(), existingValueIndex);
    }

    private Map<String, Object> mergeMapsPreservingUserValues(Map<String, Object> bundled,
            Map<String, Object> existing,
            List<String> currentPath,
            ExistingValueIndex existingValueIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> bundledMap = bundled == null ? new LinkedHashMap<>() : bundled;
        Map<String, Object> existingMap = existing == null ? new LinkedHashMap<>() : existing;

        for (Map.Entry<String, Object> entry : bundledMap.entrySet()) {
            String key = entry.getKey();
            Object bundledValue = entry.getValue();
            List<String> targetPath = appendPath(currentPath, key);
            if (!existingMap.containsKey(key)) {
                ExistingPathValue migrated = existingValueIndex.takeBestMatch(key, bundledValue, targetPath);
                if (migrated != null) {
                    result.put(key, deepCopyValue(migrated.value()));
                    continue;
                }
                result.put(key, deepCopyValue(bundledValue));
                continue;
            }

            Object existingValue = existingMap.get(key);
            if (bundledValue instanceof Map<?, ?> bundledChild && existingValue instanceof Map<?, ?> existingChild) {
                result.put(key, mergeMapsPreservingUserValues(toMutableMap(bundledChild), toMutableMap(existingChild),
                        targetPath, existingValueIndex));
            } else {
                result.put(key, deepCopyValue(existingValue));
            }
        }

        for (Map.Entry<String, Object> entry : existingMap.entrySet()) {
            if (VersionRegistry.CONFIG_VERSION_KEY.equals(entry.getKey())) {
                continue;
            }
            List<String> existingPath = appendPath(currentPath, entry.getKey());
            if (!result.containsKey(entry.getKey()) && !existingValueIndex.isConsumed(existingPath)) {
                result.put(entry.getKey(), deepCopyValue(entry.getValue()));
            }
        }
        return result;
    }

    private void ensureInlineVersionMarker() {
        if (configMap == null) {
            return;
        }
        Integer inlineVersion = extractConfigVersion(configMap);
        if (inlineVersion != null && inlineVersion == bundledConfigVersion) {
            return;
        }
        configMap.put(VersionRegistry.CONFIG_VERSION_KEY, bundledConfigVersion);
        save();
    }

    private List<String> appendPath(List<String> basePath, String nextKey) {
        List<String> mergedPath = new ArrayList<>(basePath);
        mergedPath.add(nextKey);
        return mergedPath;
    }

    private boolean isCompatibleForMigration(Object expectedValue, Object candidateValue) {
        if (expectedValue == null || candidateValue == null) {
            return true;
        }
        if (expectedValue instanceof Map<?, ?>) {
            return candidateValue instanceof Map<?, ?>;
        }
        if (expectedValue instanceof List<?>) {
            return candidateValue instanceof List<?>;
        }
        if (candidateValue instanceof Map<?, ?> || candidateValue instanceof List<?>) {
            return false;
        }
        if (expectedValue instanceof Number && candidateValue instanceof Number) {
            return true;
        }
        return expectedValue.getClass().isAssignableFrom(candidateValue.getClass())
                || candidateValue.getClass().isAssignableFrom(expectedValue.getClass());
    }

    private int suffixSimilarity(List<String> leftPath, List<String> rightPath) {
        int matches = 0;
        int l = leftPath.size() - 1;
        int r = rightPath.size() - 1;
        while (l >= 0 && r >= 0) {
            if (!Objects.equals(leftPath.get(l), rightPath.get(r))) {
                break;
            }
            matches++;
            l--;
            r--;
        }
        return matches;
    }

    private String toPathKey(List<String> path) {
        return String.join("\u001F", path);
    }

    private void indexExistingValues(Map<String, Object> map, List<String> currentPath,
            Map<String, List<ExistingPathValue>> byKey) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            List<String> path = appendPath(currentPath, entry.getKey());
            ExistingPathValue pathValue = new ExistingPathValue(path, entry.getValue());
            byKey.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(pathValue);
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedMap) {
                indexExistingValues(toMutableMap(nestedMap), path, byKey);
            }
        }
    }

    private record ExistingPathValue(List<String> path, Object value) {
    }

    private final class ExistingValueIndex {
        private final Map<String, List<ExistingPathValue>> valuesByKey = new LinkedHashMap<>();
        private final Set<String> consumedPaths = new HashSet<>();

        private ExistingValueIndex(Map<String, Object> existingRoot) {
            indexExistingValues(existingRoot == null ? new LinkedHashMap<>() : existingRoot, new ArrayList<>(),
                    valuesByKey);
        }

        private ExistingPathValue takeBestMatch(String key, Object expectedValue, List<String> targetPath) {
            List<ExistingPathValue> candidates = valuesByKey.getOrDefault(key, Collections.emptyList());
            ExistingPathValue best = null;
            int bestScore = -1;
            for (ExistingPathValue candidate : candidates) {
                if (consumedPaths.contains(toPathKey(candidate.path()))) {
                    continue;
                }
                if (!isCompatibleForMigration(expectedValue, candidate.value())) {
                    continue;
                }
                int score = suffixSimilarity(candidate.path(), targetPath);
                if (best == null || score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best != null) {
                consumedPaths.add(toPathKey(best.path()));
            }
            return best;
        }

        private boolean isConsumed(List<String> path) {
            return consumedPaths.contains(toPathKey(path));
        }
    }

    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                copy.put(entry.getKey().toString(), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> listValue) {
            List<Object> copy = new ArrayList<>(listValue.size());
            for (Object entry : listValue) {
                copy.add(deepCopyValue(entry));
            }
            return copy;
        }
        return value;
    }

    private void writeMergedWithBundledTemplate(Map<String, Object> merged,
            Map<String, Object> bundled) throws IOException {
        String template = loadBundledTemplateText();
        if (template == null || template.isBlank()) {
            try (FileWriter writer = new FileWriter(configFile)) {
                yaml.dump(merged, writer);
            }
            return;
        }

        List<String> lines = new ArrayList<>(template.lines().toList());
        Map<String, Object> overridesWithinTemplate = getTemplateScopedOverrides(merged, bundled);
        applyTemplateOverrides(lines, overridesWithinTemplate);

        Map<String, Object> extraKeys = collectKeysMissingFromTemplate(merged, bundled);
        StringBuilder output = new StringBuilder(String.join(System.lineSeparator(), lines));
        if (!extraKeys.isEmpty()) {
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
                output.append(System.lineSeparator());
            }
            output.append(System.lineSeparator())
                    .append("# Preserved legacy keys from previous config")
                    .append(System.lineSeparator());
            output.append(yaml.dump(extraKeys));
        }

        Files.writeString(configFile.toPath(), output.toString());
    }

    private String loadBundledTemplateText() throws IOException {
        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes());
        }
    }

    private Map<String, Object> getTemplateScopedOverrides(Map<String, Object> merged, Map<String, Object> bundled) {
        return intersectWithTemplate(merged, bundled);
    }

    private Map<String, Object> intersectWithTemplate(Map<String, Object> merged, Map<String, Object> bundled) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (merged == null || bundled == null) {
            return result;
        }

        for (Map.Entry<String, Object> entry : bundled.entrySet()) {
            String key = entry.getKey();
            if (!merged.containsKey(key)) {
                continue;
            }
            Object bundledValue = entry.getValue();
            Object mergedValue = merged.get(key);
            if (bundledValue instanceof Map<?, ?> bundledMap && mergedValue instanceof Map<?, ?> mergedMap) {
                Map<String, Object> nested = intersectWithTemplate(toMutableMap(mergedMap), toMutableMap(bundledMap));
                if (!nested.isEmpty()) {
                    result.put(key, nested);
                }
            } else {
                if (!Objects.equals(mergedValue, bundledValue)) {
                    result.put(key, deepCopyValue(mergedValue));
                }
            }
        }

        return result;
    }

    private Map<String, Object> collectKeysMissingFromTemplate(Map<String, Object> merged,
            Map<String, Object> bundled) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (merged == null) {
            return extras;
        }
        if (bundled == null) {
            extras.putAll(merged);
            return extras;
        }

        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            String key = entry.getKey();
            if (!bundled.containsKey(key)) {
                extras.put(key, deepCopyValue(entry.getValue()));
                continue;
            }
            Object mergedValue = entry.getValue();
            Object bundledValue = bundled.get(key);
            if (mergedValue instanceof Map<?, ?> mergedMap && bundledValue instanceof Map<?, ?> bundledMap) {
                Map<String, Object> nestedExtras = collectKeysMissingFromTemplate(toMutableMap(mergedMap),
                        toMutableMap(bundledMap));
                if (!nestedExtras.isEmpty()) {
                    extras.put(key, nestedExtras);
                }
            }
        }

        return extras;
    }

    private void applyTemplateOverrides(List<String> lines, Map<String, Object> overrides) {
        List<PathValue> flattened = new ArrayList<>();
        flattenPaths(overrides, new ArrayList<>(), flattened);
        flattened.sort((a, b) -> Integer.compare(b.path().size(), a.path().size()));
        for (PathValue pathValue : flattened) {
            replacePathValue(lines, pathValue.path(), pathValue.value());
        }
    }

    private void flattenPaths(Map<String, Object> map, List<String> pathPrefix, List<PathValue> out) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            List<String> path = new ArrayList<>(pathPrefix);
            path.add(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nestedMap) {
                flattenPaths(toMutableMap(nestedMap), path, out);
            } else {
                out.add(new PathValue(path, value));
            }
        }
    }

    private void replacePathValue(List<String> lines, List<String> path, Object value) {
        KeyLocation location = findPathLocation(lines, path);
        if (location == null) {
            return;
        }

        String oldLine = lines.get(location.lineIndex());
        int commentIndex = oldLine.indexOf('#');
        String comment = commentIndex >= 0 ? oldLine.substring(commentIndex) : "";
        String content = commentIndex >= 0 ? oldLine.substring(0, commentIndex) : oldLine;
        int colonIndex = content.indexOf(':');
        if (colonIndex < 0) {
            return;
        }

        int pos = colonIndex + 1;
        while (pos < content.length() && content.charAt(pos) == ' ') {
            pos++;
        }
        String oldRawValue = content.substring(Math.min(pos, content.length())).trim();

        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (oldRawValue.isBlank()) {
                if (isEmptyCollection(value)) {
                    String prefix = content.substring(0, colonIndex + 1);
                    String spacing = pos > colonIndex + 1 ? content.substring(colonIndex + 1, pos) : " ";
                    String rebuiltLine = prefix + spacing + toInlineFlowYaml(value);
                    if (!comment.isEmpty()) {
                        if (!rebuiltLine.endsWith(" ")) {
                            rebuiltLine += " ";
                        }
                        rebuiltLine += comment;
                    }
                    lines.set(location.lineIndex(), rebuiltLine);
                    removeNestedBlockLines(lines, location.lineIndex(), location.indent());
                    return;
                }

                String rebuiltLine = content.substring(0, colonIndex + 1);
                if (!comment.isEmpty()) {
                    if (!rebuiltLine.endsWith(" ")) {
                        rebuiltLine += " ";
                    }
                    rebuiltLine += comment;
                }
                lines.set(location.lineIndex(), rebuiltLine);

                int insertAt = location.lineIndex() + 1;
                removeNestedBlockLines(lines, location.lineIndex(), location.indent());
                List<String> renderedBlock = renderBlockLines(value, location.indent() + 2);
                lines.addAll(insertAt, renderedBlock);
                return;
            } else {
                String prefix = content.substring(0, colonIndex + 1);
                String spacing = pos > colonIndex + 1 ? content.substring(colonIndex + 1, pos) : " ";
                String rebuiltLine = prefix + spacing + toInlineFlowYaml(value);
                if (!comment.isEmpty()) {
                    if (!rebuiltLine.endsWith(" ")) {
                        rebuiltLine += " ";
                    }
                    rebuiltLine += comment;
                }
                lines.set(location.lineIndex(), rebuiltLine);
            }
            return;
        }

        String prefix = content.substring(0, colonIndex + 1);
        String spacing = " ";
        if (pos > colonIndex + 1) {
            spacing = content.substring(colonIndex + 1, pos);
        }

        String rendered = formatScalarLikeTemplate(value, oldRawValue);
        String rebuiltLine = prefix + spacing + rendered;
        if (!comment.isEmpty()) {
            if (!rebuiltLine.endsWith(" ")) {
                rebuiltLine += " ";
            }
            rebuiltLine += comment;
        }
        lines.set(location.lineIndex(), rebuiltLine);
    }

    private boolean isEmptyCollection(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return mapValue.isEmpty();
        }
        if (value instanceof List<?> listValue) {
            return listValue.isEmpty();
        }
        return false;
    }

    private void removeNestedBlockLines(List<String> lines, int parentLineIndex, int parentIndent) {
        int start = parentLineIndex + 1;
        int cursor = start;
        while (cursor < lines.size()) {
            String nextLine = lines.get(cursor);
            String trimmed = nextLine.stripLeading();
            if (trimmed.isBlank()) {
                break;
            }
            int indent = leadingSpaces(nextLine);
            if (indent <= parentIndent) {
                break;
            }
            cursor++;
        }
        if (cursor > start) {
            lines.subList(start, cursor).clear();
        }
    }

    private List<String> renderBlockLines(Object value, int indent) {
        String dumped = yaml.dump(value);
        if (dumped == null || dumped.isBlank()) {
            return Collections.emptyList();
        }
        String withoutTrailing = dumped.stripTrailing();
        if (withoutTrailing.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> rawLines = new ArrayList<>(List.of(withoutTrailing.split("\\R")));
        rawLines = normalizeScalarChildListsToInline(rawLines);
        String prefix = " ".repeat(Math.max(0, indent));
        List<String> rendered = new ArrayList<>(rawLines.size());
        for (String rawLine : rawLines) {
            rendered.add(prefix + rawLine);
        }
        return rendered;
    }

    private List<String> normalizeScalarChildListsToInline(List<String> lines) {
        List<String> normalized = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            int keyIndent = leadingSpaces(line);
            if (!trimmed.endsWith(":") || trimmed.startsWith("- ")) {
                normalized.add(line);
                i++;
                continue;
            }

            int childIndent = keyIndent + 2;
            int j = i + 1;
            List<String> scalarItems = new ArrayList<>();
            boolean sawChild = false;
            boolean compatible = true;
            Integer observedItemIndent = null;

            while (j < lines.size()) {
                String childLine = lines.get(j);
                String childTrimmed = childLine.stripLeading();
                if (childTrimmed.isBlank()) {
                    break;
                }
                int indent = leadingSpaces(childLine);
                if (indent < keyIndent) {
                    break;
                }

                boolean isListItemAtExpectedIndent = childTrimmed.startsWith("- ")
                        && (indent == childIndent || indent == keyIndent);
                if (isListItemAtExpectedIndent) {
                    if (observedItemIndent != null && indent != observedItemIndent) {
                        compatible = false;
                        break;
                    }
                    observedItemIndent = indent;
                    sawChild = true;
                    String item = childTrimmed.substring(2).trim();
                    if (item.isEmpty() || item.contains(": ") || item.endsWith(":")) {
                        compatible = false;
                        break;
                    }
                    scalarItems.add(item);
                    j++;
                    continue;
                }

                if (indent == keyIndent) {
                    break;
                }

                sawChild = true;
                compatible = false;
                break;
            }

            if (sawChild && compatible && !scalarItems.isEmpty()) {
                normalized.add(line + " [" + String.join(", ", scalarItems) + "]");
                i = j;
                continue;
            }

            normalized.add(line);
            i++;
        }
        return normalized;
    }

    private KeyLocation findPathLocation(List<String> lines, List<String> targetPath) {
        List<PathAtIndent> stack = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }

            int indent = leadingSpaces(line);
            Matcher matcher = KEY_LINE_PATTERN.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            String rawKey = matcher.group(1).trim();
            String key = stripYamlQuotes(rawKey);

            while (!stack.isEmpty() && indent <= stack.get(stack.size() - 1).indent()) {
                stack.remove(stack.size() - 1);
            }
            stack.add(new PathAtIndent(key, indent));

            List<String> currentPath = new ArrayList<>();
            for (PathAtIndent pathEntry : stack) {
                currentPath.add(pathEntry.key());
            }
            if (currentPath.equals(targetPath)) {
                return new KeyLocation(i, indent);
            }
        }
        return null;
    }

    private String formatScalarLikeTemplate(Object value, String oldRawValue) {
        if (oldRawValue != null && oldRawValue.length() >= 2) {
            if (oldRawValue.startsWith("\"") && oldRawValue.endsWith("\"")) {
                String raw = value == null ? "null" : value.toString();
                return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
            if (oldRawValue.startsWith("'") && oldRawValue.endsWith("'")) {
                String raw = value == null ? "null" : value.toString();
                return "'" + raw.replace("'", "''") + "'";
            }
        }
        return toInlineYaml(value);
    }

    private String toInlineYaml(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value.toString();
        if (text.matches("[A-Za-z0-9_./:-]+")) {
            return text;
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String toInlineFlowYaml(Object value) {
        String dumped = flowYaml.dump(value);
        if (dumped == null) {
            return "[]";
        }
        return dumped.strip();
    }

    private int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private String stripYamlQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record PathAtIndent(String key, int indent) {
    }

    private record KeyLocation(int lineIndex, int indent) {
    }

    private record PathValue(List<String> path, Object value) {
    }

    private int resolveBundledConfigVersion() {
        Integer centralizedVersion = VersionRegistry.getResourceConfigVersion(resourceName);
        if (centralizedVersion != null) {
            return centralizedVersion;
        }

        try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Bundled config resource missing: " + resourceName);
            }
            Map<String, Object> bundledMap = toMutableMap(yaml.load(in));
            Integer version = extractConfigVersion(bundledMap);
            if (version == null) {
                LOGGER.atWarning().log("Bundled resource %s is missing config_version; defaulting to 1", resourceName);
                return 1;
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read bundled config resource", e);
        }
    }

    private Integer extractConfigVersion(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Object versionValue = source.get(VersionRegistry.CONFIG_VERSION_KEY);
        if (versionValue instanceof Number number) {
            double raw = number.doubleValue();
            if (Double.isNaN(raw) || raw % 1 != 0) {
                return null; // reject decimal versions
            }
            long numeric = number.longValue();
            if (numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                return null;
            }
            return (int) numeric;
        }
        if (versionValue instanceof String str) {
            String trimmed = str.trim();
            if (!trimmed.matches("-?\\d+")) {
                return null;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
