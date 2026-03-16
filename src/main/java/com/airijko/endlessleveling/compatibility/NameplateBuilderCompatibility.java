package com.airijko.endlessleveling.compatibility;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;

public final class NameplateBuilderCompatibility {

    private static final String API_CLASS = "com.frotty27.nameplatebuilder.api.NameplateAPI";
    private static final String SEGMENT_TARGET_CLASS = "com.frotty27.nameplatebuilder.api.SegmentTarget";
    private static final String MOB_SEGMENT_ID = "mob_level";
    private static final String PLAYER_SEGMENT_ID = "player_level";

    private static volatile boolean initialized = false;
    private static volatile Method describeMethod = null;
    private static volatile Method registerMethod = null;
    private static volatile Method removeMethod = null;
    private static volatile Object segmentTargetNpcs = null;
    private static volatile Object segmentTargetPlayers = null;

    private NameplateBuilderCompatibility() {
    }

    public static boolean isAvailable() {
        return ensureInitialized();
    }

    public static boolean describeMobLevelSegment(JavaPlugin plugin) {
        if (plugin == null || !ensureInitialized()) {
            return false;
        }

        try {
            describeMethod.invoke(
                    null,
                    plugin,
                    MOB_SEGMENT_ID,
                    "Mob Level",
                    segmentTargetNpcs,
                    "Lv.10");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean describePlayerLevelSegment(JavaPlugin plugin) {
        if (plugin == null || !ensureInitialized() || segmentTargetPlayers == null) {
            return false;
        }

        try {
            describeMethod.invoke(
                    null,
                    plugin,
                    PLAYER_SEGMENT_ID,
                    "Player Level",
                    segmentTargetPlayers,
                    "Lv.10");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerMobLevel(Store<EntityStore> store, Ref<EntityStore> entityRef, int level) {
        if (store == null || entityRef == null || level <= 0 || !ensureInitialized()) {
            return false;
        }

        try {
            registerMethod.invoke(null, store, entityRef, MOB_SEGMENT_ID, "Lv." + level);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerMobText(Store<EntityStore> store, Ref<EntityStore> entityRef, String text) {
        if (store == null || entityRef == null || text == null || text.isBlank() || !ensureInitialized()) {
            return false;
        }

        try {
            registerMethod.invoke(null, store, entityRef, MOB_SEGMENT_ID, text);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerPlayerLevel(Store<EntityStore> store, Ref<EntityStore> entityRef, int level) {
        if (store == null || entityRef == null || level <= 0 || !ensureInitialized() || segmentTargetPlayers == null) {
            return false;
        }

        try {
            registerMethod.invoke(null, store, entityRef, PLAYER_SEGMENT_ID, "Lv." + level);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean removeMobLevel(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        if (store == null || entityRef == null || !ensureInitialized()) {
            return false;
        }

        try {
            removeMethod.invoke(null, store, entityRef, MOB_SEGMENT_ID);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean removePlayerLevel(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        if (store == null || entityRef == null || !ensureInitialized() || segmentTargetPlayers == null) {
            return false;
        }

        try {
            removeMethod.invoke(null, store, entityRef, PLAYER_SEGMENT_ID);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static synchronized boolean ensureInitialized() {
        if (initialized) {
            return describeMethod != null && registerMethod != null && removeMethod != null
                    && segmentTargetNpcs != null;
        }

        initialized = true;

        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Class<?> segmentTargetClass = Class.forName(SEGMENT_TARGET_CLASS);

            describeMethod = apiClass.getMethod(
                    "describe",
                    JavaPlugin.class,
                    String.class,
                    String.class,
                    segmentTargetClass,
                    String.class);
            registerMethod = apiClass.getMethod(
                    "register",
                    Store.class,
                    Ref.class,
                    String.class,
                    String.class);
            removeMethod = apiClass.getMethod(
                    "remove",
                    Store.class,
                    Ref.class,
                    String.class);

            segmentTargetNpcs = Enum.valueOf((Class<? extends Enum>) segmentTargetClass.asSubclass(Enum.class), "NPCS");
            segmentTargetPlayers = resolveSegmentTargetPlayer(segmentTargetClass);
            return true;
        } catch (Throwable ignored) {
            describeMethod = null;
            registerMethod = null;
            removeMethod = null;
            segmentTargetNpcs = null;
            segmentTargetPlayers = null;
            return false;
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object resolveSegmentTargetPlayer(Class<?> segmentTargetClass) {
        Class<? extends Enum> enumClass = segmentTargetClass.asSubclass(Enum.class);
        String[] candidates = new String[] { "PLAYERS", "PLAYER" };
        for (String candidate : candidates) {
            try {
                return Enum.valueOf(enumClass, candidate);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }
}