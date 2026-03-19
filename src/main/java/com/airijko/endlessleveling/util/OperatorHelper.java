package com.airijko.endlessleveling.util;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Convenience helpers for detecting whether a {@link PlayerRef} currently has
 * operator privileges.
 */
public final class OperatorHelper {

    private static final Method PLAYER_REF_IS_OPERATOR;
    private static final String[] ADMIN_PERMISSION_NODES = {
        "*",
        "op",
        "operator",
        "admin",
        "hytale.*",
        "hytale.op",
        "hytale.operator",
        "hytale.admin",
        "endlessleveling.*",
        "endlessleveling.op",
        "endlessleveling.operator",
        "endlessleveling.admin"
    };
    private static final String[] PERMISSION_CHECK_METHODS = {
        "hasPermission",
        "userHasPermission",
        "hasUserPermission",
        "checkPermission",
        "isPermitted"
    };
    private static final String[] PERMISSION_LIST_METHODS = {
        "getPermissionsForUser",
        "getUserPermissions",
        "getEffectivePermissionsForUser",
        "getEffectiveUserPermissions"
    };
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

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

    /**
     * Returns true when a player should be treated as privileged staff/admin and
     * therefore excluded from regular-player integrity notices.
     */
    public static boolean hasAdministrativeAccess(PlayerRef playerRef) {
        if (playerRef == null) {
            return false;
        }
        if (isOperator(playerRef)) {
            return true;
        }
        return hasAdministrativePermissionNode(playerRef);
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
                String normalized = group.trim().toLowerCase(Locale.ROOT);
                if (normalized.equals("op") || normalized.equals("admin") || normalized.equals("administrator")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Permissions API not available or another error occurred
        }
        return false;
    }

    private static boolean hasAdministrativePermissionNode(PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null) {
            return false;
        }

        try {
            PermissionsModule permissions = PermissionsModule.get();
            if (permissions == null) {
                return false;
            }

            for (String node : ADMIN_PERMISSION_NODES) {
                if (node == null || node.isBlank()) {
                    continue;
                }
                if (hasPermissionNode(permissions, uuid, node)) {
                    return true;
                }
            }

            Set<String> permissionNodes = getPermissionNodes(permissions, uuid);
            if (permissionNodes == null || permissionNodes.isEmpty()) {
                return false;
            }
            for (String node : permissionNodes) {
                if (isAdministrativePermissionNode(node)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Permissions API not available or another error occurred
        }
        return false;
    }

    private static boolean hasPermissionNode(PermissionsModule permissions, UUID uuid, String permissionNode) {
        Method checkMethod = findPermissionCheckMethod(permissions.getClass());
        if (checkMethod == null) {
            return false;
        }

        try {
            Class<?>[] params = checkMethod.getParameterTypes();
            Object result;
            if (params.length != 2) {
                return false;
            }
            if (UUID.class.isAssignableFrom(params[0]) && String.class.isAssignableFrom(params[1])) {
                result = checkMethod.invoke(permissions, uuid, permissionNode);
            } else if (String.class.isAssignableFrom(params[0]) && UUID.class.isAssignableFrom(params[1])) {
                result = checkMethod.invoke(permissions, permissionNode, uuid);
            } else {
                return false;
            }
            return result instanceof Boolean bool && bool;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getPermissionNodes(PermissionsModule permissions, UUID uuid) {
        Method listMethod = findPermissionListMethod(permissions.getClass());
        if (listMethod == null) {
            return Set.of();
        }

        try {
            Object result = listMethod.invoke(permissions, uuid);
            if (result instanceof Set<?> set) {
                return (Set<String>) set;
            }
            if (result instanceof Iterable<?> iterable) {
                Set<String> collected = ConcurrentHashMap.newKeySet();
                for (Object entry : iterable) {
                    if (entry != null) {
                        collected.add(entry.toString());
                    }
                }
                return collected;
            }
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return Set.of();
        }
        return Set.of();
    }

    private static boolean isAdministrativePermissionNode(String node) {
        if (node == null || node.isBlank()) {
            return false;
        }

        String normalized = node.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("*") || normalized.endsWith(".*")) {
            return true;
        }

        String[] tokens = normalized.split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.equals("admin") || token.equals("administrator") || token.equals("op")
                    || token.equals("operator") || token.equals("owner")) {
                return true;
            }
        }
        return false;
    }

    private static Method findPermissionCheckMethod(Class<?> type) {
        String cacheKey = type.getName() + "#check";
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        for (String name : PERMISSION_CHECK_METHODS) {
            for (Method method : type.getMethods()) {
                if (!method.getName().equalsIgnoreCase(name)) {
                    continue;
                }
                if (method.getParameterCount() != 2 || method.getReturnType() != boolean.class) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                boolean uuidString = UUID.class.isAssignableFrom(params[0]) && String.class.isAssignableFrom(params[1]);
                boolean stringUuid = String.class.isAssignableFrom(params[0]) && UUID.class.isAssignableFrom(params[1]);
                if (uuidString || stringUuid) {
                    method.setAccessible(true);
                    METHOD_CACHE.put(cacheKey, method);
                    return method;
                }
            }
        }
        return null;
    }

    private static Method findPermissionListMethod(Class<?> type) {
        String cacheKey = type.getName() + "#list";
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        for (String name : PERMISSION_LIST_METHODS) {
            for (Method method : type.getMethods()) {
                if (!method.getName().equalsIgnoreCase(name)) {
                    continue;
                }
                if (method.getParameterCount() != 1 || !UUID.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    continue;
                }
                method.setAccessible(true);
                METHOD_CACHE.put(cacheKey, method);
                return method;
            }
        }
        return null;
    }
}
