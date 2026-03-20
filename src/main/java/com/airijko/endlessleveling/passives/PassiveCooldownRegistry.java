package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.cooldowns.CooldownMath;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.passives.PassiveManager.PassiveRuntimeState;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Central registry of archetype passive cooldown fields and metadata.
 */
public final class PassiveCooldownRegistry {

    private static final List<Entry> ENTRIES = List.of(
            Entry.notifiable(
                    "second_wind",
                    "Second Wind",
                    ArchetypePassiveType.SECOND_WIND,
                    PassiveCategory.ON_LOW_HP.getIconItemId(),
                    PassiveRuntimeState::getSecondWindCooldownExpiresAt,
                    PassiveRuntimeState::setSecondWindCooldownExpiresAt,
                    PassiveRuntimeState::isSecondWindReadyNotified,
                    PassiveRuntimeState::setSecondWindReadyNotified),
            Entry.notifiable(
                    "first_strike",
                    "First Strike",
                    ArchetypePassiveType.FIRST_STRIKE,
                    PassiveCategory.ON_HIT.getIconItemId(),
                    PassiveRuntimeState::getFirstStrikeCooldownExpiresAt,
                    PassiveRuntimeState::setFirstStrikeCooldownExpiresAt,
                    PassiveRuntimeState::isFirstStrikeReadyNotified,
                    PassiveRuntimeState::setFirstStrikeReadyNotified),
            Entry.notifiable(
                    "adrenaline",
                    "Adrenaline",
                    ArchetypePassiveType.ADRENALINE,
                    PassiveCategory.PASSIVE_STAT.getIconItemId(),
                    PassiveRuntimeState::getAdrenalineCooldownExpiresAt,
                    PassiveRuntimeState::setAdrenalineCooldownExpiresAt,
                    PassiveRuntimeState::isAdrenalineReadyNotified,
                    PassiveRuntimeState::setAdrenalineReadyNotified),
            Entry.notifiable(
                    "executioner",
                    "Final Incantation",
                    ArchetypePassiveType.EXECUTIONER,
                    PassiveCategory.ON_HIT.getIconItemId(),
                    PassiveRuntimeState::getExecutionerCooldownExpiresAt,
                    PassiveRuntimeState::setExecutionerCooldownExpiresAt,
                    PassiveRuntimeState::isExecutionerReadyNotified,
                    PassiveRuntimeState::setExecutionerReadyNotified),
            Entry.notifiable(
                    "retaliation",
                    "Retaliation",
                    ArchetypePassiveType.RETALIATION,
                    PassiveCategory.ON_DAMAGE_TAKEN.getIconItemId(),
                    PassiveRuntimeState::getRetaliationCooldownExpiresAt,
                    PassiveRuntimeState::setRetaliationCooldownExpiresAt,
                    PassiveRuntimeState::isRetaliationReadyNotified,
                    PassiveRuntimeState::setRetaliationReadyNotified),
            Entry.basic(
                    "absorb",
                    "Absorb",
                    ArchetypePassiveType.ABSORB,
                    PassiveCategory.ON_DAMAGE_TAKEN.getIconItemId(),
                    PassiveRuntimeState::getAbsorbCooldownExpiresAt,
                    PassiveRuntimeState::setAbsorbCooldownExpiresAt),
            Entry.basic(
                    "true_edge",
                    "True Edge",
                    ArchetypePassiveType.TRUE_EDGE,
                    PassiveCategory.ON_HIT.getIconItemId(),
                    PassiveRuntimeState::getTrueEdgeCooldownExpiresAt,
                    PassiveRuntimeState::setTrueEdgeCooldownExpiresAt),
            Entry.basic(
                    "shielding_aura",
                    "Shielding Aura",
                    ArchetypePassiveType.SHIELDING_AURA,
                    PassiveCategory.ON_DAMAGE_TAKEN.getIconItemId(),
                    PassiveRuntimeState::getShieldingAuraCooldownExpiresAt,
                    PassiveRuntimeState::setShieldingAuraCooldownExpiresAt));

    private static final List<Entry> NOTIFIABLE_ENTRIES = ENTRIES.stream()
            .filter(Entry::supportsReadyNotifications)
            .toList();

    private PassiveCooldownRegistry() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static List<Entry> notifiableEntries() {
        return NOTIFIABLE_ENTRIES;
    }

    public static void reduceCooldowns(PassiveRuntimeState state,
            long now,
            long flatReductionMillis,
            double percentRemainingReduction) {
        if (state == null) {
            return;
        }

        for (Entry entry : ENTRIES) {
            long previous = entry.expiresAt(state);
            long updated = CooldownMath.reduceExpiresAt(previous, now, flatReductionMillis, percentRemainingReduction);
            if (updated >= previous) {
                continue;
            }
            entry.setExpiresAt(state, updated);
            entry.markReduced(state);
        }
    }

    public static final class Entry {
        private final String id;
        private final String displayName;
        private final ArchetypePassiveType archetypeType;
        private final String fallbackIconItemId;
        private final ToLongFunction<PassiveRuntimeState> expiresAtGetter;
        private final ObjLongConsumer<PassiveRuntimeState> expiresAtSetter;
        private final Predicate<PassiveRuntimeState> readyNotifiedGetter;
        private final BiConsumer<PassiveRuntimeState, Boolean> readyNotifiedSetter;

        private Entry(String id,
                String displayName,
                ArchetypePassiveType archetypeType,
                String fallbackIconItemId,
                ToLongFunction<PassiveRuntimeState> expiresAtGetter,
                ObjLongConsumer<PassiveRuntimeState> expiresAtSetter,
                Predicate<PassiveRuntimeState> readyNotifiedGetter,
                BiConsumer<PassiveRuntimeState, Boolean> readyNotifiedSetter) {
            this.id = Objects.requireNonNull(id, "id");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.archetypeType = archetypeType;
            this.fallbackIconItemId = fallbackIconItemId == null || fallbackIconItemId.isBlank()
                    ? PassiveCategory.PASSIVE_STAT.getIconItemId()
                    : fallbackIconItemId;
            this.expiresAtGetter = Objects.requireNonNull(expiresAtGetter, "expiresAtGetter");
            this.expiresAtSetter = Objects.requireNonNull(expiresAtSetter, "expiresAtSetter");
            this.readyNotifiedGetter = readyNotifiedGetter;
            this.readyNotifiedSetter = readyNotifiedSetter;
        }

        public static Entry basic(String id,
                String displayName,
                ArchetypePassiveType archetypeType,
                String fallbackIconItemId,
                ToLongFunction<PassiveRuntimeState> expiresAtGetter,
                ObjLongConsumer<PassiveRuntimeState> expiresAtSetter) {
            return new Entry(id,
                    displayName,
                    archetypeType,
                    fallbackIconItemId,
                    expiresAtGetter,
                    expiresAtSetter,
                    null,
                    null);
        }

        public static Entry notifiable(String id,
                String displayName,
                ArchetypePassiveType archetypeType,
                String fallbackIconItemId,
                ToLongFunction<PassiveRuntimeState> expiresAtGetter,
                ObjLongConsumer<PassiveRuntimeState> expiresAtSetter,
                Predicate<PassiveRuntimeState> readyNotifiedGetter,
                BiConsumer<PassiveRuntimeState, Boolean> readyNotifiedSetter) {
            return new Entry(id,
                    displayName,
                    archetypeType,
                    fallbackIconItemId,
                    expiresAtGetter,
                    expiresAtSetter,
                    readyNotifiedGetter,
                    readyNotifiedSetter);
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public ArchetypePassiveType archetypeType() {
            return archetypeType;
        }

        public String fallbackIconItemId() {
            return fallbackIconItemId;
        }

        public long expiresAt(PassiveRuntimeState state) {
            return state == null ? 0L : expiresAtGetter.applyAsLong(state);
        }

        public void setExpiresAt(PassiveRuntimeState state, long expiresAt) {
            if (state == null) {
                return;
            }
            expiresAtSetter.accept(state, expiresAt);
        }

        public boolean supportsReadyNotifications() {
            return readyNotifiedGetter != null && readyNotifiedSetter != null;
        }

        public boolean isReadyNotified(PassiveRuntimeState state) {
            if (!supportsReadyNotifications() || state == null) {
                return true;
            }
            return readyNotifiedGetter.test(state);
        }

        public void setReadyNotified(PassiveRuntimeState state, boolean readyNotified) {
            if (!supportsReadyNotifications() || state == null) {
                return;
            }
            readyNotifiedSetter.accept(state, readyNotified);
        }

        public void markReduced(PassiveRuntimeState state) {
            if (supportsReadyNotifications()) {
                setReadyNotified(state, false);
            }
        }
    }
}
