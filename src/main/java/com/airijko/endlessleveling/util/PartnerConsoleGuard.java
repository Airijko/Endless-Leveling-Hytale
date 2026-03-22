package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Guards console execution of admin commands behind EndlessLevelingPartnerAddon
 * authorization.
 *
 * <ul>
 *   <li>If the partner addon is absent → console is not allowed.</li>
 *   <li>If the partner addon is present and authorized → console is allowed.</li>
 *   <li>If the partner addon is present but <em>not</em> authorized → a warning
 *       is logged and console is denied.</li>
 * </ul>
 */
public final class PartnerConsoleGuard {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String PARTNER_CLASS =
            "com.airijko.endlessleveling.EndlessLevelingPartnerAddon";

    private PartnerConsoleGuard() {
    }

    /** Returns {@code true} when the EndlessLevelingPartnerAddon class is on the classpath. */
    public static boolean isPartnerAddonPresent() {
        try {
            Class.forName(PARTNER_CLASS);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Checks whether the console is permitted to execute an admin command.
     *
     * <p>Allowed only when EndlessLevelingPartnerAddon is present AND the partner
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
                    + "EndlessLevelingPartnerAddon is present with an invalid or "
                    + "unauthorized partner domain. Console admin access denied. "
                    + "Verify your partner server host configuration.",
                    commandLabel);
            return false;
        }

        return true;
    }
}
