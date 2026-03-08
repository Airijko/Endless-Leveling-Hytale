package com.airijko.endlessleveling.util;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Utilities for detecting world context (normal world vs instance world).
 */
public final class WorldContextUtil {

    private WorldContextUtil() {
    }

    /**
     * Detects whether the given world is an instance world by mirroring the
     * built-in InstancesPlugin check:
     * InstanceWorldConfig.get(world.getWorldConfig()) != null.
     */
    public static boolean isInstanceWorld(World world) {
        if (world == null) {
            return false;
        }

        WorldConfig worldConfig = world.getWorldConfig();
        return worldConfig != null && InstanceWorldConfig.get(worldConfig) != null;
    }

    /**
     * Returns true when the player entity carries instance return-point metadata.
     */
    public static boolean hasInstanceReturnPoint(Ref<EntityStore> playerRef,
            ComponentAccessor<EntityStore> accessor) {
        if (playerRef == null || accessor == null) {
            return false;
        }

        InstanceEntityConfig entityConfig = accessor.getComponent(playerRef, InstanceEntityConfig.getComponentType());
        return entityConfig != null
                && (entityConfig.getReturnPoint() != null || entityConfig.getReturnPointOverride() != null);
    }

    /**
     * Detects instance context using both world config and player return-point
     * data.
     */
    public static boolean isInstanceContext(World world,
            Ref<EntityStore> playerRef,
            ComponentAccessor<EntityStore> accessor) {
        return isInstanceWorld(world) || hasInstanceReturnPoint(playerRef, accessor);
    }
}