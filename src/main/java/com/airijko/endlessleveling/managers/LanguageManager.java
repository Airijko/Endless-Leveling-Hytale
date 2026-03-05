package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class LanguageManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String DEFAULT_LOCALE = "en_US";

    private final PluginFilesManager filesManager;
    private final ConfigManager configManager;
    private final boolean forceBuiltinLanguages;
    private final Yaml yaml = new Yaml();

    private volatile String activeLocale = DEFAULT_LOCALE;
    private volatile String fallbackLocale = DEFAULT_LOCALE;
    private volatile Map<String, String> activeTranslations = Collections.emptyMap();
    private volatile Map<String, String> fallbackTranslations = Collections.emptyMap();
    private final Map<String, Map<String, String>> localeCache = new ConcurrentHashMap<>();

    public LanguageManager(PluginFilesManager filesManager, ConfigManager configManager) {
        this.filesManager = filesManager;
        this.configManager = configManager;
        Object forceFlag = configManager != null
                ? configManager.get("force_builtin_languages", Boolean.TRUE, false)
                : Boolean.TRUE;
        this.forceBuiltinLanguages = parseBoolean(forceFlag, true);
        reload();
    }

    public void reload() {
        String configuredLocale = normalizeLocale(Objects.toString(
                configManager != null ? configManager.get("language.locale", DEFAULT_LOCALE, false) : DEFAULT_LOCALE,
                DEFAULT_LOCALE));
        syncBuiltinLanguagesIfNeeded();

        String configuredFallback = normalizeLocale(Objects.toString(
                configManager != null ? configManager.get("language.fallback_locale", DEFAULT_LOCALE, false)
                        : DEFAULT_LOCALE,
                DEFAULT_LOCALE));

        localeCache.clear();

        Map<String, String> loadedFallback = loadLocaleMap(configuredFallback, true);
        Map<String, String> loadedActive;
        if (configuredLocale.equalsIgnoreCase(configuredFallback)) {
            loadedActive = loadedFallback;
        } else {
            loadedActive = loadLocaleMap(configuredLocale, false);
        }

        fallbackLocale = configuredFallback;
        activeLocale = configuredLocale;
        fallbackTranslations = loadedFallback;
        activeTranslations = loadedActive;

        LOGGER.atInfo().log("Language loaded: locale=%s, fallback=%s, keys=%d", activeLocale, fallbackLocale,
                activeTranslations.size());
    }

    public String getActiveLocale() {
        return activeLocale;
    }

    public List<String> getAvailableLocales() {
        File folder = filesManager.getLangFolder();
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return List.of(DEFAULT_LOCALE);
        }

        File[] files = folder.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of(DEFAULT_LOCALE);
        }

        List<String> locales = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            locales.add(normalizeLocale(name.substring(0, dot)));
        }

        if (locales.isEmpty()) {
            return List.of(DEFAULT_LOCALE);
        }

        locales.sort(Comparator.naturalOrder());
        return Collections.unmodifiableList(locales.stream().distinct().toList());
    }

    public boolean isLocaleAvailable(String locale) {
        String normalized = normalizeLocale(locale);
        File langFile = new File(filesManager.getLangFolder(), normalized + ".yml");
        return langFile.exists() && langFile.isFile();
    }

    public void invalidateLocaleCache(String locale) {
        String normalized = normalizeLocale(locale);
        localeCache.remove(normalized);
    }

    public String tr(String key, String fallback, Object... args) {
        String template = resolveTemplate(key, fallback);
        return format(template, args);
    }

    public String tr(UUID playerUuid, String key, String fallback, Object... args) {
        String locale = resolvePlayerLocale(playerUuid);
        String template = resolveTemplate(locale, key, fallback);
        return format(template, args);
    }

    private String resolvePlayerLocale(UUID playerUuid) {
        if (playerUuid == null) {
            return activeLocale;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getPlayerDataManager() == null) {
            return activeLocale;
        }

        PlayerData playerData = plugin.getPlayerDataManager().get(playerUuid);
        if (playerData == null) {
            return activeLocale;
        }

        String preferred = playerData.getLanguage();
        if (preferred == null || preferred.isBlank()) {
            return activeLocale;
        }
        return normalizeLocale(preferred);
    }

    private String resolveTemplate(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback != null ? fallback : "";
        }

        String translated = activeTranslations.get(key);
        if (translated != null) {
            return translated;
        }

        translated = fallbackTranslations.get(key);
        if (translated != null) {
            return translated;
        }

        return fallback != null ? fallback : key;
    }

    private String resolveTemplate(String locale, String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback != null ? fallback : "";
        }

        String normalizedLocale = normalizeLocale(locale);
        Map<String, String> localeTranslations = getLocaleTranslations(normalizedLocale);
        String translated = localeTranslations.get(key);
        if (translated != null) {
            return translated;
        }

        translated = fallbackTranslations.get(key);
        if (translated != null) {
            return translated;
        }

        translated = activeTranslations.get(key);
        if (translated != null) {
            return translated;
        }

        return fallback != null ? fallback : key;
    }

    private Map<String, String> getLocaleTranslations(String locale) {
        String normalized = normalizeLocale(locale);
        if (normalized.equalsIgnoreCase(activeLocale)) {
            return activeTranslations;
        }
        if (normalized.equalsIgnoreCase(fallbackLocale)) {
            return fallbackTranslations;
        }
        return localeCache.computeIfAbsent(normalized, loc -> loadLocaleMap(loc, false));
    }

    private Map<String, String> loadLocaleMap(String locale, boolean required) {
        File langFile = new File(filesManager.getLangFolder(), locale + ".yml");
        if (!langFile.exists()) {
            if (required) {
                LOGGER.atWarning().log("Language file %s is missing. Falling back to empty translations.",
                        langFile.getAbsolutePath());
            } else {
                LOGGER.atWarning().log("Language file %s not found. Using fallback locale %s.",
                        langFile.getAbsolutePath(), fallbackLocale);
            }
            return Collections.emptyMap();
        }

        try (Reader reader = new FileReader(langFile)) {
            Object root = yaml.load(reader);
            Map<String, String> flattened = new ConcurrentHashMap<>();
            flattenNode("", root, flattened);
            return Collections.unmodifiableMap(flattened);
        } catch (IOException | RuntimeException ex) {
            LOGGER.atWarning().log("Failed to load language file %s: %s", langFile.getAbsolutePath(),
                    ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private void flattenNode(String prefix, Object node, Map<String, String> target) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().toString().trim();
                if (key.isEmpty()) {
                    continue;
                }
                String nextPrefix = prefix.isEmpty() ? key : prefix + "." + key;
                flattenNode(nextPrefix, entry.getValue(), target);
            }
            return;
        }

        if (node == null) {
            return;
        }

        if (!prefix.isEmpty()) {
            target.put(prefix, node.toString());
        }
    }

    private static String normalizeLocale(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String trimmed = value.trim().replace('-', '_');
        String[] parts = trimmed.split("_");
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }

    private static String format(String template, Object... args) {
        if (template == null) {
            return "";
        }
        if (args == null || args.length == 0) {
            return template;
        }

        String formatted = template;
        for (int i = 0; i < args.length; i++) {
            String replacement = String.valueOf(args[i]);
            formatted = formatted.replace("{" + i + "}", replacement);
        }
        return formatted;
    }

    private void syncBuiltinLanguagesIfNeeded() {
        if (!forceBuiltinLanguages) {
            return;
        }

        File langFolder = filesManager.getLangFolder();
        if (langFolder == null) {
            LOGGER.atWarning().log("Language folder is null; cannot sync built-in languages.");
            return;
        }

        int storedVersion = readLangVersion(langFolder);
        if (storedVersion == VersionRegistry.BUILTIN_LANG_VERSION) {
            return;
        }

        filesManager.archivePathIfExists(langFolder.toPath(), "lang", "lang.version:" + storedVersion);
        clearDirectory(langFolder.toPath());
        filesManager.exportResourceDirectory("lang", langFolder, true);
        writeLangVersion(langFolder, VersionRegistry.BUILTIN_LANG_VERSION);
        LOGGER.atInfo().log("Synced built-in language files to version %d (force_builtin_languages=true)",
                VersionRegistry.BUILTIN_LANG_VERSION);
    }

    private int readLangVersion(File langFolder) {
        Path versionPath = langFolder.toPath().resolve(VersionRegistry.LANG_VERSION_FILE);
        if (!Files.exists(versionPath)) {
            return -1;
        }
        try {
            String text = Files.readString(versionPath).trim();
            return Integer.parseInt(text);
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to read language version file: %s", e.getMessage());
            return -1;
        }
    }

    private void writeLangVersion(File langFolder, int version) {
        Path versionPath = langFolder.toPath().resolve(VersionRegistry.LANG_VERSION_FILE);
        try {
            Files.writeString(versionPath, Integer.toString(version));
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to write language version file: %s", e.getMessage());
        }
    }

    private void clearDirectory(Path folder) {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(folder))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            LOGGER.atWarning().log("Failed to delete %s: %s", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to clear language directory: %s", e.getMessage());
        }
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return defaultValue;
    }
}
