package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;

import java.util.function.Function;
import java.util.logging.Level;

/**
 * Controls mod logging according to config.yml enable_logging flag.
 */
public final class LoggingManager {

    private static final String LOGGER_PREFIX = "com.airijko.endlessleveling";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static volatile boolean loggingEnabled = true;
    private static boolean loaderInstalled = false;
    private static Function<String, Level> previousLoader;

    private LoggingManager() {
    }

    public static void configure(boolean enabled) {
        loggingEnabled = enabled;
        installLoaderIfNeeded();
        HytaleLoggerBackend.reloadLogLevels();
        LOGGER.at(Level.INFO).log("Logging %s via config.yml", enabled ? "enabled" : "disabled");
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
            return loggingEnabled ? Level.ALL : Level.OFF;
        }
        return previousLoader != null ? previousLoader.apply(loggerName) : null;
    }

    public static boolean isLoggingEnabled() {
        return loggingEnabled;
    }
}
