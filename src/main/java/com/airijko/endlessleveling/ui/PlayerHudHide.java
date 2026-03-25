package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.compatibility.MultipleHudCompatibility;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerHudHide extends CustomUIHud {

    public static final String ID = "EndlessLeveling:PlayerHudHide";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Map<UUID, PlayerHudHide> ACTIVE_HUDS = new ConcurrentHashMap<>();

    private final PlayerRef targetPlayerRef;
    private final java.util.concurrent.atomic.AtomicBoolean built = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    public PlayerHudHide(@Nonnull PlayerRef playerRef) {
        super(playerRef);
        this.targetPlayerRef = playerRef;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        UUID uuid = targetPlayerRef.getUuid();
        if (uuid == null || ACTIVE_HUDS.get(uuid) != this) {
            LOGGER.atFine().log("Ignoring stale PlayerHudHide build for %s", uuid);
            return;
        }

        if (!built.compareAndSet(false, true)) {
            LOGGER.atFine().log("Skipping duplicate PlayerHudHide build for %s", uuid);
            return;
        }

        uiCommandBuilder.append("Hud/Endless_HideHud.ui");
    }

    public static void open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null) {
            return;
        }

        synchronized (PlayerHud.getHudLock(uuid)) {
            PlayerHud.unregister(uuid);

            PlayerHudHide trackedHud = ACTIVE_HUDS.get(uuid);
            if (trackedHud != null) {
                if (!trackedHud.targetPlayerRef.isValid()) {
                    unregisterInternal(uuid);
                } else {
                    LOGGER.atFine().log("PlayerHudHide already tracked for %s; skipping duplicate open", uuid);
                    return;
                }
            }

            PlayerHudHide newHud = new PlayerHudHide(playerRef);
            ACTIVE_HUDS.put(uuid, newHud);

            if (MultipleHudCompatibility.showHud(player, playerRef, PlayerHud.MULTI_HUD_SLOT, newHud)) {
                LOGGER.atInfo().log("Opening PlayerHudHide via MultipleHUD for %s", uuid);
                return;
            }

            var hudManager = player.getHudManager();
            var existingHud = hudManager.getCustomHud();
            if (existingHud instanceof PlayerHudHide existingPlayerHudHide) {
                ACTIVE_HUDS.put(uuid, existingPlayerHudHide);
                LOGGER.atFine().log("PlayerHudHide already active for %s; skipping duplicate open", uuid);
                return;
            }

            hudManager.setCustomHud(playerRef, null);

            LOGGER.atInfo().log("Opening PlayerHudHide via default HudManager for %s", uuid);
            hudManager.setCustomHud(playerRef, newHud);
        }
    }

    public static void unregister(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (PlayerHud.getHudLock(uuid)) {
            unregisterInternal(uuid);
        }
    }

    public static int clearAllTrackedHuds() {
        int cleared = ACTIVE_HUDS.size();
        for (PlayerHudHide hud : ACTIVE_HUDS.values()) {
            if (hud != null) {
                hud.built.set(false);
            }
        }
        ACTIVE_HUDS.clear();
        return cleared;
    }

    private static void unregisterInternal(UUID uuid) {
        PlayerHudHide removed = ACTIVE_HUDS.remove(uuid);
        if (removed != null) {
            removed.built.set(false);
        }
    }
}