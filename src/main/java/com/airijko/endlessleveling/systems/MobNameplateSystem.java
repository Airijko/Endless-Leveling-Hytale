package com.airijko.endlessleveling.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.MobLevelingManager;

public class MobNameplateSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> ENTITY_QUERY = Query.any();

    public MobNameplateSystem() {
    }

    // simplified passive detection using NPCEntity component

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return;
        }

        MobLevelingManager levelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
        if (levelingManager == null || !levelingManager.isMobLevelingEnabled()
                || !levelingManager.shouldShowMobLevelUi()) {
            return; // mob leveling disabled globally
        }

        boolean includeLevelInName = levelingManager.shouldIncludeLevelInNameplate();

        store.forEachChunk(ENTITY_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);

                        // Skip players
                        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                        if (playerRef != null && playerRef.isValid()) {
                            continue;
                        }

                        // Resolve mob type (if available) and check blacklist
                        String mobType = resolveMobType(ref, commandBuffer);
                        if (mobType != null && levelingManager.isMobTypeBlacklisted(mobType)) {
                            continue; // explicitly blacklisted
                        }

                        // If passive mob leveling is disabled, skip entities that are not NPCs
                        if (!levelingManager.allowPassiveMobLeveling()) {
                            Object npcComp = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
                            if (npcComp == null) {
                                continue; // treat as passive / non-NPC
                            }
                        }

                        // Resolve base display name if available
                        String baseName = "Mob";
                        DisplayNameComponent display = commandBuffer.getComponent(ref,
                                DisplayNameComponent.getComponentType());
                        if (display != null && display.getDisplayName() != null) {
                            try {
                                baseName = display.getDisplayName().getAnsiMessage();
                            } catch (Throwable ignored) {
                            }
                        }

                        // Ensure a Nameplate component exists and set the hard-coded level label
                        // including HP
                        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
                        if (nameplate != null) {
                            int mobLevel = levelingManager.resolveMobLevel(ref, commandBuffer);
                            StringBuilder label = new StringBuilder();
                            if (includeLevelInName) {
                                label.append("[Lv.").append(mobLevel).append("] ");
                            }
                            label.append(baseName);

                            // Append health if available
                            EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
                            if (statMap != null) {
                                try {
                                    EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
                                    if (hp != null) {
                                        float cur = hp.get();
                                        float max = hp.getMax();
                                        if (Float.isFinite(cur) && Float.isFinite(max) && max > 0) {
                                            int curI = Math.round(cur);
                                            int maxI = Math.round(max);
                                            label.append(" ").append(curI).append("/").append(maxI);
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }

                            nameplate.setText(label.toString());
                        }
                    }
                });
    }

    private String resolveMobType(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = commandBuffer.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            Store<EntityStore> store = ref.getStore();
            if (store != null) {
                npc = store.getComponent(ref, NPCEntity.getComponentType());
            }
        }
        if (npc != null) {
            try {
                String npcTypeId = npc.getNPCTypeId();
                if (npcTypeId != null && !npcTypeId.isBlank()) {
                    return npcTypeId;
                }
            } catch (Throwable ignored) {
            }
        }

        WorldGenId worldGen = commandBuffer.getComponent(ref, WorldGenId.getComponentType());
        if (worldGen == null) {
            Store<EntityStore> store = ref.getStore();
            if (store != null) {
                worldGen = store.getComponent(ref, WorldGenId.getComponentType());
            }
        }
        if (worldGen != null) {
            try {
                return Integer.toString(worldGen.getWorldGenId());
            } catch (Throwable ignored) {
                try {
                    return worldGen.toString();
                } catch (Throwable ignored2) {
                }
            }
        }
        return null;
    }
}
