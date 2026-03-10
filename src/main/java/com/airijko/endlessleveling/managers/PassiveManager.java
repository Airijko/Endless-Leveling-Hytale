package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveManager;
import com.airijko.endlessleveling.systems.PassiveRegenSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player passives that scale automatically with player level.
 */
public class PassiveManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final long LUCK_MOB_DROP_WINDOW_MS = 3500L;
    private static final int LUCK_MOB_DROP_STACK_BUDGET = 6;

    private final ConfigManager configManager;
    private final Map<PassiveType, PassiveDefinition> definitions = new EnumMap<>(PassiveType.class);
    private final Map<UUID, PassiveRuntimeState> runtimeStates = new ConcurrentHashMap<>();

    public PassiveManager(@Nonnull ConfigManager configManager) {
        this.configManager = configManager;
        reload();
    }

    public void reload() {
        definitions.clear();
        for (PassiveType type : PassiveType.values()) {
            definitions.put(type, loadDefinition(type));
        }
        LOGGER.atInfo().log("Loaded %d passive definitions", definitions.size());
    }

    public void markCombat(@Nonnull UUID uuid) {
        PassiveRuntimeState state = runtimeStates.computeIfAbsent(uuid, PassiveRuntimeState::new);
        state.setLastCombatMillis(System.currentTimeMillis());
        state.setRegenerationActive(false);
    }

    public void openMobDropWindow(@Nonnull UUID uuid) {
        PassiveRuntimeState state = runtimeStates.computeIfAbsent(uuid, PassiveRuntimeState::new);
        long now = System.currentTimeMillis();
        state.setLastMobKillMillis(now);
        state.setLuckMobDropWindowExpiresAt(now + LUCK_MOB_DROP_WINDOW_MS);
        state.setLuckMobDropStacks(Math.max(state.getLuckMobDropStacks(), LUCK_MOB_DROP_STACK_BUDGET));
    }

    public boolean hasRecentMobKill(@Nonnull UUID uuid) {
        PassiveRuntimeState state = runtimeStates.get(uuid);
        if (state == null) {
            return false;
        }
        long lastKill = state.getLastMobKillMillis();
        if (lastKill <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastKill <= LUCK_MOB_DROP_WINDOW_MS;
    }

    public boolean hasMobDropStack(@Nonnull UUID uuid) {
        PassiveRuntimeState state = runtimeStates.get(uuid);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.getLuckMobDropWindowExpiresAt() <= now) {
            state.setLuckMobDropStacks(0);
            return false;
        }
        return state.getLuckMobDropStacks() > 0;
    }

    public boolean consumeMobDropStack(@Nonnull UUID uuid) {
        PassiveRuntimeState state = runtimeStates.get(uuid);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.getLuckMobDropWindowExpiresAt() <= now) {
            state.setLuckMobDropStacks(0);
            return false;
        }
        int remaining = state.getLuckMobDropStacks();
        if (remaining <= 0) {
            return false;
        }
        state.setLuckMobDropStacks(remaining - 1);
        return true;
    }

    public boolean isOutOfCombat(@Nonnull UUID uuid, long cooldownMillis) {
        PassiveRuntimeState state = runtimeStates.get(uuid);
        if (state == null) {
            return true;
        }
        long lastCombat = state.getLastCombatMillis();
        if (lastCombat <= 0L) {
            return true;
        }
        return (System.currentTimeMillis() - lastCombat) >= cooldownMillis;
    }

    public PassiveRuntimeState getRuntimeState(@Nonnull UUID uuid) {
        return runtimeStates.computeIfAbsent(uuid, PassiveRuntimeState::new);
    }

    public void resetRuntimeState(@Nonnull UUID uuid) {
        runtimeStates.remove(uuid);
    }

    public int resetAllPassiveCooldowns() {
        int affected = 0;
        for (PassiveRuntimeState state : runtimeStates.values()) {
            if (state == null) {
                continue;
            }
            state.clearPassiveCooldowns();
            affected++;
        }
        return affected;
    }

    private PassiveDefinition loadDefinition(PassiveType type) {
        String basePath = "passives." + type.getConfigKey();
        boolean enabled = toBoolean(configManager.get(basePath + ".enabled", Boolean.TRUE, false), true);
        int unlockLevel = toInt(configManager.get(basePath + ".unlock_level", 1, false), 1);
        double baseValue = toDouble(configManager.get(basePath + ".base_value", 0.0, false), 0.0);
        double valuePerLevel = toDouble(configManager.get(basePath + ".value_per_level", 0.0, false), 0.0);
        int maxLevel = Math.max(1, toInt(configManager.get(basePath + ".max_level", 1, false), 1));
        int interval = Math.max(1, toInt(configManager.get(basePath + ".tier_interval", 5, false), 5));
        return new PassiveDefinition(type, enabled, unlockLevel, baseValue, valuePerLevel, maxLevel, interval);
    }

    public PassiveSyncResult syncPassives(@Nonnull PlayerData playerData) {
        Map<PassiveType, PassiveSnapshot> snapshots = new EnumMap<>(PassiveType.class);
        List<PassiveType> leveled = new ArrayList<>();

        for (PassiveType type : PassiveType.values()) {
            PassiveDefinition definition = definitions.getOrDefault(type, PassiveDefinition.disabled(type));
            int computedLevel = computePassiveLevel(definition, playerData.getLevel());
            int previousLevel = playerData.getPassiveLevel(type);
            playerData.setPassiveLevel(type, computedLevel);

            double value = computePassiveValue(definition, computedLevel);
            snapshots.put(type, new PassiveSnapshot(definition, computedLevel, value));

            if (computedLevel > previousLevel) {
                leveled.add(type);
            }
        }

        return new PassiveSyncResult(Collections.unmodifiableMap(snapshots), Collections.unmodifiableList(leveled));
    }

    public void notifyPassiveChanges(@Nonnull PlayerData playerData, @Nonnull PassiveSyncResult result) {
        if (result.leveledUp().isEmpty()) {
            return;
        }
        if (!playerData.isPassiveLevelUpNotifEnabled()) {
            return;
        }

        PlayerRef ref = Universe.get().getPlayer(playerData.getUuid());
        if (ref == null) {
            return;
        }
        for (PassiveType type : result.leveledUp()) {
            PassiveSnapshot snapshot = result.snapshots().get(type);
            if (snapshot == null || snapshot.level() <= 0) {
                continue;
            }
            String formattedValue = type.formatValue(snapshot.value());
            Message message = Message.join(
                    Message.raw("[Passive] ").color("#4fd7f7"),
                    Message.raw(type.getDisplayName()).color("#ffc300"),
                    Message.raw(" is now Level ").color("#ffffff"),
                    Message.raw(String.valueOf(snapshot.level())).color("#4fd7f7"),
                    Message.raw(" (" + formattedValue + ")").color("#9be7ff"));
            ref.sendMessage(message);

        }
    }

    public void notifyLuckDoubleDrop(@Nonnull PlayerData playerData,
            @Nonnull String dropDisplayName,
            int baseAmount,
            int bonusAmount) {
        if (!playerData.isLuckDoubleDropsNotifEnabled() || bonusAmount <= 0) {
            return;
        }

        PlayerRef ref = Universe.get().getPlayer(playerData.getUuid());
        if (ref == null) {
            return;
        }

        String dropName = dropDisplayName.isBlank() ? "your loot" : dropDisplayName;
        int safeBase = Math.max(0, baseAmount);
        int totalAmount = safeBase + bonusAmount;

        Message chatMessage = Message.join(
                Message.raw("[Luck] ").color("#4fd7f7"),
                Message.raw("Double drop! ").color("#ffffff"),
                Message.raw(dropName).color("#ffc300"),
                Message.raw(" +" + bonusAmount + " (total x" + totalAmount + ")").color("#9be7ff"));
        ref.sendMessage(chatMessage);

        LOGGER.atFine().log("Luck double-drop triggered for %s: %s +%d",
                playerData.getPlayerName(), dropName, bonusAmount);
    }

    public PassiveSnapshot getSnapshot(@Nonnull PlayerData playerData, @Nonnull PassiveType type) {
        PassiveDefinition definition = definitions.getOrDefault(type, PassiveDefinition.disabled(type));
        int level = computePassiveLevel(definition, playerData.getLevel());
        double value = computePassiveValue(definition, level);
        return new PassiveSnapshot(definition, level, value);
    }

    /**
     * Returns total luck percent from innate passives, archetype passives, and
     * selected augment luck bonuses.
     */
    public double getLuckValue(@Nonnull PlayerData playerData) {
        PassiveSnapshot snapshot = getSnapshot(playerData, PassiveType.LUCK);
        double innateLuck = snapshot != null && snapshot.isUnlocked() ? snapshot.value() : 0.0D;
        double archetypeLuck = resolveArchetypeLuck(playerData);
        double augmentLuck = resolveSelectedAugmentLuck(playerData);
        double totalLuck = Math.max(0.0D, innateLuck + archetypeLuck + augmentLuck);
        LOGGER.atFiner().log("Computed luck value for %s: %f",
                playerData.getPlayerName(), totalLuck);
        return totalLuck;
    }

    private double resolveArchetypeLuck(@Nonnull PlayerData playerData) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        ArchetypePassiveManager archetypeManager = plugin != null ? plugin.getArchetypePassiveManager() : null;
        if (archetypeManager == null) {
            return 0.0D;
        }
        return Math.max(0.0D, archetypeManager.getSnapshot(playerData).getValue(ArchetypePassiveType.LUCK));
    }

    private double resolveSelectedAugmentLuck(@Nonnull PlayerData playerData) {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getAugmentManager() == null) {
            return 0.0D;
        }
        Map<String, String> selectedAugments = playerData.getSelectedAugmentsSnapshot();
        if (selectedAugments.isEmpty()) {
            return 0.0D;
        }

        double total = 0.0D;
        for (String augmentId : selectedAugments.values()) {
            if (augmentId == null || augmentId.isBlank()) {
                continue;
            }
            AugmentDefinition definition = plugin.getAugmentManager().getAugment(augmentId.trim().toLowerCase());
            if (definition == null) {
                continue;
            }
            Map<String, Object> passives = definition.getPassives();
            Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
            double luckValue = AugmentValueReader.getNestedDouble(buffs, 0.0D, "luck", "value");
            if (luckValue > 0.0D) {
                total += luckValue * 100.0D;
            }
        }
        return Math.max(0.0D, total);
    }

    private int computePassiveLevel(PassiveDefinition definition, int playerLevel) {
        if (!definition.enabled || playerLevel < definition.unlockLevel) {
            return 0;
        }
        int relativeLevel = playerLevel - definition.unlockLevel;
        int tiers = definition.tierInterval <= 0 ? relativeLevel : relativeLevel / definition.tierInterval;
        int computed = 1 + Math.max(0, tiers);
        return Math.min(definition.maxLevel, computed);
    }

    private double computePassiveValue(PassiveDefinition definition, int level) {
        if (level <= 0) {
            return 0.0;
        }
        double rawValue = definition.baseValue + (level - 1) * definition.valuePerLevel;
        if (rawValue <= 0.0) {
            return 0.0;
        }
        return appliesResourceDivisor(definition.type())
                ? rawValue / PassiveRegenSystem.RESOURCE_REGEN_DIVISOR
                : rawValue;
    }

    private boolean appliesResourceDivisor(PassiveType type) {
        return type == PassiveType.MANA_REGENERATION;
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str.trim());
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return defaultValue;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public record PassiveDefinition(PassiveType type,
            boolean enabled,
            int unlockLevel,
            double baseValue,
            double valuePerLevel,
            int maxLevel,
            int tierInterval) {
        static PassiveDefinition disabled(PassiveType type) {
            return new PassiveDefinition(type, false, Integer.MAX_VALUE, 0.0, 0.0, 0, 1);
        }
    }

    public record PassiveSnapshot(PassiveDefinition definition, int level, double value) {
        public boolean isUnlocked() {
            return level > 0;
        }
    }

    public record PassiveSyncResult(Map<PassiveType, PassiveSnapshot> snapshots,
            List<PassiveType> leveledUp) {
    }

    public static final class PassiveRuntimeState {
        private long lastCombatMillis;
        private float lastSignatureValue = Float.NaN;
        private boolean regenerationActive;
        private long luckMobDropWindowExpiresAt;
        private int luckMobDropStacks;
        private float lastHealingSample = Float.NaN;
        private float lastStaminaSample = Float.NaN;
        private long secondWindCooldownExpiresAt;
        private long secondWindActiveUntil;
        private long firstStrikeCooldownExpiresAt;
        private boolean secondWindReadyNotified = true;
        private boolean firstStrikeReadyNotified = true;
        private boolean adrenalineReadyNotified = true;
        private boolean executionerReadyNotified = true;
        private boolean retaliationReadyNotified = true;
        private double secondWindHealPerSecond;
        private double secondWindHealRemaining;
        private long adrenalineCooldownExpiresAt;
        private long adrenalineActiveUntil;
        private double adrenalineRestorePerSecond;
        private double adrenalineRestoreRemaining;
        private long retaliationCooldownExpiresAt;
        private long retaliationWindowExpiresAt;
        private double retaliationDamageStored;
        private long absorbCooldownExpiresAt;
        private long executionerCooldownExpiresAt;
        private long trueEdgeCooldownExpiresAt;
        private long swiftnessActiveUntil;
        private int swiftnessStacks;
        private long lastMobKillMillis;

        PassiveRuntimeState(UUID ignored) {
        }

        public long getLastCombatMillis() {
            return lastCombatMillis;
        }

        public void setLastCombatMillis(long lastCombatMillis) {
            this.lastCombatMillis = lastCombatMillis;
        }

        public float getLastSignatureValue() {
            return lastSignatureValue;
        }

        public void setLastSignatureValue(float lastSignatureValue) {
            this.lastSignatureValue = lastSignatureValue;
        }

        public boolean isRegenerationActive() {
            return regenerationActive;
        }

        public void setRegenerationActive(boolean regenerationActive) {
            this.regenerationActive = regenerationActive;
        }

        public long getLuckMobDropWindowExpiresAt() {
            return luckMobDropWindowExpiresAt;
        }

        public void setLuckMobDropWindowExpiresAt(long luckMobDropWindowExpiresAt) {
            this.luckMobDropWindowExpiresAt = luckMobDropWindowExpiresAt;
        }

        public int getLuckMobDropStacks() {
            return luckMobDropStacks;
        }

        public void setLuckMobDropStacks(int luckMobDropStacks) {
            this.luckMobDropStacks = luckMobDropStacks;
        }

        public float getLastHealingSample() {
            return lastHealingSample;
        }

        public void setLastHealingSample(float lastHealingSample) {
            this.lastHealingSample = lastHealingSample;
        }

        public float getLastStaminaSample() {
            return lastStaminaSample;
        }

        public void setLastStaminaSample(float lastStaminaSample) {
            this.lastStaminaSample = lastStaminaSample;
        }

        public long getSecondWindCooldownExpiresAt() {
            return secondWindCooldownExpiresAt;
        }

        public void setSecondWindCooldownExpiresAt(long secondWindCooldownExpiresAt) {
            this.secondWindCooldownExpiresAt = secondWindCooldownExpiresAt;
        }

        public long getSecondWindActiveUntil() {
            return secondWindActiveUntil;
        }

        public void setSecondWindActiveUntil(long secondWindActiveUntil) {
            this.secondWindActiveUntil = secondWindActiveUntil;
        }

        public long getFirstStrikeCooldownExpiresAt() {
            return firstStrikeCooldownExpiresAt;
        }

        public void setFirstStrikeCooldownExpiresAt(long firstStrikeCooldownExpiresAt) {
            this.firstStrikeCooldownExpiresAt = firstStrikeCooldownExpiresAt;
        }

        public boolean isSecondWindReadyNotified() {
            return secondWindReadyNotified;
        }

        public void setSecondWindReadyNotified(boolean secondWindReadyNotified) {
            this.secondWindReadyNotified = secondWindReadyNotified;
        }

        public boolean isFirstStrikeReadyNotified() {
            return firstStrikeReadyNotified;
        }

        public void setFirstStrikeReadyNotified(boolean firstStrikeReadyNotified) {
            this.firstStrikeReadyNotified = firstStrikeReadyNotified;
        }

        public boolean isAdrenalineReadyNotified() {
            return adrenalineReadyNotified;
        }

        public void setAdrenalineReadyNotified(boolean adrenalineReadyNotified) {
            this.adrenalineReadyNotified = adrenalineReadyNotified;
        }

        public boolean isExecutionerReadyNotified() {
            return executionerReadyNotified;
        }

        public void setExecutionerReadyNotified(boolean executionerReadyNotified) {
            this.executionerReadyNotified = executionerReadyNotified;
        }

        public boolean isRetaliationReadyNotified() {
            return retaliationReadyNotified;
        }

        public void setRetaliationReadyNotified(boolean retaliationReadyNotified) {
            this.retaliationReadyNotified = retaliationReadyNotified;
        }

        public double getSecondWindHealPerSecond() {
            return secondWindHealPerSecond;
        }

        public void setSecondWindHealPerSecond(double secondWindHealPerSecond) {
            this.secondWindHealPerSecond = Math.max(0.0D, secondWindHealPerSecond);
        }

        public double getSecondWindHealRemaining() {
            return secondWindHealRemaining;
        }

        public void setSecondWindHealRemaining(double secondWindHealRemaining) {
            this.secondWindHealRemaining = Math.max(0.0D, secondWindHealRemaining);
        }

        public void clearPassiveCooldowns() {
            this.secondWindCooldownExpiresAt = 0L;
            this.secondWindActiveUntil = 0L;
            this.firstStrikeCooldownExpiresAt = 0L;
            this.secondWindReadyNotified = true;
            this.firstStrikeReadyNotified = true;
            this.adrenalineReadyNotified = true;
            this.executionerReadyNotified = true;
            this.retaliationReadyNotified = true;
            this.secondWindHealPerSecond = 0.0D;
            this.secondWindHealRemaining = 0.0D;
            this.adrenalineCooldownExpiresAt = 0L;
            this.adrenalineActiveUntil = 0L;
            this.adrenalineRestorePerSecond = 0.0D;
            this.adrenalineRestoreRemaining = 0.0D;
            this.retaliationCooldownExpiresAt = 0L;
            this.retaliationWindowExpiresAt = 0L;
            this.retaliationDamageStored = 0.0D;
            this.absorbCooldownExpiresAt = 0L;
            this.executionerCooldownExpiresAt = 0L;
            this.trueEdgeCooldownExpiresAt = 0L;
            this.swiftnessActiveUntil = 0L;
            this.swiftnessStacks = 0;
            this.lastHealingSample = Float.NaN;
            this.lastStaminaSample = Float.NaN;
            this.lastMobKillMillis = 0L;
        }

        public long getAdrenalineCooldownExpiresAt() {
            return adrenalineCooldownExpiresAt;
        }

        public void setAdrenalineCooldownExpiresAt(long adrenalineCooldownExpiresAt) {
            this.adrenalineCooldownExpiresAt = adrenalineCooldownExpiresAt;
        }

        public long getAdrenalineActiveUntil() {
            return adrenalineActiveUntil;
        }

        public void setAdrenalineActiveUntil(long adrenalineActiveUntil) {
            this.adrenalineActiveUntil = adrenalineActiveUntil;
        }

        public double getAdrenalineRestorePerSecond() {
            return adrenalineRestorePerSecond;
        }

        public void setAdrenalineRestorePerSecond(double adrenalineRestorePerSecond) {
            this.adrenalineRestorePerSecond = Math.max(0.0D, adrenalineRestorePerSecond);
        }

        public double getAdrenalineRestoreRemaining() {
            return adrenalineRestoreRemaining;
        }

        public void setAdrenalineRestoreRemaining(double adrenalineRestoreRemaining) {
            this.adrenalineRestoreRemaining = Math.max(0.0D, adrenalineRestoreRemaining);
        }

        public long getRetaliationCooldownExpiresAt() {
            return retaliationCooldownExpiresAt;
        }

        public void setRetaliationCooldownExpiresAt(long retaliationCooldownExpiresAt) {
            this.retaliationCooldownExpiresAt = retaliationCooldownExpiresAt;
        }

        public long getRetaliationWindowExpiresAt() {
            return retaliationWindowExpiresAt;
        }

        public void setRetaliationWindowExpiresAt(long retaliationWindowExpiresAt) {
            this.retaliationWindowExpiresAt = retaliationWindowExpiresAt;
        }

        public double getRetaliationDamageStored() {
            return retaliationDamageStored;
        }

        public void setRetaliationDamageStored(double retaliationDamageStored) {
            this.retaliationDamageStored = Math.max(0.0D, retaliationDamageStored);
        }

        public long getAbsorbCooldownExpiresAt() {
            return absorbCooldownExpiresAt;
        }

        public void setAbsorbCooldownExpiresAt(long absorbCooldownExpiresAt) {
            this.absorbCooldownExpiresAt = absorbCooldownExpiresAt;
        }

        public long getExecutionerCooldownExpiresAt() {
            return executionerCooldownExpiresAt;
        }

        public void setExecutionerCooldownExpiresAt(long executionerCooldownExpiresAt) {
            this.executionerCooldownExpiresAt = executionerCooldownExpiresAt;
        }

        public long getTrueEdgeCooldownExpiresAt() {
            return trueEdgeCooldownExpiresAt;
        }

        public void setTrueEdgeCooldownExpiresAt(long trueEdgeCooldownExpiresAt) {
            this.trueEdgeCooldownExpiresAt = trueEdgeCooldownExpiresAt;
        }

        public long getSwiftnessActiveUntil() {
            return swiftnessActiveUntil;
        }

        public void setSwiftnessActiveUntil(long swiftnessActiveUntil) {
            this.swiftnessActiveUntil = swiftnessActiveUntil;
            if (swiftnessActiveUntil <= 0L) {
                this.swiftnessStacks = 0;
            }
        }

        public int getSwiftnessStacks() {
            return swiftnessStacks;
        }

        public void setSwiftnessStacks(int swiftnessStacks) {
            this.swiftnessStacks = Math.max(0, swiftnessStacks);
            if (this.swiftnessStacks == 0) {
                this.swiftnessActiveUntil = 0L;
            }
        }

        public void clearSwiftness() {
            this.swiftnessActiveUntil = 0L;
            this.swiftnessStacks = 0;
        }

        public long getLastMobKillMillis() {
            return lastMobKillMillis;
        }

        public void setLastMobKillMillis(long lastMobKillMillis) {
            this.lastMobKillMillis = lastMobKillMillis;
        }

    }
}
