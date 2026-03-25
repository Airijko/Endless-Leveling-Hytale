package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.airijko.endlessleveling.util.PlayerStoreSelector;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reapplies race-based base stats shortly after a player enters the entity
 * store to ensure
 * attributes such as life force, stamina, and mana override the vanilla
 * defaults even if the
 * first attempt occurred before the stat map was available.
 */
public class PlayerRaceStatSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Query<EntityStore> PLAYER_QUERY = Query.any();
    private static final float RETRY_INTERVAL_SECONDS = 3.0f;
    private static final int MAX_ATTEMPTS = 200;

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final Map<UUID, RetryState> pendingAttempts = new ConcurrentHashMap<>();
    private float elapsedSinceRetryPass;

    public PlayerRaceStatSystem(PlayerDataManager playerDataManager, SkillManager skillManager) {
        this.playerDataManager = playerDataManager;
        this.skillManager = skillManager;
    }

    public void scheduleRetry(UUID uuid) {
        if (uuid == null) {
            return;
        }
        pendingAttempts.compute(uuid, (key, existing) -> {
            if (existing == null) {
                return new RetryState(MAX_ATTEMPTS);
            }
            existing.reset();
            return existing;
        });
    }

    public int shutdownRuntimeState() {
        int cleared = pendingAttempts.size();
        pendingAttempts.clear();
        elapsedSinceRetryPass = 0.0f;
        return cleared;
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown() || playerDataManager == null || skillManager == null) {
            pendingAttempts.clear();
            elapsedSinceRetryPass = 0.0f;
            return;
        }

        if (pendingAttempts.isEmpty()) {
            elapsedSinceRetryPass = 0.0f;
            return;
        }

        elapsedSinceRetryPass += deltaSeconds;
        if (elapsedSinceRetryPass < RETRY_INTERVAL_SECONDS) {
            return;
        }
        elapsedSinceRetryPass = 0.0f;

        Map<Integer, PlayerRef> playersByEntityIndex = PlayerStoreSelector.snapshotPlayersByEntityIndex(store);
        if (playersByEntityIndex.isEmpty()) {
            pendingAttempts.clear();
            return;
        }

        Set<UUID> processed = new HashSet<>();
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
                RetryState state = pendingAttempts.get(uuid);
                if (state == null) {
                    continue;
                }
                processed.add(uuid);

                PlayerData playerData = playerDataManager.get(uuid);
                if (playerData == null) {
                    playerData = playerDataManager.loadOrCreate(uuid, playerRef.getUsername());
                }
                if (playerData == null) {
                    continue;
                }

                EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
                if (hp != null && hp.get() <= 1.0f) {
                    continue;
                }

                boolean applied = false;
                try {
                    applied = skillManager.applyAllSkillModifiers(ref, commandBuffer, playerData);
                } catch (Exception e) {
                    LOGGER.atWarning().log("PlayerRaceStatSystem: failed to apply modifiers for %s: %s",
                            playerRef.getUsername(), e.getMessage());
                }

                if (applied) {
                    pendingAttempts.remove(uuid);
                } else if (state.decrementAndGet() <= 0) {
                    pendingAttempts.remove(uuid);
                    LOGGER.atFine().log("PlayerRaceStatSystem: max retries reached for %s",
                            playerRef.getUsername());
                }
            }
        });

        if (processed.size() != pendingAttempts.size()) {
            pendingAttempts.keySet().removeIf(uuid -> !processed.contains(uuid));
        }
    }

    private static final class RetryState {
        private int attemptsLeft;

        private RetryState(int attempts) {
            this.attemptsLeft = attempts;
        }

        private void reset() {
            this.attemptsLeft = MAX_ATTEMPTS;
        }

        private int decrementAndGet() {
            return --attemptsLeft;
        }
    }
}
