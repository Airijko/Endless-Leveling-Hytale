package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.augments.AugmentRuntimeManager;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.player.PlayerDataManager;
import com.airijko.endlessleveling.player.SkillManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MovementHasteSystem extends TickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String MOVEMENT_HASTE_SOURCE_ID = "movement_haste_tracker";
    private static final float PROCESS_INTERVAL_SECONDS = 0.2f;
    private static final float PLAYER_DISCOVERY_INTERVAL_SECONDS = 5.0f;
    private static final float BASE_HASTE_REFRESH_SECONDS = 0.75f;
    private static final double POSITION_EPSILON_SQUARED = 0.0064D;
    private static final double MIN_FRACTION = 0.10D;
    private static final double MAX_FRACTION = 1.0D;
    private static final double DAMAGE_HALVE_FACTOR = 0.5D;
    private static final float DAMAGE_RAMP_PAUSE_SECONDS = 0.5f;
    private static final float RAMP_DURATION_SECONDS = 5.0f;
    private static final float DECAY_DELAY_SECONDS = 1.0f;
    private static final float DECAY_DURATION_SECONDS = 3.0f;
    private static final double BONUS_EPSILON = 0.01D;
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 500L;
    private static final long STORE_MISMATCH_LOG_INTERVAL_MILLIS = 5000L;

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final AugmentRuntimeManager augmentRuntimeManager;
    private final Map<UUID, MovementState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Ref<EntityStore>> activePlayerRefs = new ConcurrentHashMap<>();
    private float timeSinceLastProcess = 0.0f;
    private float timeSinceLastDiscovery = 0.0f;

    public MovementHasteSystem(PlayerDataManager playerDataManager,
            SkillManager skillManager,
            AugmentRuntimeManager augmentRuntimeManager) {
        this.playerDataManager = playerDataManager;
        this.skillManager = skillManager;
        this.augmentRuntimeManager = augmentRuntimeManager;
    }

    public void registerPlayer(UUID playerId, Ref<EntityStore> playerRef) {
        if (playerId == null || playerRef == null) {
            return;
        }
        activePlayerRefs.put(playerId, playerRef);
        MovementState state = states.computeIfAbsent(playerId, ignored -> new MovementState());
        state.hasLastPosition = false;
        state.baseHasteRefreshSeconds = 0.0f;
        state.needsApply = true;
        LOGGER.atInfo().log("MovementHasteSystem: registered player %s for movement haste tracking", playerId);
    }

    public void onPlayerDamaged(UUID playerId) {
        if (playerId == null) {
            return;
        }
        MovementState state = states.computeIfAbsent(playerId, ignored -> new MovementState());
        double previousFraction = state.currentFraction;
        state.currentFraction = Math.max(MIN_FRACTION, state.currentFraction * DAMAGE_HALVE_FACTOR);
        state.rampPauseSeconds = DAMAGE_RAMP_PAUSE_SECONDS;
        state.decayDelaySeconds = DECAY_DELAY_SECONDS;
        state.needsApply = true;
        state.baseHasteRefreshSeconds = 0.0f;
        state.atMaxLogged = false;
        state.atFloorLogged = false;
        LOGGER.atInfo().log(
                "MovementHasteSystem: player %s took damage, haste fraction reduced from %.3f to %.3f",
                playerId,
                previousFraction,
                state.currentFraction);
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        states.remove(playerId);
        activePlayerRefs.remove(playerId);
        if (augmentRuntimeManager == null) {
            return;
        }
        augmentRuntimeManager.getRuntimeState(playerId)
                .setAttributeBonus(SkillAttributeType.HASTE, MOVEMENT_HASTE_SOURCE_ID, 0.0D, 0L);
    }

    @Override
    public void tick(float deltaSeconds, int tickCount, Store<EntityStore> store) {
        if (store == null || store.isShutdown()
                || playerDataManager == null || skillManager == null || augmentRuntimeManager == null) {
            return;
        }

        timeSinceLastDiscovery += deltaSeconds;
        if (timeSinceLastDiscovery >= PLAYER_DISCOVERY_INTERVAL_SECONDS) {
            timeSinceLastDiscovery = 0.0f;
            discoverPlayers(store);
        }

        timeSinceLastProcess += deltaSeconds;
        if (timeSinceLastProcess < PROCESS_INTERVAL_SECONDS) {
            return;
        }

        float stepSeconds = timeSinceLastProcess;
        timeSinceLastProcess = 0.0f;

        activePlayerRefs.entrySet().removeIf(entry -> !processActivePlayer(store, entry.getKey(), entry.getValue(), stepSeconds));
    }

    private void discoverPlayers(Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return;
        }

        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(i);
                if (ref == null) {
                    continue;
                }

                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                UUID playerId = playerRef.getUuid();
                if (playerId == null || activePlayerRefs.containsKey(playerId)) {
                    continue;
                }

                registerPlayer(playerId, ref);
                LOGGER.atInfo().log(
                        "MovementHasteSystem: discovered online player %s and added fallback movement tracking",
                        playerRef.getUsername());
            }
        });
    }

    private boolean processActivePlayer(Store<EntityStore> store,
            UUID playerId,
            Ref<EntityStore> ref,
            float deltaSeconds) {
        if (store == null || ref == null || playerId == null) {
            return false;
        }

        MovementState state = states.computeIfAbsent(playerId, ignored -> new MovementState());

        PlayerRef playerRef;
        try {
            playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        } catch (IllegalStateException mismatch) {
            long now = System.currentTimeMillis();
            if (now - state.lastStoreMismatchLogAtMillis >= STORE_MISMATCH_LOG_INTERVAL_MILLIS) {
                LOGGER.atInfo().log(
                        "MovementHasteSystem: skipping player %s in current store due to cross-store reference",
                        playerId);
                state.lastStoreMismatchLogAtMillis = now;
            }
            // Keep this ref tracked; it may be valid in the player's active world store.
            return true;
        }

        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }

        PlayerData playerData = playerDataManager.get(playerId);
        if (playerData == null) {
            return true;
        }

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue hp = statMap == null ? null : statMap.get(DefaultEntityStatTypes.getHealth());
        if (hp != null && hp.get() <= 1.0f) {
            return true;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return true;
        }

        Vector3d position = transform.getPosition();
        boolean movedThisStep = hasMoved(state, position);
        boolean rampPaused = state.rampPauseSeconds > 0.0f;
        state.lastX = position.getX();
        state.lastY = position.getY();
        state.lastZ = position.getZ();
        state.hasLastPosition = true;

        if (movedThisStep != state.wasMovingLastStep) {
            LOGGER.atInfo().log(
                    "MovementHasteSystem: %s is now %s at (%.2f, %.2f, %.2f)",
                    playerData.getPlayerName(),
                    movedThisStep ? "moving" : "still",
                    state.lastX,
                    state.lastY,
                    state.lastZ);
            state.wasMovingLastStep = movedThisStep;
        }

        if (movedThisStep) {
            if (!rampPaused && !state.rampLogged) {
                LOGGER.atInfo().log(
                        "MovementHasteSystem: %s started ramping haste from %.3f",
                        playerData.getPlayerName(),
                        state.currentFraction);
                state.rampLogged = true;
                state.decayDelayLogged = false;
                state.decayLogged = false;
            }
            state.decayDelaySeconds = DECAY_DELAY_SECONDS;
            if (!rampPaused) {
                state.currentFraction = Math.min(MAX_FRACTION,
                        state.currentFraction + (deltaSeconds / RAMP_DURATION_SECONDS) * (MAX_FRACTION - MIN_FRACTION));
            }
        } else {
            if (state.decayDelaySeconds > 0.0f) {
                if (!state.decayDelayLogged) {
                    LOGGER.atInfo().log(
                            "MovementHasteSystem: %s stopped moving, decay begins in %.2fs",
                            playerData.getPlayerName(),
                            state.decayDelaySeconds);
                    state.decayDelayLogged = true;
                    state.rampLogged = false;
                    state.decayLogged = false;
                }
                state.decayDelaySeconds = Math.max(0.0f, state.decayDelaySeconds - deltaSeconds);
            } else {
                if (!state.decayLogged) {
                    LOGGER.atInfo().log(
                            "MovementHasteSystem: %s started decaying haste from %.3f",
                            playerData.getPlayerName(),
                            state.currentFraction);
                    state.decayLogged = true;
                    state.rampLogged = false;
                }
                state.currentFraction = Math.max(MIN_FRACTION,
                        state.currentFraction - (deltaSeconds / DECAY_DURATION_SECONDS) * (MAX_FRACTION - MIN_FRACTION));
            }
        }

        if (state.rampPauseSeconds > 0.0f) {
            state.rampPauseSeconds = Math.max(0.0f, state.rampPauseSeconds - deltaSeconds);
        }

        state.currentFraction = clampFraction(state.currentFraction);
        state.baseHasteRefreshSeconds -= deltaSeconds;

        double currentBonus = resolveMovementBonusPercent(playerId, playerData, state, state.currentFraction);
        logProgress(playerData, state, currentBonus, movedThisStep);
        logBoundaries(playerData, state, currentBonus);
        if (Math.abs(currentBonus - state.appliedBonusPercent) <= BONUS_EPSILON && !state.needsApply) {
            return true;
        }

        augmentRuntimeManager.getRuntimeState(playerId)
                .setAttributeBonus(SkillAttributeType.HASTE, MOVEMENT_HASTE_SOURCE_ID, currentBonus, 0L);
        state.appliedBonusPercent = currentBonus;
        state.needsApply = false;

        try {
            skillManager.applyMovementSpeedModifier(ref, store, playerData);
        } catch (Exception exception) {
            LOGGER.atWarning().log("MovementHasteSystem: failed to apply haste modifiers for %s: %s",
                    playerData.getPlayerName(), exception.getMessage());
        }
        return true;
    }

    private double resolveMovementBonusPercent(UUID playerId,
            PlayerData playerData,
            MovementState state,
            double fraction) {
        if (playerId == null || playerData == null) {
            return 0.0D;
        }
        if (state == null) {
            return 0.0D;
        }
        if (state.baseHasteRefreshSeconds <= 0.0f || Double.isNaN(state.cachedBaseHastePercent)) {
            var runtimeState = augmentRuntimeManager.getRuntimeState(playerId);
            long now = System.currentTimeMillis();
            double currentMovementBonusPercent = runtimeState.getAttributeBonusBySourcePrefix(
                    SkillAttributeType.HASTE,
                    now,
                    MOVEMENT_HASTE_SOURCE_ID);
            SkillManager.HasteBreakdown breakdown = skillManager.getHasteBreakdown(playerData);
            double totalHastePercent = (breakdown.totalMultiplier() - 1.0D) * 100.0D;
            double movementContributionPercent = Math.max(0.0D,
                    breakdown.raceMultiplier() * currentMovementBonusPercent);
            state.cachedBaseHastePercent = Math.max(0.0D, totalHastePercent - movementContributionPercent);
            state.baseHasteRefreshSeconds = BASE_HASTE_REFRESH_SECONDS;
            if (state.cachedBaseHastePercent <= BONUS_EPSILON) {
                if (!state.zeroBaseHasteLogged) {
                    LOGGER.atInfo().log(
                            "MovementHasteSystem: %s has no base haste to ramp (totalHaste=%.3f movementBonus=%.3f movementContribution=%.3f)",
                            playerData.getPlayerName(),
                            totalHastePercent,
                            currentMovementBonusPercent,
                            movementContributionPercent);
                    state.zeroBaseHasteLogged = true;
                }
            } else {
                state.zeroBaseHasteLogged = false;
            }
        }
        return state.cachedBaseHastePercent * clampFraction(fraction);
    }

    private void logProgress(PlayerData playerData,
            MovementState state,
            double currentBonus,
            boolean movedThisStep) {
        if (playerData == null || state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean decaying = !movedThisStep && state.decayDelaySeconds <= 0.0f && state.currentFraction > MIN_FRACTION;
        boolean ramping = movedThisStep && state.currentFraction < MAX_FRACTION;
        if (ramping && now - state.lastRampLogAtMillis >= PROGRESS_LOG_INTERVAL_MILLIS) {
            LOGGER.atInfo().log(
                    "MovementHasteSystem: %s ramping fraction=%.3f baseHaste=%.3f appliedBonus=%.3f",
                    playerData.getPlayerName(),
                    state.currentFraction,
                    state.cachedBaseHastePercent,
                    currentBonus);
            state.lastRampLogAtMillis = now;
        }
        if (decaying && now - state.lastDecayLogAtMillis >= PROGRESS_LOG_INTERVAL_MILLIS) {
            LOGGER.atInfo().log(
                    "MovementHasteSystem: %s decaying fraction=%.3f baseHaste=%.3f appliedBonus=%.3f",
                    playerData.getPlayerName(),
                    state.currentFraction,
                    state.cachedBaseHastePercent,
                    currentBonus);
            state.lastDecayLogAtMillis = now;
        }
    }

    private void logBoundaries(PlayerData playerData, MovementState state, double currentBonus) {
        if (playerData == null || state == null) {
            return;
        }
        if (state.currentFraction >= MAX_FRACTION - 1.0E-6D) {
            if (!state.atMaxLogged) {
                LOGGER.atInfo().log(
                        "MovementHasteSystem: %s reached max haste fraction %.3f with applied bonus %.3f",
                        playerData.getPlayerName(),
                        state.currentFraction,
                        currentBonus);
                state.atMaxLogged = true;
            }
        } else {
            state.atMaxLogged = false;
        }

        if (state.currentFraction <= MIN_FRACTION + 1.0E-6D) {
            if (!state.atFloorLogged) {
                LOGGER.atInfo().log(
                        "MovementHasteSystem: %s reached haste floor %.3f with applied bonus %.3f",
                        playerData.getPlayerName(),
                        state.currentFraction,
                        currentBonus);
                state.atFloorLogged = true;
            }
        } else {
            state.atFloorLogged = false;
        }
    }

    private static boolean hasMoved(MovementState state, Vector3d currentPosition) {
        if (state == null || currentPosition == null || !state.hasLastPosition) {
            return false;
        }
        double deltaX = currentPosition.getX() - state.lastX;
        double deltaZ = currentPosition.getZ() - state.lastZ;
        return (deltaX * deltaX) + (deltaZ * deltaZ) > POSITION_EPSILON_SQUARED;
    }

    private static double clampFraction(double fraction) {
        if (fraction < MIN_FRACTION) {
            return MIN_FRACTION;
        }
        if (fraction > MAX_FRACTION) {
            return MAX_FRACTION;
        }
        return fraction;
    }

    private static final class MovementState {
        private boolean hasLastPosition;
        private boolean wasMovingLastStep;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double currentFraction = MIN_FRACTION;
        private double appliedBonusPercent = Double.NaN;
        private double cachedBaseHastePercent = Double.NaN;
        private float decayDelaySeconds = DECAY_DELAY_SECONDS;
        private float rampPauseSeconds;
        private float baseHasteRefreshSeconds;
        private boolean needsApply;
        private boolean rampLogged;
        private boolean decayDelayLogged;
        private boolean decayLogged;
        private boolean atMaxLogged;
        private boolean atFloorLogged = true;
        private boolean zeroBaseHasteLogged;
        private long lastRampLogAtMillis;
        private long lastDecayLogAtMillis;
        private long lastStoreMismatchLogAtMillis;
    }
}