package com.airijko.endlessleveling.compatibility;

import com.buuz135.mhud.MultipleHUD;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public class MultipleHudCompatibility {
    private MultipleHudCompatibility() {}

    public static boolean showHud(Player player, PlayerRef playerRef, CustomUIHud endlessHud) {
        try {
            // If MultipleHUD is not present, this will throw and we return false.
            MultipleHUD.getInstance().setCustomHud(player, playerRef, "EndlessLevelingHud", endlessHud);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }
}
