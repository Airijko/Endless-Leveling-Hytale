package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies mob-level stat scaling. For now mobs are hard-coded to level 10.
 * Scales Health and Damage (if a "Damage" stat exists) using the multipliers
 * provided by `LevelingManager`.
 */
public class MobLevelingSystem extends TickingSystem<EntityStore> {

    private static final Query<EntityStore> ENTITY_QUERY = Query.any();
    private static final String HEALTH_MODIFIER_KEY = "ENDLESSLEVELING_HEALTH";
    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
    private final Map<Integer, Integer> healthAppliedLevel = new ConcurrentHashMap<>();
    private final Set<Integer> trackedEntityIds = ConcurrentHashMap.newKeySet();
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public MobLevelingSystem() {
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown())
            return;

        if (mobLevelingManager == null || !mobLevelingManager.isMobLevelingEnabled())
            return;

        boolean showMobLevelUi = mobLevelingManager.shouldShowMobLevelUi();
        boolean includeLevelInName = mobLevelingManager.shouldIncludeLevelInNameplate();
        Set<Integer> seenEntityIds = new HashSet<>();

        store.forEachChunk(ENTITY_QUERY,
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        int entityId = ref.getIndex();
                        seenEntityIds.add(entityId);
                        trackedEntityIds.add(entityId);

                        boolean levelAppliedNow = mobLevelingManager.applyLeveling(ref, store, commandBuffer,
                                tickCount);
                        applyHealthModifier(ref, commandBuffer, levelAppliedNow, showMobLevelUi, includeLevelInName);
                    }
                });

        pruneStaleEntities(seenEntityIds);
    }

    private void pruneStaleEntities(Set<Integer> seenEntityIds) {
        if (seenEntityIds == null) {
            return;
        }

        for (Integer entityId : trackedEntityIds) {
            if (entityId == null || seenEntityIds.contains(entityId)) {
                continue;
            }

            trackedEntityIds.remove(entityId);
            healthAppliedLevel.remove(entityId);
            mobLevelingManager.forgetEntity(entityId);
        }
    }

    private void applyHealthModifier(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            boolean levelAppliedNow,
            boolean showMobLevelUi,
            boolean includeLevelInName) {
        if (ref == null || commandBuffer == null || mobLevelingManager == null) {
            return;
        }
        if (!mobLevelingManager.isMobHealthScalingEnabled()) {
            if (showMobLevelUi) {
                applyNameplate(ref, commandBuffer, includeLevelInName);
            }
            return;
        }

        Integer appliedLevel = mobLevelingManager.getAppliedMobLevel(ref);
        if (appliedLevel == null || appliedLevel <= 0) {
            return;
        }

        int entityId = ref.getIndex();
        Integer previousLevel = healthAppliedLevel.get(entityId);
        if (!levelAppliedNow && previousLevel != null && previousLevel == appliedLevel) {
            if (showMobLevelUi) {
                applyNameplate(ref, commandBuffer, includeLevelInName);
            }
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();
        EntityStatValue currentHealth = statMap.get(healthIndex);
        if (currentHealth == null) {
            return;
        }

        float previousMax = currentHealth.getMax();
        float previousValue = currentHealth.get();

        statMap.removeModifier(healthIndex, HEALTH_MODIFIER_KEY);
        EntityStatValue baselineHealth = statMap.get(healthIndex);
        if (baselineHealth == null) {
            baselineHealth = currentHealth;
        }

        float baseMax = baselineHealth.getMax();
        double multiplier = mobLevelingManager.getMobHealthMultiplierForLevel(appliedLevel);
        float targetMax = (float) Math.max(1.0, baseMax * multiplier);
        float additive = targetMax - baseMax;

        if (Math.abs(additive) > 0.01f) {
            StaticModifier modifier = new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, additive);
            statMap.putModifier(healthIndex, HEALTH_MODIFIER_KEY, modifier);
        }

        float ratio = previousMax > 0.0f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.0f, Math.min(targetMax, ratio * targetMax));
        if (previousValue <= 0.0f) {
            newValue = 0.0f;
        }
        statMap.setStatValue(healthIndex, newValue);
        healthAppliedLevel.put(entityId, appliedLevel);

        if (showMobLevelUi) {
            applyNameplate(ref, commandBuffer, includeLevelInName);
        }

        if (previousValue <= 0.0f && newValue > 0.0f) {
            LOGGER.atWarning().log(
                    "MobHealth anomaly: entity=%d revived during modifier apply (prevHp=%.3f newHp=%.3f level=%d)",
                    entityId,
                    previousValue,
                    newValue,
                    appliedLevel);
        }
    }

    private void applyNameplate(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            boolean includeLevelInName) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef != null && playerRef.isValid()) {
            return;
        }

        Integer appliedLevel = mobLevelingManager.getAppliedMobLevel(ref);
        if (appliedLevel == null || appliedLevel <= 0) {
            return;
        }

        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (nameplate == null) {
            return;
        }

        String baseName = "Mob";
        DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
        if (display != null && display.getDisplayName() != null) {
            try {
                baseName = display.getDisplayName().getAnsiMessage();
            } catch (Throwable ignored) {
            }
        }

        StringBuilder label = new StringBuilder();
        if (includeLevelInName) {
            label.append("[Lv.").append(appliedLevel).append("] ");
        }
        label.append(baseName);

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            try {
                EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
                if (hp != null) {
                    float cur = hp.get();
                    float max = hp.getMax();
                    if (Float.isFinite(cur) && Float.isFinite(max) && max > 0.0f) {
                        label.append(" ").append(Math.round(cur)).append("/").append(Math.round(max));
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        nameplate.setText(label.toString());
    }
}
