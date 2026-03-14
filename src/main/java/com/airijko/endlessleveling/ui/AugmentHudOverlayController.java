package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.types.BurnAugment;
import com.airijko.endlessleveling.augments.types.ConquerorAugment;
import com.airijko.endlessleveling.augments.types.EndurePainAugment;
import com.airijko.endlessleveling.augments.types.FleetFootworkAugment;
import com.airijko.endlessleveling.augments.types.FortressAugment;
import com.airijko.endlessleveling.augments.types.FrozenDomainAugment;
import com.airijko.endlessleveling.augments.types.OverhealAugment;
import com.airijko.endlessleveling.augments.types.PhaseRushAugment;
import com.airijko.endlessleveling.augments.types.ProtectiveBubbleAugment;
import com.airijko.endlessleveling.augments.types.UndyingRageAugment;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AugmentHudOverlayController {

    private static final double EPSILON = 0.0001D;
    private static final double MIN_VISIBLE_BAR_PROGRESS = 1.0D / 634.0D;
    private static final List<String> DURATION_PRIORITY = List.of(
            BurnAugment.ID,
            FleetFootworkAugment.ID,
            FortressAugment.ID,
            FrozenDomainAugment.ID,
            PhaseRushAugment.ID,
            UndyingRageAugment.ID);
    private static final List<String> SHIELD_PRIORITY = List.of(
            ProtectiveBubbleAugment.ID,
            FortressAugment.ID,
            EndurePainAugment.ID,
            OverhealAugment.ID);

    private final AugmentManager augmentManager;
    private final AugmentRuntimeManager runtimeManager;
    private final Map<String, Long> durationCache = new ConcurrentHashMap<>();
    private final Map<String, Double> overhealShieldPercentCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> maxStacksCache = new ConcurrentHashMap<>();

    public AugmentHudOverlayController(AugmentManager augmentManager, AugmentRuntimeManager runtimeManager) {
        this.augmentManager = augmentManager;
        this.runtimeManager = runtimeManager;
    }

    public HudOverlayState resolve(@Nonnull PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid() || runtimeManager == null) {
            return HudOverlayState.hidden();
        }

        var uuid = playerRef.getUuid();
        if (uuid == null) {
            return HudOverlayState.hidden();
        }

        long now = System.currentTimeMillis();
        AugmentRuntimeManager.AugmentRuntimeState runtimeState = runtimeManager.getRuntimeState(uuid);
        EntityStatMap statMap = resolveStatMap(playerRef);
        int conquerorStacks = resolveConquerorStacks(runtimeState, now);
        int conquerorMaxStacks = resolveConfiguredMaxStacks(ConquerorAugment.ID, 8);

        return new HudOverlayState(resolveDurationBar(runtimeState, now),
                resolveShieldBar(runtimeState, statMap, now),
                conquerorStacks > 0,
                conquerorStacks,
                conquerorStacks > 0 && conquerorStacks >= conquerorMaxStacks);
    }

    private int resolveConquerorStacks(AugmentRuntimeManager.AugmentRuntimeState runtimeState, long now) {
        if (runtimeState == null || !isAugmentSelected(runtimeState, ConquerorAugment.ID)) {
            return 0;
        }

        AugmentRuntimeManager.AugmentState state = runtimeState.getState(ConquerorAugment.ID);
        if (state == null || state.getStacks() <= 0) {
            return 0;
        }

        boolean active = state.getExpiresAt() <= 0L || state.getExpiresAt() > now;
        return active ? state.getStacks() : 0;
    }

    private BarState resolveDurationBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState, long now) {
        if (runtimeState == null) {
            return BarState.hidden();
        }

        List<DurationCandidate> activeCandidates = new ArrayList<>();
        for (String augmentId : DURATION_PRIORITY) {
            DurationCandidate candidate = switch (augmentId) {
                case BurnAugment.ID -> resolveSimpleDuration(runtimeState, BurnAugment.ID, now, false, false);
                case FleetFootworkAugment.ID -> resolveSimpleDuration(runtimeState,
                        FleetFootworkAugment.BUFF_WINDOW_STATE_ID,
                        now,
                        false,
                        false);
                case FortressAugment.ID -> resolveFortressBuffDuration(runtimeState, now);
                case FrozenDomainAugment.ID -> resolveSimpleDuration(runtimeState,
                        FrozenDomainAugment.ID,
                        now,
                        false,
                        false);
                case PhaseRushAugment.ID -> resolveSimpleDuration(runtimeState, PhaseRushAugment.ID, now, false, false);
                case UndyingRageAugment.ID -> resolveSimpleDuration(runtimeState,
                        UndyingRageAugment.ID,
                        now,
                        false,
                        false);
                default -> null;
            };
            if (candidate == null) {
                continue;
            }
            activeCandidates.add(candidate);
        }

        if (activeCandidates.isEmpty()) {
            return BarState.hidden();
        }

        DurationCandidate bestCandidate = activeCandidates.get(0);
        for (int i = 1; i < activeCandidates.size(); i++) {
            DurationCandidate candidate = activeCandidates.get(i);
            if (candidate.expiresAt() < bestCandidate.expiresAt()) {
                bestCandidate = candidate;
            }
        }

        int additionalCount = activeCandidates.size() - 1;
        String label = additionalCount > 0
                ? bestCandidate.label() + " ( +" + additionalCount + " )"
                : bestCandidate.label();
        return new BarState(label, bestCandidate.progress(), true);
    }

    private BarState resolveShieldBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            EntityStatMap statMap,
            long now) {
        if (runtimeState == null) {
            return BarState.hidden();
        }

        for (String augmentId : SHIELD_PRIORITY) {
            BarState candidate = switch (augmentId) {
                case ProtectiveBubbleAugment.ID -> resolveProtectiveBubbleBar(runtimeState, now);
                case FortressAugment.ID -> resolveFortressShieldBar(runtimeState, now);
                case EndurePainAugment.ID -> resolveEndurePainShieldBar(runtimeState, statMap, now);
                case OverhealAugment.ID -> resolveOverhealShieldBar(runtimeState, statMap, now);
                default -> BarState.hidden();
            };
            if (candidate.visible()) {
                return candidate;
            }
        }

        return BarState.hidden();
    }

    private BarState resolveProtectiveBubbleBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState, long now) {
        if (!isAugmentSelected(runtimeState, ProtectiveBubbleAugment.ID)) {
            return BarState.hidden();
        }

        AugmentRuntimeManager.AugmentState state = runtimeState.getState(ProtectiveBubbleAugment.ID);
        if (state != null && state.getStacks() > 0 && state.getExpiresAt() > now) {
            return new BarState(resolveDisplayName(ProtectiveBubbleAugment.ID), 1.0D, true);
        }

        AugmentRuntimeManager.CooldownState cooldown = runtimeState.getCooldown(ProtectiveBubbleAugment.ID);
        boolean available = cooldown == null || cooldown.getExpiresAt() <= now;
        return available
                ? new BarState(resolveDisplayName(ProtectiveBubbleAugment.ID), 1.0D, true)
                : BarState.hidden();
    }

    private BarState resolveFortressShieldBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState, long now) {
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(FortressAugment.ID);
        if (state == null || state.getStacks() <= 0 || state.getExpiresAt() <= now) {
            return BarState.hidden();
        }

        long totalDuration = resolveConfiguredShieldDurationMillis(FortressAugment.ID);
        if (totalDuration <= 0L) {
            totalDuration = Math.max(1L, state.getExpiresAt() - now);
        }

        long remaining = Math.max(0L, state.getExpiresAt() - now);
        return new BarState(resolveDisplayName(FortressAugment.ID), clamp01(remaining / (double) totalDuration), true);
    }

    private BarState resolveOverhealShieldBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            EntityStatMap statMap,
            long now) {
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(OverhealAugment.ID);
        if (state == null || state.getStoredValue() <= EPSILON || state.getExpiresAt() <= now) {
            return BarState.hidden();
        }

        double progress = 0.0D;
        float maxHealth = statMap == null ? 0.0F
                : com.airijko.endlessleveling.augments.AugmentUtils.getMaxHealth(statMap);
        double shieldPercent = resolveOverhealShieldPercent();
        if (maxHealth > 0.0F && shieldPercent > 0.0D) {
            double maxShieldValue = maxHealth * shieldPercent;
            if (maxShieldValue > EPSILON) {
                progress = clamp01(state.getStoredValue() / maxShieldValue);
            }
        }

        if (!isVisiblyFilled(progress)) {
            return BarState.hidden();
        }

        return new BarState("Overheal Shield", progress, true);
    }

    private BarState resolveEndurePainShieldBar(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            EntityStatMap statMap,
            long now) {
        if (!isAugmentSelected(runtimeState, EndurePainAugment.ID)) {
            return BarState.hidden();
        }

        AugmentRuntimeManager.AugmentState state = runtimeState.getState(EndurePainAugment.ID);
        if (state == null || state.getExpiresAt() <= now || state.getStoredValue() <= EPSILON) {
            return BarState.hidden();
        }

        AugmentRuntimeManager.AugmentState capState = runtimeState.getState(EndurePainAugment.SHIELD_CAP_STATE_ID);
        if (capState != null && capState.getExpiresAt() < now) {
            capState.clear();
        }

        double capValue = Math.max(state.getStoredValue(), capState == null ? 0.0D : capState.getStoredValue());
        if (capValue <= EPSILON) {
            return BarState.hidden();
        }

        double progress = 0.0D;
        progress = clamp01(state.getStoredValue() / capValue);

        if (progress > 0.0D && !isVisiblyFilled(progress)) {
            progress = MIN_VISIBLE_BAR_PROGRESS * 1.1D;
        }

        return new BarState(resolveDisplayName(EndurePainAugment.ID), progress, true);
    }

    private DurationCandidate resolveSimpleDuration(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            String augmentId,
            long now,
            boolean requiresStoredValue,
            boolean requiresStacks) {
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(augmentId);
        if (state == null || state.getExpiresAt() <= now) {
            return null;
        }
        if (requiresStoredValue && state.getStoredValue() <= EPSILON) {
            return null;
        }
        if (requiresStacks && state.getStacks() <= 0) {
            return null;
        }

        long totalDuration = resolveConfiguredDurationMillis(augmentId);
        if (totalDuration <= 0L) {
            totalDuration = Math.max(1L, state.getExpiresAt() - now);
        }
        return createDurationCandidate(resolveDisplayName(augmentId), state.getExpiresAt(), totalDuration, now);
    }

    private DurationCandidate resolveFortressBuffDuration(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            long now) {
        AugmentRuntimeManager.AugmentState state = runtimeState.getState(FortressAugment.ID);
        if (state == null) {
            return null;
        }

        long buffExpiresAt = Math.round(state.getStoredValue());
        if (buffExpiresAt <= now) {
            return null;
        }

        long totalDuration = resolveConfiguredDurationMillis(FortressAugment.ID);
        if (totalDuration <= 0L) {
            totalDuration = Math.max(1L, buffExpiresAt - now);
        }
        return createDurationCandidate(resolveDisplayName(FortressAugment.ID), buffExpiresAt, totalDuration, now);
    }

    private DurationCandidate createDurationCandidate(String label, long expiresAt, long totalDuration, long now) {
        long remaining = Math.max(0L, expiresAt - now);
        if (remaining <= 0L) {
            return null;
        }
        long safeTotalDuration = Math.max(1L, totalDuration);
        double progress = clamp01(remaining / (double) safeTotalDuration);
        return new DurationCandidate(label, progress, Math.max(0L, expiresAt - safeTotalDuration), expiresAt);
    }

    private long resolveConfiguredDurationMillis(String augmentId) {
        return durationCache.computeIfAbsent(augmentId, this::loadDurationMillis);
    }

    private long resolveConfiguredShieldDurationMillis(String augmentId) {
        AugmentDefinition definition = augmentManager == null ? null : augmentManager.getAugment(augmentId);
        if (definition == null) {
            return 0L;
        }

        Map<String, Object> passives = definition.getPassives();
        if (OverhealAugment.ID.equals(augmentId)) {
            Map<String, Object> shield = AugmentValueReader.getMap(passives, "overheal_shield");
            return secondsToMillis(AugmentValueReader.getDouble(shield,
                    "decay_duration",
                    AugmentValueReader.getDouble(shield, "duration", 0.0D)));
        }
        if (ProtectiveBubbleAugment.ID.equals(augmentId)) {
            Map<String, Object> bubble = AugmentValueReader.getMap(passives, "immunity_bubble");
            return secondsToMillis(AugmentValueReader.getDouble(bubble, "immunity_window", 0.0D));
        }
        if (FortressAugment.ID.equals(augmentId)) {
            Map<String, Object> shield = AugmentValueReader.getMap(passives, "shield_phase");
            return secondsToMillis(AugmentValueReader.getDouble(shield, "duration", 0.0D));
        }
        return 0L;
    }

    private long loadDurationMillis(String augmentId) {
        String definitionId = FleetFootworkAugment.BUFF_WINDOW_STATE_ID.equalsIgnoreCase(augmentId)
                ? FleetFootworkAugment.ID
                : augmentId;

        AugmentDefinition definition = augmentManager == null ? null : augmentManager.getAugment(definitionId);
        if (definition == null) {
            return 0L;
        }

        Map<String, Object> passives = definition.getPassives();
        if (passives == null || passives.isEmpty()) {
            return 0L;
        }

        return switch (augmentId) {
            case BurnAugment.ID -> secondsToMillis(
                    AugmentValueReader.getDouble(AugmentValueReader.getMap(passives, "aura_burn"), "duration", 0.0D));
            case EndurePainAugment.ID -> secondsToMillis(AugmentValueReader.getDouble(
                    AugmentValueReader.getMap(passives, "heal_over_time"),
                    "duration",
                    0.0D));
            case FleetFootworkAugment.ID, FleetFootworkAugment.BUFF_WINDOW_STATE_ID ->
                secondsToMillis(AugmentValueReader.getDouble(
                        AugmentValueReader.getMap(AugmentValueReader.getMap(passives, "buffs"), "movement_speed"),
                        "duration",
                        0.0D));
            case FortressAugment.ID -> secondsToMillis(
                    AugmentValueReader.getDouble(AugmentValueReader.getMap(passives, "buff_phase"), "duration", 0.0D));
            case FrozenDomainAugment.ID -> secondsToMillis(AugmentValueReader.getDouble(
                    AugmentValueReader.getMap(passives, "aura_frozen_domain"),
                    "duration",
                    0.0D));
            case PhaseRushAugment.ID -> secondsToMillis(
                    AugmentValueReader.getDouble(AugmentValueReader.getMap(passives, "haste_burst"), "duration", 0.0D));
            case UndyingRageAugment.ID -> resolveUndyingRageDurationMillis(passives);
            case OverhealAugment.ID, ProtectiveBubbleAugment.ID -> resolveConfiguredShieldDurationMillis(
                    augmentId);
            default -> 0L;
        };
    }

    private long resolveUndyingRageDurationMillis(Map<String, Object> passives) {
        if (passives == null || passives.isEmpty()) {
            return 0L;
        }
        Map<String, Object> rage = AugmentValueReader.getMap(passives, "rage");
        if (rage.isEmpty()) {
            rage = AugmentValueReader.getMap(passives, "under_rage");
        }
        return secondsToMillis(AugmentValueReader.getDouble(rage, "duration", 0.0D));
    }

    private double resolveOverhealShieldPercent() {
        return overhealShieldPercentCache.computeIfAbsent(OverhealAugment.ID, id -> {
            AugmentDefinition definition = augmentManager == null ? null : augmentManager.getAugment(id);
            if (definition == null) {
                return 0.0D;
            }

            Map<String, Object> shield = AugmentValueReader.getMap(definition.getPassives(), "overheal_shield");
            return Math.max(0.0D,
                    AugmentValueReader.getDouble(shield,
                            "max_shield_percent",
                            AugmentValueReader.getDouble(shield, "max_bonus_health_percent", 0.0D)));
        });
    }

    private int resolveConfiguredMaxStacks(String augmentId, int fallback) {
        if (augmentId == null || augmentId.isBlank()) {
            return Math.max(1, fallback);
        }

        return maxStacksCache.computeIfAbsent(augmentId, id -> {
            AugmentDefinition definition = augmentManager == null ? null : augmentManager.getAugment(id);
            if (definition == null) {
                return Math.max(1, fallback);
            }

            Map<String, Object> passives = definition.getPassives();
            Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
            int configured = AugmentValueReader.getInt(buffs, "max_stacks", fallback);
            return Math.max(1, configured);
        });
    }

    private String resolveDisplayName(String augmentId) {
        String definitionId = FleetFootworkAugment.BUFF_WINDOW_STATE_ID.equalsIgnoreCase(augmentId)
                ? FleetFootworkAugment.ID
                : augmentId;

        AugmentDefinition definition = augmentManager == null ? null : augmentManager.getAugment(definitionId);
        if (definition != null && definition.getName() != null && !definition.getName().isBlank()) {
            return definition.getName();
        }
        if (augmentId == null || augmentId.isBlank()) {
            return "Active Augment";
        }
        String normalized = augmentId.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "Active Augment";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private EntityStatMap resolveStatMap(PlayerRef playerRef) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            return store == null ? null : store.getComponent(ref, EntityStatMap.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private long secondsToMillis(double seconds) {
        if (seconds <= 0.0D) {
            return 0L;
        }
        return Math.max(1L, Math.round(seconds * 1000.0D));
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private boolean isVisiblyFilled(double progress) {
        return progress > MIN_VISIBLE_BAR_PROGRESS;
    }

    private boolean isAugmentSelected(AugmentRuntimeManager.AugmentRuntimeState runtimeState, String augmentId) {
        if (runtimeState == null || augmentId == null || augmentId.isBlank()) {
            return false;
        }

        String signature = runtimeState.getPassiveSelectionSignature();
        if (signature == null || signature.isBlank()) {
            return false;
        }

        String normalizedAugmentId = augmentId.trim().toLowerCase(Locale.ROOT);
        String wrappedSignature = "|" + signature + "|";
        return wrappedSignature.contains("|" + normalizedAugmentId + "|");
    }

    public record HudOverlayState(BarState durationBar,
            BarState shieldBar,
            boolean conquerorActive,
            int conquerorStacks,
            boolean conquerorAtMaxStacks) {
        public static HudOverlayState hidden() {
            return new HudOverlayState(BarState.hidden(), BarState.hidden(), false, 0, false);
        }
    }

    public record BarState(String label, double progress, boolean visible) {
        public static BarState hidden() {
            return new BarState("", 0.0D, false);
        }
    }

    private record DurationCandidate(String label, double progress, long activeSince, long expiresAt) {
    }
}