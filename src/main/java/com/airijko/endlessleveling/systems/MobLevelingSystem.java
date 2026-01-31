package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;

import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Field;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies mob-level stat scaling. For now mobs are hard-coded to level 10.
 * Scales Health and Damage (if a "Damage" stat exists) using the multipliers
 * provided by `LevelingManager`.
 */
public class MobLevelingSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> ENTITY_QUERY = Query.any();
    private final Set<Integer> applied = new HashSet<>();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public MobLevelingSystem() {
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown())
            return;

        LevelingManager levelingManager = EndlessLeveling.getInstance().getLevelingManager();
        if (levelingManager == null || !levelingManager.isMobLevelingEnabled())
            return;

        store.forEachChunk(ENTITY_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        int idx = ref.getIndex();
                        if (applied.contains(idx))
                            continue;

                        // skip players
                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef != null && playerRef.isValid())
                            continue;

                        // check blacklist
                        String mobType = null;
                        var worldGen = commandBuffer.getComponent(ref, WorldGenId.getComponentType());
                        if (worldGen != null) {
                            try {
                                mobType = worldGen.toString();
                            } catch (Throwable ignored) {
                            }
                        }
                        if (mobType != null && levelingManager.isMobTypeBlacklisted(mobType))
                            continue;

                        // If passive mob leveling is disabled, skip entities that are not NPCs
                        if (!levelingManager.allowPassiveMobLeveling()) {
                            Object npcComp = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
                            if (npcComp == null) {
                                continue;
                            }
                        }

                        // Hard-coded level
                        int mobLevel = 100;

                        // Apply health and damage scaling
                        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                        boolean modified = false;
                        if (statMap != null) {
                            if (!levelingManager.isMobHealthScalingEnabled()) {
                                // Health scaling disabled via config; skip and retry later
                                continue;
                            }
                            try {
                                // Health
                                EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
                                if (hp != null) {
                                    double mult = levelingManager.getMobHealthMultiplierForLevel(mobLevel);
                                    float oldMax = hp.getMax();
                                    float newMax = (float) Math.max(1.0, oldMax * mult);
                                    float cur = hp.get();
                                    float newCur;
                                    if (oldMax > 0.0f) {
                                        newCur = (cur / oldMax) * newMax;
                                    } else {
                                        newCur = Math.min(newMax, cur * (float) mult);
                                    }
                                    // Clamp to valid range and avoid zero which can cause odd behavior
                                    newCur = Math.max(0.01f, Math.min(newMax, newCur));
                                    boolean setMaxSucceeded = false;
                                    try {
                                        Field maxField = EntityStatValue.class.getDeclaredField("max");
                                        maxField.setAccessible(true);
                                        maxField.setFloat(hp, newMax);
                                        setMaxSucceeded = true;
                                    } catch (Throwable t) {
                                        LOGGER.atInfo().log(
                                                "MobLeveling: failed to set hp.max via reflection for entity %d: %s",
                                                idx, t.toString());
                                    }
                                    if (setMaxSucceeded) {
                                        // Only update current health if max update succeeded to avoid
                                        // inconsistent state that can lead to invulnerability.
                                        statMap.setStatValue(DefaultEntityStatTypes.getHealth(), newCur);
                                        modified = true;
                                    } else {
                                        LOGGER.atInfo().log(
                                                "MobLeveling: skipping current health update because max update failed for entity %d",
                                                idx);
                                        modified = false;
                                    }
                                }

                                // Damage stat modification removed; damage scaling is handled by
                                // MobDamageScalingSystem at damage-application time. We keep health
                                // scaling here and will retry entities that didn't get modified.
                            } catch (Throwable ignored) {
                                // avoid crashing server on unexpected errors
                            }
                        }

                        if (modified) {
                            applied.add(idx);
                        } else {
                            // don't mark as applied so we will retry on subsequent ticks
                        }
                    }
                });
    }
}
