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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier.ModifierTarget;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier.CalculationType;
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
    private static final String MOB_HEALTH_SCALE_MODIFIER_KEY = "EL_MOB_HEALTH_SCALE";
    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();
    private final Map<Integer, Integer> healthAppliedLevel = new ConcurrentHashMap<>();
    private final Set<Integer> trackedEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> deathHandledEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> forcedDeathLoggedEntityIds = ConcurrentHashMap.newKeySet();
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

                        ensureDeadComponentWhenZeroHp(ref, commandBuffer);

                        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
                            if (deathHandledEntityIds.add(entityId)) {
                                handleDeadEntity(ref, commandBuffer);
                                healthAppliedLevel.remove(entityId);
                                forcedDeathLoggedEntityIds.remove(entityId);
                                mobLevelingManager.forgetEntity(entityId);
                            }
                            continue;
                        }

                        Integer previouslyAppliedLevel = healthAppliedLevel.get(entityId);
                        Integer appliedLevel = previouslyAppliedLevel;
                        if (appliedLevel == null || appliedLevel <= 0) {
                            appliedLevel = mobLevelingManager.resolveMobLevelForEntity(ref, store, commandBuffer);
                        }
                        if (appliedLevel == null || appliedLevel <= 0) {
                            if (isAtOrBelowZeroHealth(ref, commandBuffer)) {
                                clearOrRemoveNameplate(ref, commandBuffer);
                                healthAppliedLevel.remove(entityId);
                            }
                            continue;
                        }

                        applyHealthModifier(ref, commandBuffer, appliedLevel);
                        if (showMobLevelUi) {
                            applyNameplate(ref, commandBuffer, includeLevelInName);
                        }
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
            deathHandledEntityIds.remove(entityId);
            forcedDeathLoggedEntityIds.remove(entityId);
            mobLevelingManager.forgetEntity(entityId);
        }
    }

    private void ensureDeadComponentWhenZeroHp(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            forcedDeathLoggedEntityIds.remove(ref.getIndex());
            return;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp == null || !Float.isFinite(hp.get()) || hp.get() > 0.0001f) {
            forcedDeathLoggedEntityIds.remove(ref.getIndex());
            return;
        }

        DeathComponent.tryAddComponent(
                commandBuffer,
                ref,
                new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, 0.0f));

        int entityId = ref.getIndex();
        if (forcedDeathLoggedEntityIds.add(entityId)) {
            LOGGER.atWarning().log(
                    "ForcedDeathComponent target=%d hp=%.3f max=%.3f",
                    entityId,
                    hp.get(),
                    hp.getMax());
        }
    }

    private void handleDeadEntity(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        int entityId = ref.getIndex();
        Integer appliedLevel = healthAppliedLevel.get(entityId);
        float hpBefore = Float.NaN;
        float maxBefore = Float.NaN;

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIndex = DefaultEntityStatTypes.getHealth();
            EntityStatValue before = statMap.get(healthIndex);
            if (before != null) {
                hpBefore = before.get();
                maxBefore = before.getMax();
            }
        }

        LOGGER.atInfo().log(
                "MobOnDeath triggered: entity=%d level=%s hpBefore=%.3f maxBefore=%.3f",
                entityId,
                appliedLevel != null ? appliedLevel.toString() : "unknown",
                hpBefore,
                maxBefore);

        clearOrRemoveNameplate(ref, commandBuffer);
    }

    private boolean isAtOrBelowZeroHealth(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return false;
        }

        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return false;
        }

        EntityStatValue hp = statMap.get(DefaultEntityStatTypes.getHealth());
        return hp != null && hp.get() <= 0.0001f;
    }

    private void applyHealthModifier(Ref<EntityStore> ref,
            CommandBuffer<EntityStore> commandBuffer,
            int appliedLevel) {
        if (ref == null || commandBuffer == null || mobLevelingManager == null) {
            return;
        }

        int entityId = ref.getIndex();
        EntityStatMap statMap = commandBuffer.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        EntityStatValue healthStat = statMap.get(DefaultEntityStatTypes.getHealth());
        if (healthStat == null) {
            return;
        }

        int healthIndex = DefaultEntityStatTypes.getHealth();

        float currentValue = healthStat.get();
        float currentMax = healthStat.getMax();
        if (!Float.isFinite(currentValue) || !Float.isFinite(currentMax) || currentMax <= 0.0f) {
            healthAppliedLevel.put(entityId, appliedLevel);
            return;
        }

        statMap.removeModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY);
        EntityStatValue baselineHealth = statMap.get(healthIndex);
        float baseMax = baselineHealth != null ? baselineHealth.getMax() : currentMax;
        if (!Float.isFinite(baseMax) || baseMax <= 0.0f) {
            baseMax = Math.max(1.0f, currentMax);
        }

        if (!mobLevelingManager.isMobHealthScalingEnabled()) {
            float ratio = currentMax > 0.0f ? currentValue / currentMax : 1.0f;
            float restoredValue = Math.max(0.0f, Math.min(baseMax, ratio * baseMax));
            if (currentValue <= 0.0f) {
                restoredValue = 0.0f;
            }
            statMap.setStatValue(healthIndex, restoredValue);
            statMap.update();
            healthAppliedLevel.put(entityId, appliedLevel);
            return;
        }

        MobLevelingManager.MobHealthScalingResult scaled = mobLevelingManager.computeMobHealthScaling(
                appliedLevel,
                baseMax,
                currentMax,
                currentValue);

        float targetMax = scaled.targetMax();
        float targetValue = scaled.newValue();
        if (!Float.isFinite(targetMax) || targetMax <= 0.0f || !Float.isFinite(targetValue)) {
            healthAppliedLevel.put(entityId, appliedLevel);
            return;
        }

        float additive = scaled.additive();
        if (Math.abs(additive) > 0.0001f) {
            try {
                StaticModifier modifier = new StaticModifier(ModifierTarget.MAX, CalculationType.ADDITIVE, additive);
                statMap.putModifier(healthIndex, MOB_HEALTH_SCALE_MODIFIER_KEY, modifier);
            } catch (Exception e) {
                LOGGER.atWarning().log(
                        "MobHealthScaling modifier apply failed for entity=%d level=%d: %s",
                        entityId,
                        appliedLevel,
                        e.toString());
            }
        }

        statMap.setStatValue(healthIndex, targetValue);
        statMap.update();

        healthAppliedLevel.put(entityId, appliedLevel);

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

        if (commandBuffer.getComponent(ref, DeathComponent.getComponentType()) != null) {
            clearOrRemoveNameplate(ref, commandBuffer);
            return;
        }

        Integer appliedLevel = healthAppliedLevel.get(ref.getIndex());
        if (appliedLevel == null || appliedLevel <= 0) {
            return;
        }

        Nameplate nameplate = commandBuffer.ensureAndGetComponent(ref, Nameplate.getComponentType());
        if (nameplate == null) {
            return;
        }

        String baseName = "Mob";
        DisplayNameComponent display = commandBuffer.getComponent(ref, DisplayNameComponent.getComponentType());
        if (display != null && display.getDisplayName() != null
                && display.getDisplayName().getAnsiMessage() != null
                && !display.getDisplayName().getAnsiMessage().isBlank()) {
            baseName = display.getDisplayName().getAnsiMessage();
        }

        String label;
        if (includeLevelInName) {
            label = "[Lv." + appliedLevel + "] " + baseName;
        } else {
            label = baseName;
        }

        nameplate.setText(label);
    }

    private void clearOrRemoveNameplate(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer) {
        if (ref == null || commandBuffer == null) {
            return;
        }

        Nameplate nameplate = commandBuffer.getComponent(ref, Nameplate.getComponentType());
        if (nameplate != null) {
            nameplate.setText("");
        }
    }
}
