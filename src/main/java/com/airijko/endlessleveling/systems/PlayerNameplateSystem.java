package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.compatibility.NameplateBuilderCompatibility;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Keeps player nameplates in sync with their level ("Lv. X <name>"). */
public class PlayerNameplateSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private static final float UPDATE_INTERVAL_SECONDS = 0.5f;

    private final PlayerDataManager playerDataManager;
    private final Map<UUID, String> lastLabels = new HashMap<>();
    private float elapsedSeconds;

    public PlayerNameplateSystem(@Nonnull PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
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
                String baseName = playerRef.getUsername() != null ? playerRef.getUsername() : "Player";
                PlayerData playerData = playerDataManager.get(uuid);
                if (playerData == null) {
                    playerData = playerDataManager.loadOrCreate(uuid, baseName);
                }
                if (playerData == null) {
                    continue;
                }

                String label = String.format("Lv. %d %s", playerData.getLevel(), baseName);
                String previous = lastLabels.get(uuid);
                if (label.equals(previous)) {
                    continue;
                }

                if (NameplateBuilderCompatibility.isAvailable()
                        && NameplateBuilderCompatibility.registerPlayerLevel(ref.getStore(), ref,
                                playerData.getLevel())) {
                    lastLabels.put(uuid, label);
                    continue;
                }

                Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
                if (nameplate == null) {
                    continue;
                }

                nameplate.setText(label);
                lastLabels.put(uuid, label);
            }
        });

        if (!lastLabels.isEmpty()) {
            lastLabels.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
        }
    }
}