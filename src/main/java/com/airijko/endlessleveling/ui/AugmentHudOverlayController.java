package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.types.BurnAugment;
import com.airijko.endlessleveling.augments.types.BloodthirsterAugment;
import com.airijko.endlessleveling.augments.types.EndurePainAugment;
import com.airijko.endlessleveling.augments.types.FleetFootworkAugment;
import com.airijko.endlessleveling.augments.types.FortressAugment;
import com.airijko.endlessleveling.augments.types.FrozenDomainAugment;
import com.airijko.endlessleveling.augments.types.OverhealAugment;
import com.airijko.endlessleveling.augments.types.PhaseRushAugment;
import com.airijko.endlessleveling.augments.types.ProtectiveBubbleAugment;
import com.airijko.endlessleveling.augments.types.RagingMomentumAugment;
import com.airijko.endlessleveling.augments.types.TankEngineAugment;
import com.airijko.endlessleveling.augments.types.UndyingRageAugment;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.passives.PassiveCooldownRegistry;
import com.airijko.endlessleveling.passives.PassiveManager;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSnapshot;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.passives.type.PartyShieldingAuraPassive;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AugmentHudOverlayController {

    private static final double EPSILON = 0.0001D;
    private static final double MIN_VISIBLE_BAR_PROGRESS = 1.0D / 634.0D;
    private static final String STACKING_ICON_FALLBACK = "Ingredient_Life_Essence";
    private static final int STACKING_LEFT_SLOT_COUNT = 3;
    private static final int STACKING_RIGHT_SLOT_COUNT = 3;
    private static final int STACKING_TOTAL_SLOT_COUNT = STACKING_LEFT_SLOT_COUNT + STACKING_RIGHT_SLOT_COUNT;
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
    private final PassiveManager passiveManager;
    private final Map<String, Long> durationCache = new ConcurrentHashMap<>();
    private final Map<String, Double> overhealShieldPercentCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> maxStacksCache = new ConcurrentHashMap<>();

    public AugmentHudOverlayController(AugmentManager augmentManager,
            AugmentRuntimeManager runtimeManager,
            PassiveManager passiveManager) {
        this.augmentManager = augmentManager;
        this.runtimeManager = runtimeManager;
        this.passiveManager = passiveManager;
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
        PassiveManager.PassiveRuntimeState passiveRuntimeState = passiveManager != null
                ? passiveManager.getRuntimeState(uuid)
                : null;
        ArchetypePassiveSnapshot archetypeSnapshot = resolveArchetypeSnapshot(uuid);
        EntityStatMap statMap = resolveStatMap(playerRef);
        List<StackingHudState> stackingSlots = resolveStackingHudStates(runtimeState,
                passiveRuntimeState,
                archetypeSnapshot,
                now);

        return new HudOverlayState(resolveDurationBar(runtimeState, now),
                resolveShieldBar(runtimeState, passiveRuntimeState, statMap, now),
                stackingSlots);
    }

    private List<StackingHudState> resolveStackingHudStates(AugmentRuntimeManager.AugmentRuntimeState runtimeState,
            PassiveManager.PassiveRuntimeState passiveRuntimeState,
            ArchetypePassiveSnapshot archetypeSnapshot,
            long now) {
        if (runtimeState == null && passiveRuntimeState == null) {
            return emptyStackingLayout();
        }

        List<StackingHudState> leftSlots = new ArrayList<>();
        List<StackingHudState> rightSlots = new ArrayList<>();

        if (runtimeState != null && augmentManager != null) {
            List<String> selectedAugmentIds = resolveSelectedAugmentIds(runtimeState);
            for (String augmentId : selectedAugmentIds) {
                AugmentDefinition definition = augmentManager.getAugment(augmentId);
                if (definition == null) {
                    continue;
                }

                if (leftSlots.size() < STACKING_LEFT_SLOT_COUNT && hasStackingMechanic(definition)) {
                    AugmentRuntimeManager.AugmentState state = runtimeState.getState(augmentId);
                    if (state != null) {
                        int stacks = Math.max(0, state.getStacks());
                        int maxStacks = resolveConfiguredMaxStacks(definition.getId(), definition.getPassives());
                        boolean nextHitTriggerIndicator = usesNextHitTriggerIndicator(definition.getId());
                        boolean decaysOverTime = hasStackDecayMechanic(definition.getId(), definition.getPassives());
                        boolean stacksVisible = nextHitTriggerIndicator
                                ? shouldShowNextHitTrigger(definition.getId(), state, stacks, maxStacks, now)
                                : stacks > 0
                                        && (decaysOverTime
                                                || state.getExpiresAt() <= 0L
                                                || state.getExpiresAt() > now);
                        if (stacksVisible) {
                            boolean atMaxStacks = nextHitTriggerIndicator
                                    ? true
                                    : maxStacks > 0 && stacks >= maxStacks;
                            leftSlots.add(new StackingHudState(
                                    true,
                                    stacks,
                                    atMaxStacks,
                                    resolveCategoryIconItemId(definition),
                                    !nextHitTriggerIndicator));
                        }
                    }
                }

                if (rightSlots.size() >= STACKING_RIGHT_SLOT_COUNT) {
                    continue;
                }

                AugmentRuntimeManager.CooldownState cooldownState = runtimeState.getCooldown(augmentId);
                boolean tracksCooldown = cooldownState != null || hasCooldownMechanic(definition.getPassives());
                if (!tracksCooldown) {
                    continue;
                }

                boolean available = cooldownState == null || cooldownState.getExpiresAt() <= now;
                if (!available) {
                    continue;
                }

                rightSlots.add(new StackingHudState(
                        true,
                        0,
                        false,
                        resolveCategoryIconItemId(definition),
                        false));
            }
        }

        appendArchetypePassiveCooldownIndicators(passiveRuntimeState, archetypeSnapshot, rightSlots, now);

        if (leftSlots.isEmpty() && rightSlots.isEmpty()) {
            return emptyStackingLayout();
        }

        List<StackingHudState> layout = new ArrayList<>(STACKING_TOTAL_SLOT_COUNT);
        for (int i = 0; i < STACKING_LEFT_SLOT_COUNT; i++) {
            layout.add(i < leftSlots.size() ? leftSlots.get(i) : StackingHudState.hidden());
        }
        int rightVisibleCount = Math.min(rightSlots.size(), STACKING_RIGHT_SLOT_COUNT);
        int rightLeadingHidden = STACKING_RIGHT_SLOT_COUNT - rightVisibleCount;
        for (int i = 0; i < STACKING_RIGHT_SLOT_COUNT; i++) {
            if (i < rightLeadingHidden) {
                layout.add(StackingHudState.hidden());
                continue;
            }
            int rightIndex = i - rightLeadingHidden;
            layout.add(rightIndex < rightVisibleCount ? rightSlots.get(rightIndex) : StackingHudState.hidden());
        }
        return layout;
    }

    private ArchetypePassiveSnapshot resolveArchetypeSnapshot(UUID playerUuid) {
        if (playerUuid == null) {
            return ArchetypePassiveSnapshot.empty();
        }

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null || plugin.getPlayerDataManager() == null || plugin.getArchetypePassiveManager() == null) {
            return ArchetypePassiveSnapshot.empty();
        }

        PlayerData playerData = plugin.getPlayerDataManager().get(playerUuid);
        if (playerData == null) {
            return ArchetypePassiveSnapshot.empty();
        }

        return plugin.getArchetypePassiveManager().getSnapshot(playerData);
    }

    private void appendArchetypePassiveCooldownIndicators(PassiveManager.PassiveRuntimeState passiveRuntimeState,
            ArchetypePassiveSnapshot archetypeSnapshot,
            List<StackingHudState> rightSlots,
            long now) {
        if (passiveRuntimeState == null || archetypeSnapshot == null || archetypeSnapshot.isEmpty()) {
            return;
        }

        for (PassiveCooldownRegistry.Entry entry : PassiveCooldownRegistry.entries()) {
            if (rightSlots.size() >= STACKING_RIGHT_SLOT_COUNT) {
                return;
            }

            ArchetypePassiveType passiveType = entry.archetypeType();
            if (!hasArchetypePassive(archetypeSnapshot, passiveType)) {
                continue;
            }

            if (entry.expiresAt(passiveRuntimeState) > now) {
                continue;
            }

            rightSlots.add(new StackingHudState(
                    true,
                    0,
                    false,
                    resolveArchetypePassiveIconItemId(archetypeSnapshot, entry),
                    false));
        }
    }

    private boolean hasArchetypePassive(ArchetypePassiveSnapshot archetypeSnapshot, ArchetypePassiveType passiveType) {
        if (archetypeSnapshot == null || archetypeSnapshot.isEmpty() || passiveType == null) {
            return false;
        }
        if (!archetypeSnapshot.getDefinitions(passiveType).isEmpty()) {
            return true;
        }
        return archetypeSnapshot.getValue(passiveType) > EPSILON;
    }

    private String resolveArchetypePassiveIconItemId(ArchetypePassiveSnapshot archetypeSnapshot,
            PassiveCooldownRegistry.Entry entry) {
        if (archetypeSnapshot == null || entry == null || entry.archetypeType() == null) {
            return STACKING_ICON_FALLBACK;
        }

        for (RacePassiveDefinition definition : archetypeSnapshot.getDefinitions(entry.archetypeType())) {
            if (definition == null) {
                continue;
            }
            PassiveCategory category = definition.category();
            if (category != null && category.getIconItemId() != null && !category.getIconItemId().isBlank()) {
                return category.getIconItemId();
            }
        }

        String fallback = entry.fallbackIconItemId();
        return fallback == null || fallback.isBlank() ? STACKING_ICON_FALLBACK : fallback;
    }

    private List<String> resolveSelectedAugmentIds(AugmentRuntimeManager.AugmentRuntimeState runtimeState) {
        if (runtimeState == null) {
            return List.of();
        }

        String signature = runtimeState.getPassiveSelectionSignature();
        if (signature == null || signature.isBlank()) {
            return List.of();
        }

        List<String> selectedAugmentIds = new ArrayList<>();
        String[] tokens = signature.split("\\|");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            selectedAugmentIds.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return selectedAugmentIds;
    }

    private boolean hasStackingMechanic(AugmentDefinition definition) {
        if (definition == null) {
            return false;
        }
        if (definition.isStackable()) {
            return true;
        }

        Map<String, Object> passives = definition.getPassives();
        if (resolveConfiguredMaxStacks(definition.getId(), passives) > 0) {
            return true;
        }

        return containsStackingHint(passives);
    }

    private boolean containsStackingHint(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString().toLowerCase(Locale.ROOT);
                if (key.contains("stack") || containsStackingHint(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }

        if (node instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (containsStackingHint(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasCooldownMechanic(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString().toLowerCase(Locale.ROOT);
                if (key.contains("cooldown") || hasCooldownMechanic(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }

        if (node instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (hasCooldownMechanic(value)) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<StackingHudState> emptyStackingLayout() {
        List<StackingHudState> slots = new ArrayList<>(STACKING_TOTAL_SLOT_COUNT);
        for (int i = 0; i < STACKING_TOTAL_SLOT_COUNT; i++) {
            slots.add(StackingHudState.hidden());
        }
        return slots;
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
            PassiveManager.PassiveRuntimeState passiveRuntimeState,
            EntityStatMap statMap,
            long now) {
        if (runtimeState != null) {
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
        }

        BarState shieldingAuraBar = resolveShieldingAuraShieldBar(passiveRuntimeState, now);
        if (shieldingAuraBar.visible()) {
            return shieldingAuraBar;
        }

        return BarState.hidden();
    }

    private BarState resolveShieldingAuraShieldBar(PassiveManager.PassiveRuntimeState passiveRuntimeState, long now) {
        if (passiveRuntimeState == null) {
            return BarState.hidden();
        }

        PartyShieldingAuraPassive.cleanupExpiredShield(passiveRuntimeState, now);
        double currentShield = Math.max(0.0D, passiveRuntimeState.getShieldingAuraShieldAmount());
        double maxShield = Math.max(0.0D, passiveRuntimeState.getShieldingAuraShieldMaxAmount());
        long expiresAt = passiveRuntimeState.getShieldingAuraShieldExpiresAt();
        if (currentShield <= EPSILON || maxShield <= EPSILON || expiresAt <= now) {
            return BarState.hidden();
        }

        double progress = clamp01(currentShield / maxShield);
        if (!isVisiblyFilled(progress)) {
            progress = MIN_VISIBLE_BAR_PROGRESS * 1.1D;
        }

        return new BarState("Shielding Aura", progress, true);
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

    private int resolveConfiguredMaxStacks(String augmentId, Map<String, Object> passives) {
        if (augmentId == null || augmentId.isBlank()) {
            return 0;
        }

        return maxStacksCache.computeIfAbsent(augmentId, id -> {
            Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
            if (buffs != null) {
                int configured = AugmentValueReader.getInt(buffs, "max_stacks", 0);
                if (configured > 0) {
                    return configured;
                }
            }

            Map<String, Object> stackingHealth = AugmentValueReader.getMap(passives, "stacking_health");
            if (stackingHealth != null) {
                int configured = AugmentValueReader.getInt(stackingHealth, "max_stacks", 0);
                if (configured > 0) {
                    return configured;
                }
            }

            Map<String, Object> deathStacks = AugmentValueReader.getMap(passives, "death_stacks");
            if (deathStacks != null) {
                int configured = AugmentValueReader.getInt(deathStacks, "max_deaths", 0);
                if (configured > 0) {
                    return configured;
                }
            }

            Map<String, Object> hitCounter = AugmentValueReader.getMap(passives, "hit_counter");
            if (hitCounter != null && !hitCounter.isEmpty()) {
                int configured = AugmentValueReader.getInt(hitCounter, "hits_required", 0);
                if (configured > 0) {
                    return configured;
                }
            }

            int topLevelHitCounter = AugmentValueReader.getInt(passives, "hit_counter", 0);
            if (topLevelHitCounter > 0) {
                return topLevelHitCounter;
            }

            if (BloodthirsterAugment.ID.equals(augmentId)) {
                int healthyHitCounter = AugmentValueReader.getInt(
                        AugmentValueReader.getMap(passives, "healthy_state"),
                        "hit_counter",
                        0);
                int woundedHitCounter = AugmentValueReader.getInt(
                        AugmentValueReader.getMap(passives, "wounded_state"),
                        "hit_counter",
                        0);
                int configured = Math.max(healthyHitCounter, woundedHitCounter);
                if (configured > 0) {
                    return configured;
                }
            }

            return Math.max(0, AugmentValueReader.getInt(passives, "max_stacks", 0));
        });
    }

    private String resolveCategoryIconItemId(AugmentDefinition definition) {
        if (definition == null) {
            return STACKING_ICON_FALLBACK;
        }

        PassiveCategory category = definition.getCategory() == null ? PassiveCategory.PASSIVE_STAT
                : definition
                        .getCategory();
        String iconItemId = category.getIconItemId();
        return iconItemId == null || iconItemId.isBlank() ? STACKING_ICON_FALLBACK : iconItemId;
    }

    private boolean usesNextHitTriggerIndicator(String augmentId) {
        return PhaseRushAugment.ID.equals(augmentId) || BloodthirsterAugment.ID.equals(augmentId);
    }

    private boolean hasStackDecayMechanic(String augmentId, Map<String, Object> passives) {
        if (passives == null || passives.isEmpty()) {
            return false;
        }

        if (RagingMomentumAugment.ID.equals(augmentId)) {
            return AugmentValueReader.getDouble(AugmentValueReader.getMap(passives, "buffs"),
                    "decay_per_second",
                    0.0D) > 0.0D;
        }

        if (TankEngineAugment.ID.equals(augmentId)) {
            return AugmentValueReader.getDouble(AugmentValueReader.getMap(passives, "stacking_health"),
                    "decay_per_second",
                    0.0D) > 0.0D;
        }

        return false;
    }

    private boolean shouldShowNextHitTrigger(String augmentId,
            AugmentRuntimeManager.AugmentState state,
            int stacks,
            int maxStacks,
            long now) {
        if (!usesNextHitTriggerIndicator(augmentId) || state == null) {
            return false;
        }

        if (maxStacks <= 1 || stacks < (maxStacks - 1)) {
            return false;
        }

        if (BloodthirsterAugment.ID.equals(augmentId)) {
            return state.getExpiresAt() <= 0L || state.getExpiresAt() > now;
        }

        return true;
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
            List<StackingHudState> stackingSlots) {
        public static HudOverlayState hidden() {
            return new HudOverlayState(BarState.hidden(), BarState.hidden(), List.of());
        }
    }

    public record BarState(String label, double progress, boolean visible) {
        public static BarState hidden() {
            return new BarState("", 0.0D, false);
        }
    }

    private record DurationCandidate(String label, double progress, long activeSince, long expiresAt) {
    }

    public record StackingHudState(boolean active,
            int stacks,
            boolean atMaxStacks,
            String iconItemId,
            boolean showStackCount) {
        private static StackingHudState hidden() {
            return new StackingHudState(false, 0, false, STACKING_ICON_FALLBACK, false);
        }
    }
}