package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.MobLevelingManager;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.WorldGenId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;

/**
 * Applies level-based health scaling exactly once when a mob enters the store
 * by attaching an EntityStat modifier instead of mutating
 * {@link EntityStatValue}
 * through reflection. This mirrors Hytale's own balancing systems.
 */
public class MobHealthModifierSystem extends HolderSystem<EntityStore> {

    private static final Query<EntityStore> QUERY = Query.and(EntityStatMap.getComponentType());
    private static final String HEALTH_MODIFIER_KEY = "ENDLESSLEVELING_HEALTH";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final MobLevelingManager mobLevelingManager = EndlessLeveling.getInstance().getMobLevelingManager();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        if (!isEnabled() || store == null)
            return;

        try {
            if (isPlayer(holder))
                return;

            if (!mobLevelingManager.allowPassiveMobLeveling()
                    && holder.getComponent(NPCEntity.getComponentType()) == null)
                return;

            String mobType = resolveMobType(holder);
            if (mobType != null && mobLevelingManager.isMobTypeBlacklisted(mobType))
                return;

            EntityStatMap statMap = holder.getComponent(EntityStatMap.getComponentType());
            if (statMap == null)
                return;

            Vector3d position = resolvePosition(holder);
            Integer entityIndex = resolveEntityIndex(holder);
            int mobLevel = entityIndex != null
                    ? mobLevelingManager.resolveMobLevel(store, position, entityIndex)
                    : mobLevelingManager.resolveMobLevel(store, position);
            applyHealthScaling(statMap, mobLevel);
        } catch (Throwable t) {
            LOGGER.atWarning().log("MobHealthModifierSystem: failed to scale mob health: %s", t.toString());
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
        // No clean-up needed because modifiers live inside the stat map that will be
        // discarded
        // alongside the entity; method implemented to satisfy HolderSystem contract.
    }

    private boolean isEnabled() {
        return mobLevelingManager != null
                && mobLevelingManager.isMobLevelingEnabled()
                && mobLevelingManager.isMobHealthScalingEnabled();
    }

    private boolean isPlayer(Holder<EntityStore> holder) {
        return holder.getComponent(PlayerRef.getComponentType()) != null;
    }

    private String resolveMobType(Holder<EntityStore> holder) {
        NPCEntity npc = holder.getComponent(NPCEntity.getComponentType());
        if (npc != null) {
            try {
                String npcTypeId = npc.getNPCTypeId();
                if (npcTypeId != null && !npcTypeId.isBlank())
                    return npcTypeId;
            } catch (Throwable ignored) {
            }
        }

        WorldGenId worldGenId = holder.getComponent(WorldGenId.getComponentType());
        if (worldGenId != null) {
            try {
                return Integer.toString(worldGenId.getWorldGenId());
            } catch (Throwable ignored) {
                try {
                    return worldGenId.toString();
                } catch (Throwable ignored2) {
                }
            }
        }
        return null;
    }

    private Vector3d resolvePosition(Holder<EntityStore> holder) {
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        return transform != null ? transform.getPosition() : null;
    }

    private Integer resolveEntityIndex(Holder<EntityStore> holder) {
        if (holder == null) {
            return null;
        }
        try {
            Method getIndex = holder.getClass().getMethod("getIndex");
            Object index = getIndex.invoke(holder);
            if (index instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
        }

        try {
            Method getReference = holder.getClass().getMethod("getReference");
            Object ref = getReference.invoke(holder);
            if (ref != null) {
                Method getIndex = ref.getClass().getMethod("getIndex");
                Object index = getIndex.invoke(ref);
                if (index instanceof Number number) {
                    return number.intValue();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void applyHealthScaling(EntityStatMap statMap, int mobLevel) {
        int healthIndex = DefaultEntityStatTypes.getHealth();
        EntityStatValue currentHealth = statMap.get(healthIndex);
        if (currentHealth == null)
            return;

        float previousMax = currentHealth.getMax();
        float previousValue = currentHealth.get();

        statMap.removeModifier(healthIndex, HEALTH_MODIFIER_KEY);
        EntityStatValue baselineHealth = statMap.get(healthIndex);
        if (baselineHealth == null)
            baselineHealth = currentHealth;

        float baseMax = baselineHealth.getMax();
        double multiplier = mobLevelingManager.getMobHealthMultiplierForLevel(mobLevel);
        float targetMax = (float) Math.max(1.0, baseMax * multiplier);
        float additive = targetMax - baseMax;

        if (Math.abs(additive) > 0.01f) {
            StaticModifier modifier = new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, additive);
            statMap.putModifier(healthIndex, HEALTH_MODIFIER_KEY, modifier);
        }

        float ratio = previousMax > 0.0f ? previousValue / previousMax : 1.0f;
        float newValue = Math.max(0.01f, Math.min(targetMax, ratio * targetMax));
        statMap.setStatValue(healthIndex, newValue);
    }
}
