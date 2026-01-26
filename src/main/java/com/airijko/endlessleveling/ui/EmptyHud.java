package com.airijko.endlessleveling.ui;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * Minimal empty HUD used when the player disables the Endless Leveling HUD.
 */
public class EmptyHud extends CustomUIHud {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public EmptyHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        LOGGER.atFine().log("Building EmptyHud");
        uiCommandBuilder.append("Hud/EmptyHud.ui");
    }
}
