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
    private static final String ALWAYS_INFO_PREFIX_SHUTDOWN = LOGGER_PREFIX + ".shutdown.";
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
        if (configuredSections.isEmpty()) {
            configuredSections = resolveSections(
                configManager.get("debug_sections", Collections.emptyList(), false));
        }

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
        if ("EndlessLeveling".equals(loggerName)) {
            return Level.INFO;
        }
        if (loggerName != null && loggerName.startsWith(LOGGER_PREFIX)) {
            if (loggerName.startsWith(ALWAYS_INFO_PREFIX_SHUTDOWN)) {
                return Level.INFO;
            }
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

    /**
     * Returns true if INFO-level logs should execute — i.e. {@code enable_logging=true}.
     * Use this as a fast gate before any LOGGER.atInfo() call that is not already
     * behind a debug-section check, to prevent argument evaluation overhead.
     */
    public static boolean isInfoEnabled() {
        return enableAll;
    }

    /**
     * Returns true if the given short debug-section name (e.g. "mob_level_flow",
     * "mob_augments") is active — either because {@code enable_logging=true} or
     * because it appears in {@code logging.debug_sections}.
     * <p>
     * Delegates to the same normalization used when applying log levels, ensuring
     * config.yml section names resolve consistently across all callers.
     */
    public static boolean isDebugSectionEnabled(String shortKey) {
        if (enableAll) {
            return true;
        }
        if (debugSections.isEmpty() || shortKey == null || shortKey.isBlank()) {
            return false;
        }
        String normalized = normalizeSection(shortKey);
        if (normalized == null) {
            return false;
        }
        // Re-use resolveLevel: if the normalized class name resolves to Level.ALL the
        // section is active.
        return resolveLevel(normalized) == Level.ALL;
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
        String lowered = trimmed.toLowerCase();
        if ("mob_common_offense".equals(lowered)) {
            return LOGGER_PREFIX + ".systems.PlayerDefenseSystem";
        }
        if ("mob_common_defense".equals(lowered)) {
            return LOGGER_PREFIX + ".systems.PlayerCombatSystem";
        }
        if ("mob_augment_check".equals(lowered)) {
            return LOGGER_PREFIX + ".systems.PlayerCombatSystem";
        }
        if ("mob_level_debug".equals(lowered)) {
            return LOGGER_PREFIX + ".leveling.MobLevelingManager";
        }
        if ("mob_level_flow".equals(lowered)) {
            return LOGGER_PREFIX + ".mob.MobLevelingSystem";
        }
        if ("mob_augments".equals(lowered)) {
            return LOGGER_PREFIX + ".augments.MobAugmentExecutor";
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
