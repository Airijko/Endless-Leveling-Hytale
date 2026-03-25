package com.airijko.endlessleveling.shutdown;

import com.airijko.endlessleveling.EndlessLeveling;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class EndlessLevelingShutdownCoordinator {

    private static final HytaleLogger LOGGER = HytaleLogger.get("EndlessLeveling");
    private static final String SHUTLOG_FILE_NAME = "shutdown.log";
    private static final Query<EntityStore> ENTITY_QUERY = Query.any();

    private final EndlessLeveling plugin;
    private final AtomicBoolean preShutdownCleanupExecuted = new AtomicBoolean(false);

    public EndlessLevelingShutdownCoordinator(EndlessLeveling plugin) {
        this.plugin = plugin;
    }

    public void handlePluginShutdown() {
        alwaysShutdownLog("Starting shutdown cleanup...");
        appendShutlog("shutdown() entered");
        runPreShutdownEntityCleanup("Plugin.shutdown()");

        var mobLevelingSystem = plugin.getMobLevelingSystem();
        var mobLevelingManager = plugin.getMobLevelingManager();
        if (mobLevelingSystem != null) {
            mobLevelingSystem.shutdownRuntimeState();
            alwaysShutdownLog("Mob leveling runtime state cleared.");
            appendShutlog("mob leveling runtime state cleared");
        } else if (mobLevelingManager != null) {
            mobLevelingManager.shutdownRuntimeState();
            alwaysShutdownLog("Mob leveling manager state cleared.");
            appendShutlog("mob leveling manager state cleared");
        }

        var playerDataManager = plugin.getPlayerDataManager();
        if (playerDataManager != null) {
            playerDataManager.saveAll();
            alwaysShutdownLog("All player data saved.");
            appendShutlog("player data saved");
        }

        var partyManager = plugin.getPartyManager();
        if (partyManager != null) {
            partyManager.saveAllParties();
            alwaysShutdownLog("All party data saved.");
            appendShutlog("party data saved");
        }

        appendShutlog("shutdown() completed");
        alwaysShutdownLog("Shutdown complete!");
    }

    public void runPreShutdownEntityCleanup(String source) {
        if (!preShutdownCleanupExecuted.compareAndSet(false, true)) {
            appendShutlog("pre-shutdown cleanup skipped (already executed)");
            return;
        }

        cleanupKnownWorldEntityStores();
        cleanupOnlinePlayerEntityState();
        alwaysShutdownLog("Pre-shutdown cleanup executed from " + (source == null ? "unknown" : source) + ".");
        appendShutlog("pre-shutdown cleanup executed from " + (source == null ? "unknown" : source));
    }

    public void appendShutlog(String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String line = Instant.now() + " | " + message + System.lineSeparator();
        try {
            Path logPath = resolveShutlogPath();
            if (logPath == null) {
                System.err.print("[EL_SHUTLOG] " + line);
                return;
            }

            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.print("[EL_SHUTLOG] write failure: " + ex.getMessage() + " :: " + line);
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanupKnownWorldEntityStores() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        Set<Store<EntityStore>> seenStores = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger visitedStores = new AtomicInteger();
        AtomicInteger clearedPlayerAttributeEntities = new AtomicInteger();
        AtomicInteger clearedPlayerNameplates = new AtomicInteger();
        AtomicInteger clearedMobScaledEntities = new AtomicInteger();

        Map<String, ?> worlds;
        try {
            worlds = universe.getWorlds();
        } catch (Throwable ignored) {
            worlds = null;
        }

        if (worlds != null && !worlds.isEmpty()) {
            for (Object world : worlds.values()) {
                cleanupWorldStore(world,
                        seenStores,
                        visitedStores,
                        clearedPlayerAttributeEntities,
                        clearedPlayerNameplates,
                        clearedMobScaledEntities);
            }
        }

        cleanupWorldStore(universe.getDefaultWorld(),
                seenStores,
                visitedStores,
                clearedPlayerAttributeEntities,
                clearedPlayerNameplates,
                clearedMobScaledEntities);

        if (visitedStores.get() > 0) {
            alwaysShutdownLog(String.format(
                "Store sweep complete: stores=%d, player-modifiers=%d, player-nameplates=%d, mob-scaled=%d.",
                visitedStores.get(),
                clearedPlayerAttributeEntities.get(),
                clearedPlayerNameplates.get(),
                clearedMobScaledEntities.get()));
            appendShutlog(String.format(
                    "store sweep: stores=%d playerModifierEntities=%d playerNameplates=%d mobScaledEntities=%d",
                    visitedStores.get(),
                    clearedPlayerAttributeEntities.get(),
                    clearedPlayerNameplates.get(),
                    clearedMobScaledEntities.get()));
        } else {
            appendShutlog("store sweep: no live stores discovered");
        }
    }

    private void cleanupWorldStore(Object worldObject,
            Set<Store<EntityStore>> seenStores,
            AtomicInteger visitedStores,
            AtomicInteger clearedPlayerAttributeEntities,
            AtomicInteger clearedPlayerNameplates,
            AtomicInteger clearedMobScaledEntities) {
        if (worldObject == null) {
            return;
        }

        Runnable cleanupTask = () -> {
            Store<EntityStore> store = resolveStoreFromWorldObject(worldObject);
            if (store == null || store.isShutdown()) {
                return;
            }

            if (!seenStores.add(store)) {
                return;
            }

            visitedStores.incrementAndGet();
            clearedPlayerAttributeEntities.addAndGet(cleanupPlayerModifiersForStore(store));
            if (plugin.getPlayerNameplateSystem() != null) {
                clearedPlayerNameplates.addAndGet(plugin.getPlayerNameplateSystem().removeAllNameplatesForStore(store));
            }
            if (plugin.getMobLevelingSystem() != null) {
                clearedMobScaledEntities.addAndGet(plugin.getMobLevelingSystem().removeAllNameplatesForStore(store));
            }
        };

        if (!runOnWorldThreadAndWait(worldObject, cleanupTask, 500L)) {
            // Fallback best effort when world execution is unavailable late in shutdown.
            try {
                cleanupTask.run();
            } catch (Throwable ignored) {
                appendShutlog("store sweep fallback cleanup failed for one world");
            }
        }
    }

    private int cleanupPlayerModifiersForStore(Store<EntityStore> store) {
        if (store == null || store.isShutdown() || plugin.getSkillManager() == null) {
            return 0;
        }

        final int[] cleaned = { 0 };
        store.forEachChunk(ENTITY_QUERY, (ArchetypeChunk<EntityStore> chunk,
                CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    continue;
                }

                if (plugin.getSkillManager().removeAllSkillModifiers(ref, commandBuffer)) {
                    cleaned[0]++;
                }
            }
        });
        return cleaned[0];
    }

    @SuppressWarnings("unchecked")
    private Store<EntityStore> resolveStoreFromWorldObject(Object worldObject) {
        if (worldObject == null) {
            return null;
        }

        try {
            Method getEntityStore = worldObject.getClass().getMethod("getEntityStore");
            Object entityStoreObject = getEntityStore.invoke(worldObject);
            if (entityStoreObject instanceof Store) {
                return (Store<EntityStore>) entityStoreObject;
            }

            if (entityStoreObject == null) {
                return null;
            }

            Method getStore = entityStoreObject.getClass().getMethod("getStore");
            Object nestedStoreObject = getStore.invoke(entityStoreObject);
            if (nestedStoreObject instanceof Store) {
                return (Store<EntityStore>) nestedStoreObject;
            }
        } catch (Throwable ignored) {
            return null;
        }

        return null;
    }

    private void cleanupOnlinePlayerEntityState() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        int cleanedPlayers = 0;
        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                continue;
            }

            Store<EntityStore> store = entityRef.getStore();
            if (store == null || store.isShutdown()) {
                continue;
            }

            try {
                Object world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
                Runnable cleanupTask = () -> {
                    try {
                        if (!entityRef.isValid()) {
                            return;
                        }
                        Store<EntityStore> liveStore = entityRef.getStore();
                        if (liveStore == null || liveStore.isShutdown()) {
                            return;
                        }
                        if (plugin.getSkillManager() != null) {
                            plugin.getSkillManager().removeAllSkillModifiers(entityRef, liveStore);
                        }
                        if (plugin.getPlayerNameplateSystem() != null) {
                            plugin.getPlayerNameplateSystem().removeNameplateForPlayerRef(entityRef, liveStore, playerRef);
                        }
                    } catch (Throwable ignored) {
                    }
                };

                if (world != null) {
                    runOnWorldThreadAndWait(world, cleanupTask, 250L);
                } else {
                    cleanupTask.run();
                }
                cleanedPlayers++;
            } catch (Throwable ignored) {
                // Best-effort cleanup only during shutdown.
            }
        }

        if (cleanedPlayers > 0) {
            alwaysShutdownLog(String.format(
                    "Cleaned runtime modifiers/nameplates for %d online player(s).",
                    cleanedPlayers));
        }
    }

    private boolean runOnWorldThreadAndWait(Object worldObject, Runnable task, long timeoutMillis) {
        if (worldObject == null || task == null) {
            return false;
        }

        try {
            if (isCurrentWorldThread(worldObject)) {
                task.run();
                return true;
            }

            Method executeMethod = worldObject.getClass().getMethod("execute", Runnable.class);
            CountDownLatch latch = new CountDownLatch(1);
            executeMethod.invoke(worldObject, (Runnable) () -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });

            if (timeoutMillis <= 0L) {
                latch.await();
                return true;
            }
            boolean completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                appendShutlog("world-thread cleanup timed out after " + timeoutMillis + "ms");
            }
            return completed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCurrentWorldThread(Object worldObject) {
        if (worldObject == null) {
            return false;
        }
        try {
            Method getThreadMethod = worldObject.getClass().getMethod("getThread");
            Object worldThread = getThreadMethod.invoke(worldObject);
            return worldThread == Thread.currentThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void alwaysShutdownLog(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        LOGGER.atInfo().log("[Shutdown] %s", message);
    }

    private Path resolveShutlogPath() {
        if (plugin.getFilesManager() == null || plugin.getFilesManager().getPluginFolder() == null) {
            return null;
        }
        return plugin.getFilesManager().getPluginFolder().toPath().resolve(SHUTLOG_FILE_NAME);
    }
}
