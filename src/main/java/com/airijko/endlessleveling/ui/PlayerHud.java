package com.airijko.endlessleveling.ui;

import au.ellie.hyui.builders.HudBuilder;
import au.ellie.hyui.builders.HyUIHud;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.ClassManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PlayerHud {

    public static final String ID = "EndlessLeveling:PlayerHud";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Map<UUID, HyUIHud> ACTIVE_HUDS = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerRef> ACTIVE_REFS = new ConcurrentHashMap<>();
    private static final Map<UUID, HudSnapshot> HUD_SNAPSHOTS = new ConcurrentHashMap<>();

    private PlayerHud() {
        // Utility class
    }

    private static HyUIHud buildSimpleHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
            @Nonnull Store<EntityStore> store) {
        PlayerDataManager playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        RaceManager raceManager = EndlessLeveling.getInstance().getRaceManager();
        ClassManager classManager = EndlessLeveling.getInstance().getClassManager();
        PlayerData data = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;

        HudSnapshot snapshot = buildSnapshot(raceManager, classManager, data, playerRef);

        LOGGER.atFine().log("Building HUD for %s with hyvatar username %s", playerRef.getUuid(), snapshot.playerIcon());
        LOGGER.atFine().log("HUD icons for %s -> primary: %s, secondary: %s", playerRef.getUuid(),
                snapshot.primaryIconId(), snapshot.secondaryIconId());

        String html = buildHudHtml(snapshot);

        HyUIHud hud = HudBuilder.hudForPlayer(playerRef)
                .enableRuntimeTemplateUpdates(true)
                .fromHtml(html)
                .show(store);

        UUID uuid = playerRef.getUuid();
        ACTIVE_REFS.put(uuid, playerRef);
        HUD_SNAPSHOTS.put(uuid, snapshot);
        return hud;
    }

    private static HudSnapshot buildSnapshot(RaceManager raceManager, ClassManager classManager, PlayerData data,
            PlayerRef playerRef) {
        String raceLabel = resolveRaceName(raceManager, data);
        String raceIconId = "Ingredient_Crystal_White";
        String primaryLabel = resolvePrimaryClassName(classManager, data);
        String secondaryLabel = resolveSecondaryClassName(classManager, data);
        String primaryIconId = resolveClassIcon(classManager, data, true);
        String secondaryIconId = resolveClassIcon(classManager, data, false);
        String playerIcon = resolvePlayerName(playerRef);

        return new HudSnapshot(playerIcon, raceIconId, raceLabel, primaryIconId, primaryLabel, secondaryIconId,
                secondaryLabel);
    }

    private static String buildHudHtml(HudSnapshot snapshot) {
        return """
                <div style='anchor-left: 0; anchor-bottom: 0; anchor-height: 100; layout-mode: left;'>
                    <hyvatar username="%s" render="head" size="100" rotate="15"></hyvatar>
                    <div style='layout-mode: bottom; anchor-bottom: 5; anchor-height: 60; anchor-left: 10;'>
                        <div id='RaceText' style='margin: 0; layout-mode: left; vertical-align: center; anchor-height: 24;'>
                            <span class="item-icon" data-hyui-item-id="%s"></span>
                            <p style='padding-left: 4; color: #cfcfcf; font-size: 14; vertical-align: center; display: inline-block;'>%s</p>
                        </div>
                        <div style='margin: 0; layout-mode: left; vertical-align: center; anchor-height: 24; anchor-top: 2;'>
                            <span class="item-icon" data-hyui-item-id="%s"></span>
                            <p id='PrimaryText' style='padding-left: 4; vertical-align: center; font-size: 12; color: #bebebe; text-align: center; display: inline-block;'>%s</p>
                        </div>
                        <div style='margin: 0; layout-mode: left; vertical-align: center; anchor-height: 24; anchor-top: 2;'>
                            <span class="item-icon" data-hyui-item-id="%s"></span>
                            <p id='SecondaryText' style='padding-left: 4; vertical-align: center; font-size: 12; color: #bebebe; text-align: center; display: inline-block;'>%s</p>
                        </div>
                    </div>
                </div>
                """
                .formatted(snapshot.playerIcon(), snapshot.raceIconId(), snapshot.raceLabel(), snapshot.primaryIconId(),
                        snapshot.primaryLabel(), snapshot.secondaryIconId(), snapshot.secondaryLabel());
    }

    private static String resolvePlayerName(PlayerRef playerRef) {
        String refName = playerRef.getUsername();
        if (refName != null && !refName.isBlank()) {
            return refName.trim();
        }
        return playerRef.getUuid().toString();
    }

    private static String resolveRaceName(RaceManager raceManager, PlayerData data) {
        if (raceManager == null || data == null) {
            return "--";
        }
        RaceDefinition race = raceManager.getPlayerRace(data);
        if (race != null) {
            return race.getDisplayName();
        }
        String id = data.getRaceId();
        return id == null ? "--" : id;
    }

    private static String resolvePrimaryClassName(ClassManager classManager, PlayerData data) {
        if (classManager == null || data == null) {
            return "--";
        }
        CharacterClassDefinition def = classManager.getPlayerPrimaryClass(data);
        if (def != null) {
            return def.getDisplayName();
        }
        String id = data.getPrimaryClassId();
        return id == null ? "--" : id;
    }

    private static String resolveSecondaryClassName(ClassManager classManager, PlayerData data) {
        if (classManager == null || data == null) {
            return "None";
        }
        CharacterClassDefinition def = classManager.getPlayerSecondaryClass(data);
        if (def != null) {
            return def.getDisplayName();
        }
        String id = data.getSecondaryClassId();
        return id == null || id.isBlank() ? "None" : id;
    }

    private static String resolveClassIcon(ClassManager classManager, PlayerData data, boolean primary) {
        if (classManager == null || data == null) {
            return "";
        }
        CharacterClassDefinition def = primary
                ? classManager.getPlayerPrimaryClass(data)
                : classManager.getPlayerSecondaryClass(data);
        if (def == null) {
            return "";
        }
        String icon = def.getIconItemId();
        return icon == null ? "" : icon;
    }

    public static void open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            LOGGER.atFine().log("Cannot open PlayerHud: null reference for %s", playerRef.getUuid());
            return;
        }

        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            LOGGER.atFine().log("Cannot open PlayerHud: null store for %s", playerRef.getUuid());
            return;
        }

        HyUIHud existing = ACTIVE_HUDS.get(playerRef.getUuid());
        if (existing != null) {
            existing.unhide();
            updateClassAndRace(playerRef.getUuid());
            return;
        }

        try {
            HyUIHud hud = buildSimpleHud(player, playerRef, store);
            ACTIVE_HUDS.put(playerRef.getUuid(), hud);
            LOGGER.atInfo().log("Opened simple PlayerHud for %s", playerRef.getUuid());
        } catch (Exception ex) {
            LOGGER.atSevere().withCause(ex).log("Failed to open PlayerHud for %s", playerRef.getUuid());
        }
    }

    public static void close(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        HyUIHud hud = ACTIVE_HUDS.remove(uuid);
        if (hud != null) {
            hud.remove();
        }
        ACTIVE_REFS.remove(uuid);
        HUD_SNAPSHOTS.remove(uuid);
    }

    public static boolean isActive(UUID uuid) {
        return uuid != null && ACTIVE_HUDS.containsKey(uuid);
    }

    public static void refreshHud(UUID uuid) {
        if (uuid == null) {
            return;
        }

        updateClassAndRace(uuid);
    }

    public static void refreshAll() {
        for (UUID uuid : ACTIVE_HUDS.keySet()) {
            refreshHud(uuid);
        }
    }

    public static void unregister(UUID uuid) {
        if (uuid == null) {
            return;
        }

        HyUIHud hud = ACTIVE_HUDS.remove(uuid);
        if (hud != null) {
            hud.remove();
        }
        ACTIVE_REFS.remove(uuid);
        HUD_SNAPSHOTS.remove(uuid);
    }

    private static void updateClassAndRace(UUID uuid) {
        if (uuid == null) {
            return;
        }

        HyUIHud hud = ACTIVE_HUDS.get(uuid);
        PlayerRef playerRef = ACTIVE_REFS.get(uuid);
        if (hud == null || playerRef == null) {
            return;
        }

        PlayerDataManager playerDataManager = EndlessLeveling.getInstance().getPlayerDataManager();
        RaceManager raceManager = EndlessLeveling.getInstance().getRaceManager();
        ClassManager classManager = EndlessLeveling.getInstance().getClassManager();
        PlayerData data = playerDataManager != null ? playerDataManager.get(uuid) : null;

        HudSnapshot current = HUD_SNAPSHOTS.get(uuid);
        HudSnapshot updated = buildSnapshot(raceManager, classManager, data, playerRef);

        if (updated.equals(current)) {
            return;
        }

        String html = buildHudHtml(updated);
        HudBuilder.hudForPlayer(playerRef)
                .enableRuntimeTemplateUpdates(true)
                .fromHtml(html)
                .updateExisting(hud);

        HUD_SNAPSHOTS.put(uuid, updated);
    }

    private record HudSnapshot(String playerIcon, String raceIconId, String raceLabel, String primaryIconId,
            String primaryLabel, String secondaryIconId, String secondaryLabel) {
    }
}
