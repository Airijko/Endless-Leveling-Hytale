package com.airijko.endlessleveling.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Tags newly spawned item entities that likely originate from a recent mob kill
 * with the killer UUID in item metadata.
 */
public class MobDropTaggingSystem extends RefSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final LuckDoubleDropSystem luckDoubleDropSystem;

    public MobDropTaggingSystem(@Nonnull LuckDoubleDropSystem luckDoubleDropSystem) {
        this.luckDoubleDropSystem = luckDoubleDropSystem;
    }

    @Override
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ItemComponent itemComponent = store.getComponent(ref, ItemComponent.getComponentType());
        if (itemComponent == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (itemStack == null || ItemStack.isEmpty(itemStack)) {
            return;
        }

        UUID killerUuid = luckDoubleDropSystem.resolveKillerForSpawnedItem(ref, store, commandBuffer);
        if (killerUuid == null) {
            // Any spawned item not linked to a recent mob death is non-mob loot
            // (player drop, chest/container, world systems, etc.) and must not
            // be eligible for mob luck.
            ItemStack sanitized = itemStack.withMetadata(
                    LuckDoubleDropSystem.MOB_KILL_TAG_KEY,
                    LuckDoubleDropSystem.KILL_TAG_CODEC,
                    null);
            itemComponent.setItemStack(sanitized);
            LOGGER.atFine().log(
                    "[MOB_LUCK] MobDropTaggingSystem: non-mob spawned item itemRef=%d item=%s reason=no_nearby_mob_death",
                    ref.getIndex(), itemStack.getItemId());
            return;
        }

        ItemStack tagged = itemStack.withMetadata(
                LuckDoubleDropSystem.MOB_KILL_TAG_KEY,
                LuckDoubleDropSystem.KILL_TAG_CODEC,
                killerUuid.toString());
        itemComponent.setItemStack(tagged);

        // Resolve mob luck at spawn-time only, then strip provenance metadata
        // before players can pick up the stack.
        luckDoubleDropSystem.applyMobLuckToSpawnedDrop(killerUuid, itemComponent);

        LOGGER.atFine().log("[MOB_LUCK] MobDropTaggingSystem: tagged spawned item=%s itemRef=%d killer=%s",
                itemStack.getItemId(), ref.getIndex(), killerUuid);
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return ItemComponent.getComponentType();
    }
}
