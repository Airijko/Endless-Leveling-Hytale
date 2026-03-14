package com.airijko.endlessleveling.util;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Centralized player chat notifier for EndlessLeveling.
 *
 * Every chat message sent through this helper gets the same red
 * [EndlessLeveling] prefix.
 */
public final class PlayerChatNotifier {

    private static final String PREFIX_TEXT = ChatMessageStrings.PREFIX_TEXT;
    private static final String PREFIX_COLOR = ChatMessageStrings.Color.PREFIX_RED;

    private static final Pattern LEGACY_PREFIX_PATTERN = Pattern.compile(
            "^\\s*\\[(?:EndlessLeveling|Passive|Luck|Races|Classes)\\]\\s*");

    private PlayerChatNotifier() {
    }

    @Nonnull
    public static Message prefixed(@Nonnull Message body) {
        return Message.join(Message.raw(PREFIX_TEXT).color(PREFIX_COLOR), body);
    }

    public static void send(PlayerRef playerRef, Message body) {
        if (playerRef == null || !playerRef.isValid() || body == null) {
            return;
        }
        playerRef.sendMessage(prefixed(body));
    }

    public static void send(PlayerRef playerRef, String text, String colorHex) {
        if (text == null || text.isBlank()) {
            return;
        }
        send(playerRef, Message.raw(stripKnownPrefix(text)).color(colorHex));
    }

    public static void send(PlayerRef playerRef, ChatMessageTemplate template, Object... args) {
        if (template == null) {
            return;
        }
        String text = text(playerRef, template, args);
        send(playerRef, text, template.colorHex());
    }

    @Nonnull
    public static String text(PlayerRef playerRef, ChatMessageTemplate template, Object... args) {
        if (template == null) {
            return "";
        }
        UUID uuid = playerRef != null ? playerRef.getUuid() : null;
        String translated = Lang.tr(uuid, template.key(), template.fallback(), args);
        return stripKnownPrefix(translated);
    }

    @Nonnull
    public static String stripKnownPrefix(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return LEGACY_PREFIX_PATTERN.matcher(text).replaceFirst("");
    }
}