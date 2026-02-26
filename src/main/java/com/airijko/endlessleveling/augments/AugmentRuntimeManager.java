package com.airijko.endlessleveling.augments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.airijko.endlessleveling.enums.SkillAttributeType;

/**
 * Tracks per-player augment cooldowns and ready-notification state.
 */
public final class AugmentRuntimeManager {

    private final Map<UUID, AugmentRuntimeState> runtimeStates = new ConcurrentHashMap<>();

    public AugmentRuntimeState getRuntimeState(UUID uuid) {
        return runtimeStates.computeIfAbsent(uuid, AugmentRuntimeState::new);
    }

    public void clear(UUID uuid) {
        runtimeStates.remove(uuid);
    }

    public void clearAll() {
        runtimeStates.clear();
    }

    public void markCooldown(UUID uuid, String augmentId, long cooldownMillis) {
        markCooldown(uuid, augmentId, augmentId, cooldownMillis);
    }

    public void markCooldown(UUID uuid, String augmentId, String displayName, long cooldownMillis) {
        if (uuid == null || augmentId == null || augmentId.isBlank() || cooldownMillis <= 0) {
            return;
        }
        AugmentRuntimeState state = getRuntimeState(uuid);
        state.setCooldown(augmentId, displayName, System.currentTimeMillis() + cooldownMillis);
    }

    public static final class AugmentRuntimeState {
        private final UUID playerId;
        private final Map<String, CooldownState> cooldowns = new ConcurrentHashMap<>();
        private final Map<String, AugmentState> states = new ConcurrentHashMap<>();
        private final Map<SkillAttributeType, Map<String, AttributeBonus>> attributeBonuses = new ConcurrentHashMap<>();

        private AugmentRuntimeState(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public Collection<CooldownState> getCooldowns() {
            return Collections.unmodifiableCollection(new ArrayList<>(cooldowns.values()));
        }

        public void setCooldown(String augmentId, String displayName, long expiresAtMillis) {
            if (augmentId == null || augmentId.isBlank()) {
                return;
            }
            CooldownState state = cooldowns.computeIfAbsent(normalizeId(augmentId), CooldownState::new);
            state.setDisplayName(displayName);
            state.setExpiresAt(expiresAtMillis);
            state.setReadyNotified(false);
        }

        public CooldownState getCooldown(String augmentId) {
            if (augmentId == null) {
                return null;
            }
            return cooldowns.get(normalizeId(augmentId));
        }

        public AugmentState getState(String augmentId) {
            if (augmentId == null) {
                return null;
            }
            return states.computeIfAbsent(normalizeId(augmentId), AugmentState::new);
        }

        public void clearCooldown(String augmentId) {
            if (augmentId == null) {
                return;
            }
            cooldowns.remove(normalizeId(augmentId));
        }

        public void clearState(String augmentId) {
            if (augmentId == null) {
                return;
            }
            states.remove(normalizeId(augmentId));
        }

        public void clearAll() {
            cooldowns.clear();
            states.clear();
            attributeBonuses.clear();
        }

        public void setAttributeBonus(SkillAttributeType type, String sourceId, double value, long expiresAtMillis) {
            if (type == null || sourceId == null) {
                return;
            }
            attributeBonuses
                    .computeIfAbsent(type, t -> new ConcurrentHashMap<>())
                    .put(normalizeId(sourceId), new AttributeBonus(value, expiresAtMillis));
        }

        public double getAttributeBonus(SkillAttributeType type, long now) {
            if (type == null) {
                return 0.0D;
            }
            Map<String, AttributeBonus> bonuses = attributeBonuses.get(type);
            if (bonuses == null || bonuses.isEmpty()) {
                return 0.0D;
            }
            double total = 0.0D;
            bonuses.entrySet().removeIf(entry -> entry.getValue() != null && entry.getValue().isExpired(now));
            for (AttributeBonus bonus : bonuses.values()) {
                if (bonus == null) {
                    continue;
                }
                if (bonus.expiresAt <= 0L || now <= bonus.expiresAt) {
                    total += bonus.value;
                }
            }
            return total;
        }

        private String normalizeId(String augmentId) {
            return augmentId == null ? null : augmentId.trim().toLowerCase();
        }
    }

    public record AttributeBonus(double value, long expiresAt) {
        boolean isExpired(long now) {
            return expiresAt > 0L && now > expiresAt;
        }
    }

    public static final class CooldownState {
        private final String augmentId;
        private String displayName;
        private long expiresAt;
        private boolean readyNotified = true;

        private CooldownState(String augmentId) {
            this.augmentId = augmentId;
            this.displayName = augmentId;
        }

        public String getAugmentId() {
            return augmentId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            if (displayName != null && !displayName.isBlank()) {
                this.displayName = displayName;
            }
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public boolean isReadyNotified() {
            return readyNotified;
        }

        public void setReadyNotified(boolean readyNotified) {
            this.readyNotified = readyNotified;
        }
    }

    public static final class AugmentState {
        private final String augmentId;
        private int stacks;
        private double storedValue;
        private long expiresAt;
        private long lastProc;

        private AugmentState(String augmentId) {
            this.augmentId = augmentId;
        }

        public String getAugmentId() {
            return augmentId;
        }

        public int getStacks() {
            return stacks;
        }

        public void setStacks(int stacks) {
            this.stacks = Math.max(0, stacks);
        }

        public double getStoredValue() {
            return storedValue;
        }

        public void setStoredValue(double storedValue) {
            this.storedValue = storedValue;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        public long getLastProc() {
            return lastProc;
        }

        public void setLastProc(long lastProc) {
            this.lastProc = lastProc;
        }

        public boolean isExpired(long now) {
            return expiresAt > 0L && now > expiresAt;
        }

        public void clear() {
            this.stacks = 0;
            this.storedValue = 0.0D;
            this.expiresAt = 0L;
            this.lastProc = 0L;
        }
    }
}
