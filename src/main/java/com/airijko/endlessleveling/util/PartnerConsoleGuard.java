package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Guards console execution of admin commands behind recognized partner-addon
 * authorization.
 *
 * <ul>
 *   <li>If no recognized partner addon is present → console is not allowed.</li>
 *   <li>If the partner addon is present and authorized → console is allowed.</li>
 *   <li>If the partner addon is present but <em>not</em> authorized → a warning
 *       is logged and console is denied.</li>
 * </ul>
 */
public final class PartnerConsoleGuard {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String PARTNER_CLASS =
            "com.airijko.endlessleveling.EndlessLevelingPartnerAddon";
    private static final String ARANK_CLASS =
            "com.airijko.endlessleveling.EndlessLevelingARankAddon";

    private PartnerConsoleGuard() {
    }

    /** Returns {@code true} when a recognized partner addon class is on the classpath. */
    public static boolean isPartnerAddonPresent() {
        return isClassPresent(PARTNER_CLASS) || isClassPresent(ARANK_CLASS);
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Checks whether the console is permitted to execute an admin command.
    *
    * <p>Allowed only when a recognized partner addon is present AND the partner
     * domain is authorized. If the addon is present but unauthorized a warning is
     * written to the server log so operators can diagnose the misconfiguration.
     *
     * @param commandLabel the slash-command label used in log messages (e.g.
     *                     {@code "el augments reset"})
     * @return {@code true} if console execution should be permitted
     */
    public static boolean isConsoleAllowed(String commandLabel) {
        boolean present = isPartnerAddonPresent();
        if (!present) {
            return false;
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        boolean authorized = plugin.isPartnerAddonAuthorized();
        if (!authorized) {
            LOGGER.atWarning().log(
                    "Console attempted to execute admin command '/%s' but "
                    + "a partner addon is present with an invalid or "
                    + "unauthorized partner domain. Console admin access denied. "
                    + "Verify your partner server host configuration.",
                    commandLabel);
            return false;
        }

        return true;
    }
}
