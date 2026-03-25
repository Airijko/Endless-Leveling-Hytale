package com.airijko.endlessleveling.leveling;

import com.airijko.endlessleveling.passives.type.ArmyOfTheDeadPassive;
import com.airijko.endlessleveling.util.EntityRefUtil;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class XpKillCreditTracker {

    private static final long KILL_CREDIT_WINDOW_MS = 5000L;
    private static final Map<UUID, KillCredit> RECENT_PLAYER_DAMAGE = new ConcurrentHashMap<>();

    private XpKillCreditTracker() {
    }

    public static void recordDamage(Ref<EntityStore> targetRef,
            Ref<EntityStore> attackerRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        UUID targetUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (targetUuid == null) {
            return;
        }

        UUID playerUuid = resolvePlayerCreditUuid(attackerRef, store, commandBuffer);
        if (playerUuid == null) {
            return;
        }

        long now = System.currentTimeMillis();
        RECENT_PLAYER_DAMAGE.put(targetUuid, new KillCredit(playerUuid, now));
        pruneExpired(now);
    }

    public static UUID resolveRecentKiller(Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        UUID targetUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (targetUuid == null) {
            return null;
        }

        KillCredit credit = RECENT_PLAYER_DAMAGE.get(targetUuid);
        if (credit == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (now - credit.lastDamageAtMillis() > KILL_CREDIT_WINDOW_MS) {
            RECENT_PLAYER_DAMAGE.remove(targetUuid);
            return null;
        }
        return credit.playerUuid();
    }

    public static void clearTarget(Ref<EntityStore> targetRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        UUID targetUuid = resolveEntityUuid(targetRef, store, commandBuffer);
        if (targetUuid != null) {
            RECENT_PLAYER_DAMAGE.remove(targetUuid);
        }
    }

    public static int clearAll() {
        int cleared = RECENT_PLAYER_DAMAGE.size();
        RECENT_PLAYER_DAMAGE.clear();
        return cleared;
    }

    private static UUID resolvePlayerCreditUuid(Ref<EntityStore> attackerRef,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(attackerRef)) {
            return null;
        }

        PlayerRef playerRef = EntityRefUtil.tryGetComponent(commandBuffer, attackerRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            playerRef = EntityRefUtil.tryGetComponent(store, attackerRef, PlayerRef.getComponentType());
        }
        if (playerRef != null && playerRef.isValid()) {
            return playerRef.getUuid();
        }

        return ArmyOfTheDeadPassive.getManagedSummonOwnerUuid(attackerRef, store, commandBuffer);
    }

    private static UUID resolveEntityUuid(Ref<EntityStore> ref,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        if (!EntityRefUtil.isUsable(ref)) {
            return null;
        }

        UUIDComponent uuidComponent = EntityRefUtil.tryGetComponent(commandBuffer, ref,
                UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            uuidComponent = EntityRefUtil.tryGetComponent(store, ref, UUIDComponent.getComponentType());
        }
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    private static void pruneExpired(long now) {
        for (Map.Entry<UUID, KillCredit> entry : RECENT_PLAYER_DAMAGE.entrySet()) {
            KillCredit credit = entry.getValue();
            if (credit == null || now - credit.lastDamageAtMillis() > KILL_CREDIT_WINDOW_MS) {
                RECENT_PLAYER_DAMAGE.remove(entry.getKey());
            }
        }
    }

    private record KillCredit(UUID playerUuid, long lastDamageAtMillis) {
    }
}