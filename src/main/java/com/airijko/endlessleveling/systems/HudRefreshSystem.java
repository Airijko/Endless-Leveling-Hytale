package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.ui.PlayerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodically refreshes the Endless Leveling HUD so dynamic values (e.g.,
 * local mob level)
 * stay in sync with player position without relying on manual triggers.
 */
public class HudRefreshSystem extends TickingSystem<EntityStore> {

    private static final float DIRTY_REFRESH_INTERVAL_SECONDS = 0.1f;
    private static final float FALLBACK_REFRESH_INTERVAL_SECONDS = 3.0f;
    private static final int MAX_DIRTY_REFRESHES_PER_PASS = 48;
    private static final int MAX_FALLBACK_REFRESHES_PER_PASS = 16;
    private static final double MOVEMENT_EPSILON_SQUARED = 0.09D;

    private float timeSinceDirtyRefresh = 0f;
    private float timeSinceFallbackRefresh = 0f;
    private final Map<UUID, PositionSample> lastFallbackPositions = new HashMap<>();

    public HudRefreshSystem() {
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, @Nonnull Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return;
        }

        if (!PlayerHud.hasActiveHuds()) {
            if (!lastFallbackPositions.isEmpty()) {
                lastFallbackPositions.clear();
            }
            return;
        }

        timeSinceDirtyRefresh += deltaSeconds;
        if (timeSinceDirtyRefresh >= DIRTY_REFRESH_INTERVAL_SECONDS) {
            timeSinceDirtyRefresh = 0f;
            refreshDirtyHudsForStore(store);
        }

        timeSinceFallbackRefresh += deltaSeconds;
        if (timeSinceFallbackRefresh >= FALLBACK_REFRESH_INTERVAL_SECONDS) {
            timeSinceFallbackRefresh = 0f;
            refreshAllHudsForStore(store);
        }
    }

    private void refreshDirtyHudsForStore(Store<EntityStore> store) {
        Set<UUID> dirtyHuds = PlayerHud.snapshotDirtyHudUuids();
        if (dirtyHuds.isEmpty()) {
            return;
        }

        int refreshed = 0;

        for (UUID uuid : dirtyHuds) {
            if (uuid == null) {
                continue;
            }

            if (!PlayerHud.isActive(uuid)) {
                PlayerHud.clearDirty(uuid);
                continue;
            }

            if (!PlayerHud.isHudInStore(uuid, store)) {
                continue;
            }

            PlayerHud.refreshHudNow(uuid);
            PlayerHud.clearDirty(uuid);
            refreshed++;
            if (refreshed >= MAX_DIRTY_REFRESHES_PER_PASS) {
                break;
            }
        }
    }

    private void refreshAllHudsForStore(Store<EntityStore> store) {
        int refreshed = 0;
        Set<UUID> activeHudUuids = PlayerHud.getActiveHudUuids();

        for (UUID uuid : activeHudUuids) {
            if (uuid == null || !PlayerHud.isActive(uuid)) {
                lastFallbackPositions.remove(uuid);
                continue;
            }

            if (!PlayerHud.isHudInStore(uuid, store)) {
                continue;
            }

            if (!shouldRefreshForMovement(uuid, store)) {
                continue;
            }

            PlayerHud.refreshHudNow(uuid);
            refreshed++;
            if (refreshed >= MAX_FALLBACK_REFRESHES_PER_PASS) {
                break;
            }
        }

        if (!lastFallbackPositions.isEmpty()) {
            lastFallbackPositions.keySet().removeIf(uuid -> !activeHudUuids.contains(uuid));
        }
    }

    private boolean shouldRefreshForMovement(UUID uuid, Store<EntityStore> store) {
        Ref<EntityStore> entityRef = PlayerHud.getHudEntityRef(uuid);
        if (entityRef == null || entityRef.getStore() != store) {
            return false;
        }

        TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return false;
        }

        PositionSample current = new PositionSample(
                transform.getPosition().getX(),
                transform.getPosition().getY(),
                transform.getPosition().getZ());

        PositionSample previous = lastFallbackPositions.get(uuid);
        if (previous == null) {
            lastFallbackPositions.put(uuid, current);
            return true;
        }

        double dx = current.x - previous.x;
        double dy = current.y - previous.y;
        double dz = current.z - previous.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared < MOVEMENT_EPSILON_SQUARED) {
            return false;
        }

        lastFallbackPositions.put(uuid, current);
        return true;
    }

    private static final class PositionSample {
        private final double x;
        private final double y;
        private final double z;

        private PositionSample(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
