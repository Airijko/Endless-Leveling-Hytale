package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.listeners.LuckDoubleDropSystem;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.UUID;

public class BreakBlockEntitySystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final LuckDoubleDropSystem luckSystem;

    public BreakBlockEntitySystem(@Nonnull LuckDoubleDropSystem luckSystem) {
        super(BreakBlockEvent.class);
        this.luckSystem = luckSystem;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
        Ref<EntityStore> initiatorRef = archetypeChunk.getReferenceTo(index);
        PlayerRef player = store.getComponent(initiatorRef, PlayerRef.getComponentType());
        if (player == null)
            return;

        UUID uuid = player.getUuid();
        if (uuid == null)
            return;

        String blockId = event.getBlockType() == null ? null : event.getBlockType().getId();
        if (blockId == null || blockId.isBlank())
            return;

        if (blockId.toUpperCase(Locale.ROOT).contains("ORE")) {
            luckSystem.markRecentOreBreak(uuid);
        }
    }
}
