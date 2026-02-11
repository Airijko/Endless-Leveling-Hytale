package com.airijko.endlessleveling.util;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Convenience helpers for detecting whether a {@link PlayerRef} currently has
 * operator privileges.
 */
public final class OperatorHelper {

    private static final Method PLAYER_REF_IS_OPERATOR;

    static {
        Method discovered;
        try {
            discovered = PlayerRef.class.getMethod("isOperator");
            discovered.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            discovered = null;
        }
        PLAYER_REF_IS_OPERATOR = discovered;
    }

    private OperatorHelper() {
    }

    /**
     * Returns {@code true} if the provided {@link PlayerRef} exposes an
     * {@code isOperator()} method and it reports the
     * player as having operator privileges.
     */
    public static boolean isOperator(PlayerRef playerRef) {
        if (playerRef == null) {
            return false;
        }

        if (PLAYER_REF_IS_OPERATOR != null) {
            try {
                Object result = PLAYER_REF_IS_OPERATOR.invoke(playerRef);
                if (result instanceof Boolean bool && bool) {
                    return true;
                }
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // fall through to permissions-based detection
            }
        }

        return hasOperatorGroup(playerRef);
    }

    private static boolean hasOperatorGroup(PlayerRef playerRef) {
        try {
            PermissionsModule permissions = PermissionsModule.get();
            if (permissions == null) {
                return false;
            }
            Set<String> groups = permissions.getGroupsForUser(playerRef.getUuid());
            if (groups == null || groups.isEmpty()) {
                return false;
            }
            for (String group : groups) {
                if (group == null) {
                    continue;
                }
                String normalized = group.trim().toUpperCase();
                if (normalized.equals("OP") || normalized.equals("ADMIN")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Permissions API not available or another error occurred
        }
        return false;
    }
}
