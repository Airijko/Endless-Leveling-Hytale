package com.airijko.endlessleveling.cooldowns;

/**
 * Shared cooldown math helpers used across augment and passive systems.
 */
public final class CooldownMath {

    private CooldownMath() {
    }

    public static long reduceExpiresAt(long expiresAt,
            long now,
            long flatReductionMillis,
            double percentRemainingReduction) {
        if (expiresAt <= now) {
            return expiresAt;
        }

        long remaining = expiresAt - now;
        long reduction = Math.max(0L, flatReductionMillis)
                + (long) Math.floor(Math.max(0.0D, percentRemainingReduction) * remaining);
        if (reduction <= 0L) {
            return expiresAt;
        }

        return Math.max(now, expiresAt - reduction);
    }
}
