package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.logger.HytaleLogger;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerDataManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PluginFilesManager filesManager;
    private final SkillManager skillManager;
    private final Yaml yaml;
    private final Map<UUID, PlayerData> playerCache = new HashMap<>();

    public PlayerDataManager(PluginFilesManager filesManager, SkillManager skillManager) {
        this.filesManager = filesManager;
        this.skillManager = skillManager;

        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        LOGGER.atInfo().log("PlayerDataManager initialized.");
    }

    // --- Load or create a player ---
    public PlayerData loadOrCreate(UUID uuid, String playerName) {
        if (playerCache.containsKey(uuid)) {
            LOGGER.atInfo().log("PlayerData for UUID %s retrieved from cache.", uuid);
            return playerCache.get(uuid);
        }

        File file = filesManager.getPlayerDataFile(uuid);
        PlayerData data;

        if (file.exists()) {
            data = loadFromFile(uuid, playerName, file);
            LOGGER.atInfo().log("PlayerData for UUID %s loaded from file.", uuid);
        } else {
            data = new PlayerData(uuid, playerName, getStartingSkillPoints());
            LOGGER.atInfo().log("PlayerData for UUID %s created new.", uuid);
        }

        // Cache and save
        playerCache.put(uuid, data);
        save(data);
        LOGGER.atInfo().log("PlayerData for UUID %s cached and saved.", uuid);

        return data;
    }

    // --- Get player from cache ---
    public PlayerData get(UUID uuid) {
        PlayerData data = playerCache.get(uuid);
        if (data != null) {
            LOGGER.atInfo().log("PlayerData for UUID %s retrieved from cache.", uuid);
        } else {
            LOGGER.atWarning().log("PlayerData for UUID %s not found in cache.", uuid);
        }
        return data;
    }

    // --- Remove player from cache ---
    public void remove(UUID uuid) {
        playerCache.remove(uuid);
        LOGGER.atInfo().log("PlayerData for UUID %s removed from cache.", uuid);
    }

    // --- Save a player ---
    public void save(PlayerData data) {
        File file = filesManager.getPlayerDataFile(data.getUuid());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("playerName", data.getPlayerName());
        map.put("xp", data.getXp());
        map.put("level", data.getLevel());
        map.put("skillPoints", data.getSkillPoints());

        Map<String, Integer> attrs = new LinkedHashMap<>();
        for (SkillAttributeType type : SkillAttributeType.values()) {
            attrs.put(type.name(), data.getPlayerSkillAttributeLevel(type));
        }
        map.put("attributes", attrs);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("playerHud", data.isPlayerHudEnabled());
        options.put("criticalNotif", data.isCriticalNotifEnabled());
        options.put("xpNotif", data.isXpNotifEnabled());
        map.put("options", options);

        try (StringWriter buffer = new StringWriter(); FileWriter writer = new FileWriter(file)) {
            yaml.dump(map, buffer);
            String yamlContent = buffer.toString()
                    .replace("\nattributes:", "\n\nattributes:")
                    .replace("\noptions:", "\n\noptions:");
            writer.write(yamlContent);
            LOGGER.atFine().log("PlayerData for UUID %s saved to file.", data.getUuid());
        } catch (IOException e) {
            LOGGER.atSevere().log("Failed to save PlayerData for UUID %s: %s", data.getUuid(), e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Load from file helper ---
    private PlayerData loadFromFile(UUID uuid, String playerName, File file) {
        try (FileReader reader = new FileReader(file)) {
            Map<String, Object> map = yaml.load(reader);
            if (map == null) {
                LOGGER.atWarning().log("PlayerData file %s for UUID %s is empty; creating new data.", file.getName(), uuid);
                return new PlayerData(uuid, playerName, getStartingSkillPoints());
            }
            PlayerData data = new PlayerData(uuid, playerName, getStartingSkillPoints());

            data.setXp(((Number) map.getOrDefault("xp", 0)).doubleValue());
            data.setLevel((Integer) map.getOrDefault("level", 1));
            data.setSkillPoints((Integer) map.getOrDefault("skillPoints", 0));

            Map<String, Object> attrs = castToStringObjectMap(map.get("attributes"));
            if (attrs != null) {
                for (SkillAttributeType type : SkillAttributeType.values()) {
                    Object value = attrs.get(type.name());
                    int level = value instanceof Number number ? number.intValue() : 1;
                    data.setPlayerSkillAttributeLevel(type, level);
                }
            }

            Map<String, Object> options = castToStringObjectMap(map.get("options"));
            Object playerHud = options != null ? options.get("playerHud") : map.get("playerHud");
            Object criticalNotif = options != null ? options.get("criticalNotif") : map.get("criticalNotif");
            Object xpNotif = options != null ? options.get("xpNotif") : map.get("xpNotif");
            data.setPlayerHudEnabled(parseBoolean(playerHud, true));
            data.setCriticalNotifEnabled(parseBoolean(criticalNotif, true));
            data.setXpNotifEnabled(parseBoolean(xpNotif, true));
            LOGGER.atInfo().log("PlayerData for UUID %s loaded from disk.", uuid);
            return data;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to load PlayerData for UUID %s from %s: %s", uuid,
                    file.getName(), e.getMessage());
            e.printStackTrace();
            // Safety net: fall back to a fresh PlayerData so one bad file
            // cannot crash joins or UI.
            return new PlayerData(uuid, playerName, getStartingSkillPoints());
        }
    }

    public PlayerData getByName(String playerName) {
        if (playerName == null)
            return null;

        for (PlayerData data : playerCache.values()) {
            if (data.getPlayerName().equalsIgnoreCase(playerName)) {
                LOGGER.atInfo().log("PlayerData for %s retrieved from cache by name.", playerName);
                return data;
            }
        }

        LOGGER.atWarning().log("PlayerData for player name %s not found in cache.", playerName);
        return null;
    }

    public void saveAll() {
        LOGGER.atInfo().log("Saving all cached PlayerData (%d entries)...", playerCache.size());
        for (Map.Entry<UUID, PlayerData> entry : playerCache.entrySet()) {
            save(entry.getValue());
        }
        LOGGER.atInfo().log("All cached PlayerData saved successfully.");
    }

    public Collection<PlayerData> getAllCached() {
        return Collections.unmodifiableCollection(playerCache.values());
    }

    /**
     * Load all player data files from disk (if not already cached) and
     * return a list of PlayerData sorted by level descending, then XP
     * descending as a secondary key.
     */
    public List<PlayerData> getAllPlayersSortedByLevel() {
        File folder = filesManager.getPlayerDataFolder();
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                String uuidPart = name.substring(0, name.length() - 4); // strip .yml
                try {
                    UUID uuid = UUID.fromString(uuidPart);
                    // If already cached, use cached version; otherwise, load minimally from file
                    if (!playerCache.containsKey(uuid)) {
                        PlayerData loaded = loadFromFileMinimal(uuid, file);
                        if (loaded != null) {
                            playerCache.put(uuid, loaded);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID file, skip
                }
            }
        }

        List<PlayerData> all = new ArrayList<>(playerCache.values());
        all.sort(Comparator
                .comparingInt(PlayerData::getLevel).reversed()
                .thenComparingDouble(PlayerData::getXp).reversed());
        return all;
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        return defaultValue;
    }

    private Map<String, Object> castToStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    /**
     * Minimal loader used for leaderboards when a player has never joined
     * this server run. Reads name/level/xp/skillPoints from the YAML file.
     */
    private PlayerData loadFromFileMinimal(UUID uuid, File file) {
        try (FileReader reader = new FileReader(file)) {
            Map<String, Object> map = yaml.load(reader);
            if (map == null) {
                LOGGER.atWarning().log("Minimal load: empty YAML for UUID %s in file %s", uuid, file.getName());
                return null;
            }
            String playerName = (String) map.getOrDefault("playerName", uuid.toString());
            PlayerData data = new PlayerData(uuid, playerName, getStartingSkillPoints());

            data.setXp(((Number) map.getOrDefault("xp", 0)).doubleValue());
            data.setLevel((Integer) map.getOrDefault("level", 1));
            data.setSkillPoints((Integer) map.getOrDefault("skillPoints", 0));

            Map<String, Object> attrs = castToStringObjectMap(map.get("attributes"));
            if (attrs != null) {
                for (SkillAttributeType type : SkillAttributeType.values()) {
                    Object value = attrs.get(type.name());
                    int level = value instanceof Number number ? number.intValue() : 1;
                    data.setPlayerSkillAttributeLevel(type, level);
                }
            }

            Map<String, Object> options = castToStringObjectMap(map.get("options"));
            Object playerHud = options != null ? options.get("playerHud") : map.get("playerHud");
            Object criticalNotif = options != null ? options.get("criticalNotif") : map.get("criticalNotif");
            Object xpNotif = options != null ? options.get("xpNotif") : map.get("xpNotif");
            data.setPlayerHudEnabled(parseBoolean(playerHud, true));
            data.setCriticalNotifEnabled(parseBoolean(criticalNotif, true));
            data.setXpNotifEnabled(parseBoolean(xpNotif, true));

            return data;
        } catch (Exception e) {
            LOGGER.atSevere().log("Failed to minimally load PlayerData for UUID %s from %s: %s", uuid,
                    file.getName(), e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private int getStartingSkillPoints() {
        return skillManager != null ? skillManager.getBaseSkillPoints() : 0;
    }
}
