package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.managers.ConfigManager;

/**
 * Resolves support Discord links from config with safe fallbacks.
 */
public final class DiscordLinkResolver {

    public static final String DEFAULT_DISCORD_INVITE_URL = "https://discord.gg/hfMeu9KWsh";
    private static final String CONFIG_PATH = "discord_invite_url";

    private DiscordLinkResolver() {
    }

    public static String getDiscordInviteUrl() {
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        if (plugin == null) {
            return DEFAULT_DISCORD_INVITE_URL;
        }

        ConfigManager configManager = plugin.getConfigManager();
        if (configManager == null) {
            return DEFAULT_DISCORD_INVITE_URL;
        }

        Object configuredValue = configManager.get(CONFIG_PATH, DEFAULT_DISCORD_INVITE_URL, false);
        if (!(configuredValue instanceof String configuredUrl)) {
            return DEFAULT_DISCORD_INVITE_URL;
        }

        String trimmed = configuredUrl.trim();
        return trimmed.isEmpty() ? DEFAULT_DISCORD_INVITE_URL : trimmed;
    }
}