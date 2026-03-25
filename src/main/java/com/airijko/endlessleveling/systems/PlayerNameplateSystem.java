package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.util.PlayerStoreSelector;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** Keeps player nameplates in sync with EndlessLeveling NameplateBuilder segments. */
public class PlayerNameplateSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private static final float UPDATE_INTERVAL_SECONDS = 1.0f;

    private final PlayerDataManager playerDataManager;
    private final Map<UUID, String> lastLabels = new ConcurrentHashMap<>();
    private float elapsedSeconds;

    public PlayerNameplateSystem(@Nonnull PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    public int removeAllNameplatesForStore(Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return 0;
        }

        Map<Integer, PlayerRef> playersByEntityIndex = PlayerStoreSelector.snapshotPlayersByEntityIndex(store);
        if (playersByEntityIndex.isEmpty()) {
            lastLabels.clear();
            return 0;
        }

        final int[] removed = { 0 };
        store.forEachChunk(PLAYER_QUERY, (ArchetypeChunk<EntityStore> chunk,
                CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = playersByEntityIndex.get(ref.getIndex());
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                removeNameplateForPlayerRef(ref, commandBuffer, playerRef);
                removed[0]++;
            }
        });

        if (!lastLabels.isEmpty()) {
            lastLabels.clear();
        }
        return removed[0];
    }

    public void removeNameplateForPlayerRef(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor,
            @Nonnull PlayerRef playerRef) {
        if (NameplateBuilderCompatibility.isAvailable()) {
            NameplateBuilderCompatibility.removePlayerLevel(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeELPlayerPrestigeLevel(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeELPlayerRace(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeELPlayerClassPrimary(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeELPlayerClassSecondary(ref.getStore(), ref);
            NameplateBuilderCompatibility.removeELPlayerName(ref.getStore(), ref);
        }

        Nameplate nameplate = componentAccessor.getComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            String baseName = playerRef.getUsername() != null ? playerRef.getUsername() : "Player";
            nameplate.setText(baseName);
        }
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown() || playerDataManager == null) {
            return;
        }

        elapsedSeconds += deltaSeconds;
        if (elapsedSeconds < UPDATE_INTERVAL_SECONDS) {
            return;
        }
        elapsedSeconds = 0.0f;
        Map<Integer, PlayerRef> playersByEntityIndex = PlayerStoreSelector.snapshotPlayersByEntityIndex(store);
        if (playersByEntityIndex.isEmpty()) {
            if (!lastLabels.isEmpty()) {
                lastLabels.clear();
            }
            return;
        }

        Set<UUID> onlinePlayers = new HashSet<>();

        store.forEachChunk(PLAYER_QUERY, (ArchetypeChunk<EntityStore> chunk,
                CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = playersByEntityIndex.get(ref.getIndex());
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                UUID uuid = playerRef.getUuid();
                if (uuid == null) {
                    continue;
                }
                onlinePlayers.add(uuid);
                String baseName = playerRef.getUsername() != null ? playerRef.getUsername() : "Player";
                PlayerData playerData = playerDataManager.get(uuid);
                if (playerData == null) {
                    playerData = playerDataManager.loadOrCreate(uuid, baseName);
                }
                if (playerData == null) {
                    continue;
                }

                String race = normalizePlayerSegmentValue(playerData.getRaceId(), "None");
                String classPrimary = normalizePlayerSegmentValue(playerData.getPrimaryClassId(), "None");
                String classSecondary = normalizePlayerSegmentValue(playerData.getSecondaryClassId(), "None");

                String signature = String.join("|",
                    Integer.toString(playerData.getLevel()),
                    Integer.toString(Math.max(0, playerData.getPrestigeLevel())),
                    race,
                    classPrimary,
                    classSecondary,
                    baseName);
                String previous = lastLabels.get(uuid);
                if (signature.equals(previous)) {
                    continue;
                }

                if (NameplateBuilderCompatibility.isAvailable()) {
                    boolean registeredLevel = NameplateBuilderCompatibility.registerPlayerLevel(
                        ref.getStore(), ref, playerData.getLevel());
                    boolean registeredPrestige = NameplateBuilderCompatibility.registerELPlayerPrestigeLevel(
                        ref.getStore(), ref, Math.max(0, playerData.getPrestigeLevel()));
                    boolean registeredRace = NameplateBuilderCompatibility.registerELPlayerRace(
                        ref.getStore(), ref, race);
                    boolean registeredPrimary = NameplateBuilderCompatibility.registerELPlayerClassPrimary(
                        ref.getStore(), ref, classPrimary);
                    boolean registeredSecondary = NameplateBuilderCompatibility.registerELPlayerClassSecondary(
                        ref.getStore(), ref, classSecondary);
                    boolean registeredName = NameplateBuilderCompatibility.registerELPlayerName(
                        ref.getStore(), ref, baseName);

                    if (registeredLevel && registeredPrestige && registeredRace
                        && registeredPrimary && registeredSecondary && registeredName) {
                    lastLabels.put(uuid, signature);
                    continue;
                    }
                }

                String label = String.format("Lv. %d %s", playerData.getLevel(), baseName);
                Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
                if (nameplate == null) {
                    continue;
                }

                nameplate.setText(label);
                lastLabels.put(uuid, signature);
            }
        });

        if (!lastLabels.isEmpty()) {
            lastLabels.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
        }
    }

    private static String normalizePlayerSegmentValue(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}