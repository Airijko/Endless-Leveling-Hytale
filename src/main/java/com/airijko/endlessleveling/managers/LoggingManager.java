package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Central logging controls driven by config.yml.
 * <p>
 * Behavior:
 * - enable_logging=true -> Level.ALL for all mod loggers.
 * - enable_logging=false -> keep key logs on (base level) and optionally enable
 * specific sections for deep debugging without global spam.
 */
public final class LoggingManager {

    private static final String LOGGER_PREFIX = "com.airijko.endlessleveling";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Level DEFAULT_BASE_LEVEL = Level.WARNING;

    private static volatile boolean enableAll = true;
    private static volatile Level baseLevel = DEFAULT_BASE_LEVEL;
    private static volatile Set<String> debugSections = Collections.emptySet();

    private static boolean loaderInstalled = false;
    private static Function<String, Level> previousLoader;

    private LoggingManager() {
    }

    /**
     * Configure logging from config.yml values.
     */
    public static void configureFromConfig(ConfigManager configManager) {
        if (configManager == null) {
            configure(true, Collections.emptySet(), DEFAULT_BASE_LEVEL);
            return;
        }

        boolean enabled = toBoolean(configManager.get("enable_logging", Boolean.FALSE, false), false);
        Level configuredBase = resolveLevel(
                configManager.get("logging.base_level", DEFAULT_BASE_LEVEL.getName(), false));
        Collection<String> configuredSections = resolveSections(
                configManager.get("logging.debug_sections", Collections.emptyList(), false));

        configure(enabled, configuredSections, configuredBase);
    }

    /**
     * Configure logging with explicit values (main entry point).
     */
    public static void configure(boolean enableAllLogging, Collection<String> debugSectionPrefixes,
            Level minimumLevel) {
        enableAll = enableAllLogging;

        Level effectiveBase = minimumLevel != null ? minimumLevel : DEFAULT_BASE_LEVEL;
        if (!enableAll && effectiveBase.intValue() < Level.WARNING.intValue()) {
            effectiveBase = Level.WARNING;
        }
        baseLevel = effectiveBase;
        debugSections = new LinkedHashSet<>(normalizeSections(debugSectionPrefixes));

        installLoaderIfNeeded();
        HytaleLoggerBackend.reloadLogLevels();

        LOGGER.atInfo().log(
                "Logging configured: enable_all=%s base_level=%s debug_sections=%s",
                enableAll, baseLevel.getName(), debugSections.isEmpty() ? "none" : debugSections);
    }

    /**
     * Legacy signature retained for compatibility.
     */
    public static void configure(boolean enableAllLogging) {
        configure(enableAllLogging, Collections.emptySet(), DEFAULT_BASE_LEVEL);
    }

    private static void installLoaderIfNeeded() {
        if (loaderInstalled) {
            return;
        }
        previousLoader = HytaleLoggerBackend.LOG_LEVEL_LOADER;
        HytaleLoggerBackend.LOG_LEVEL_LOADER = LoggingManager::resolveLevel;
        loaderInstalled = true;
    }

    private static Level resolveLevel(String loggerName) {
        if (loggerName != null && loggerName.startsWith(LOGGER_PREFIX)) {
            if (enableAll) {
                return Level.ALL;
            }
            for (String section : debugSections) {
                if (loggerName.startsWith(section)) {
                    return Level.ALL;
                }
            }
            return baseLevel;
        }
        return previousLoader != null ? previousLoader.apply(loggerName) : null;
    }

    public static boolean isLoggingEnabled() {
        return enableAll;
    }

    private static Collection<String> normalizeSections(Collection<String> rawSections) {
        if (rawSections == null || rawSections.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawSections) {
            String normalizedSection = normalizeSection(raw);
            if (normalizedSection != null) {
                normalized.add(normalizedSection);
            }
        }
        return normalized;
    }

    private static String normalizeSection(String rawSection) {
        if (rawSection == null) {
            return null;
        }
        String trimmed = rawSection.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Allow shorthand like "augments" or ".systems"; fall back to full package if
        // provided.
        if (trimmed.startsWith(LOGGER_PREFIX)) {
            return trimmed;
        }
        if (trimmed.startsWith(".")) {
            return LOGGER_PREFIX + trimmed;
        }
        return LOGGER_PREFIX + "." + trimmed;
    }

    private static Level resolveLevel(Object value) {
        if (value instanceof Level level) {
            return level;
        }
        if (value instanceof String str) {
            try {
                return Level.parse(str.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return DEFAULT_BASE_LEVEL;
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        return defaultValue;
    }

    private static Collection<String> resolveSections(Object value) {
        if (value instanceof Collection<?> collection) {
            Set<String> sections = new LinkedHashSet<>();
            for (Object item : collection) {
                if (item != null) {
                    sections.add(item.toString());
                }
            }
            return sections;
        }
        if (value instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return Collections.emptySet();
            }
            // Support comma-separated strings.
            String[] parts = trimmed.split(",");
            Set<String> sections = new LinkedHashSet<>();
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    sections.add(part.trim());
                }
            }
            return sections;
        }
        return Collections.emptySet();
    }
}
