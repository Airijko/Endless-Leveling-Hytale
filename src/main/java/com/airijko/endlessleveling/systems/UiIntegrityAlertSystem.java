package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.security.UiTitleIntegrityGuard;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends recurring alerts when unauthorized UI title modifications are detected.
 */
public final class UiIntegrityAlertSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private static final float SCAN_INTERVAL_SECONDS = 1.0f;
    private static final long FIRST_JOIN_ALERT_DELAY_MILLIS = 10_000L;
    private static final long ALERT_INTERVAL_MILLIS = 10_000L;

    private final UiTitleIntegrityGuard integrityGuard;
    private final Map<UUID, Long> nextAlertAtByPlayer = new HashMap<>();
    private float elapsedSeconds = 0.0f;

    public UiIntegrityAlertSystem(UiTitleIntegrityGuard integrityGuard) {
        this.integrityGuard = integrityGuard;
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown() || integrityGuard == null) {
            return;
        }

        elapsedSeconds += deltaSeconds;
        if (elapsedSeconds < SCAN_INTERVAL_SECONDS) {
            return;
        }
        elapsedSeconds = 0.0f;

        long now = System.currentTimeMillis();
        UiTitleIntegrityGuard.IntegrityResult integrityResult = integrityGuard.evaluate();
        boolean unauthorized = integrityResult.modified();
        Set<UUID> onlinePlayers = new HashSet<>();

        store.forEachChunk(PLAYER_QUERY, (ArchetypeChunk<EntityStore> chunk,
                CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                UUID uuid = playerRef.getUuid();
                if (uuid == null) {
                    continue;
                }

                onlinePlayers.add(uuid);
                nextAlertAtByPlayer.putIfAbsent(uuid, now + FIRST_JOIN_ALERT_DELAY_MILLIS);

                if (!unauthorized) {
                    continue;
                }

                long nextAlertAt = nextAlertAtByPlayer.getOrDefault(uuid, now + FIRST_JOIN_ALERT_DELAY_MILLIS);
                if (now < nextAlertAt) {
                    continue;
                }

                integrityGuard.notifyPlayerIfUnauthorized(playerRef, integrityResult);
                nextAlertAtByPlayer.put(uuid, now + ALERT_INTERVAL_MILLIS);
            }
        });

        nextAlertAtByPlayer.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }
}