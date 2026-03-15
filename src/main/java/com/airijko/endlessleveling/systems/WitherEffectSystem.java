package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.augments.types.WitherAugment;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class WitherEffectSystem extends TickingSystem<EntityStore> {
    private static final Query<EntityStore> QUERY = Query.any();
    private static final float TICK_INTERVAL_SECONDS = 0.2f;

    private float elapsed;

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown() || !WitherAugment.hasActiveWithers()) {
            return;
        }

        elapsed += deltaSeconds;
        if (elapsed < TICK_INTERVAL_SECONDS) {
            return;
        }
        elapsed = 0f;

        long now = System.currentTimeMillis();
        store.forEachChunk(QUERY, (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                WitherAugment.tickTarget(ref, commandBuffer, now);
            }
        });

        // Purge any remaining expired entries (e.g., targets no longer present in
        // queried chunks).
        WitherAugment.purgeExpiredStates(now);
    }
}
