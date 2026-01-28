package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Manifest;

/**
 * Keeps track of the last EndlessLeveling build that touched the data folder so
 * we can block downgrades.
 */
public class ModVersionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String VERSION_FILE_NAME = "mod-version.lock";
    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String MANIFEST_VERSION_KEY = "Plugin-Version";

    private final File versionFile;
    private final String currentVersion;

    public ModVersionManager(JavaPlugin plugin, PluginFilesManager filesManager) {
        this.versionFile = new File(filesManager.getPluginFolder(), VERSION_FILE_NAME);
        this.currentVersion = resolveCurrentVersion(plugin);
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    /** Ensure we are not running a jar older than the data on disk expects. */
    public void enforceVersionSafety() {
        String storedVersion = readStoredVersion();
        if (storedVersion == null || storedVersion.isBlank()) {
            LOGGER.atInfo().log("Recording EndlessLeveling version %s for future compatibility checks.",
                    currentVersion);
            writeCurrentVersion();
            return;
        }

        int comparison = compareVersions(currentVersion, storedVersion);
        if (comparison < 0) {
            String message = String.format(
                    "Detected EndlessLeveling downgrade. Data expects %s but jar is %s. Remove the older jar to continue.",
                    storedVersion, currentVersion);
            LOGGER.atSevere().log(message);
            throw new IllegalStateException(message);
        }

        if (comparison > 0) {
            LOGGER.atInfo().log("Upgrading EndlessLeveling from %s to %s", storedVersion, currentVersion);
            writeCurrentVersion();
        } else {
            LOGGER.atInfo().log("EndlessLeveling version %s verified", currentVersion);
        }
    }

    private String resolveCurrentVersion(JavaPlugin plugin) {
        try (InputStream in = plugin.getClassLoader().getResourceAsStream(MANIFEST_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Unable to locate plugin manifest to read version details");
            }
            Manifest manifest = new Manifest(in);
            String version = manifest.getMainAttributes().getValue(MANIFEST_VERSION_KEY);
            if (version == null || version.isBlank()) {
                version = manifest.getMainAttributes().getValue("Implementation-Version");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Plugin manifest is missing Plugin-Version entry");
            }
            return version.trim();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read plugin manifest", e);
        }
    }

    private String readStoredVersion() {
        if (!versionFile.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(versionFile))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            LOGGER.atWarning().log("Unable to read %s: %s", VERSION_FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void writeCurrentVersion() {
        versionFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(versionFile, false)) {
            writer.write(currentVersion);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write mod version lock file", e);
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("[\\.-]");
        String[] rightParts = right.split("[\\.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftToken = i < leftParts.length ? leftParts[i] : "0";
            String rightToken = i < rightParts.length ? rightParts[i] : "0";
            int result = comparePart(leftToken, rightToken);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private int comparePart(String left, String right) {
        Integer leftNumber = parseInteger(left);
        Integer rightNumber = parseInteger(right);
        if (leftNumber != null && rightNumber != null) {
            return Integer.compare(leftNumber, rightNumber);
        }
        if (leftNumber != null) {
            return 1; // numeric tokens outrank textual suffices like "beta"
        }
        if (rightNumber != null) {
            return -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
