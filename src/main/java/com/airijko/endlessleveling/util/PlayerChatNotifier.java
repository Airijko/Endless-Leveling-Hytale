package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Centralized player chat notifier for EndlessLeveling.
 *
 * Every chat message sent through this helper gets the same red
 * [EndlessLeveling] prefix.
 */
public final class PlayerChatNotifier {

    private static final String PREFIX_COLOR = ChatMessageStrings.Color.PREFIX_RED;

    private static final Pattern LEGACY_PREFIX_PATTERN = Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*");
    private static final Pattern COMMAND_TOKEN_PATTERN = Pattern.compile("/[A-Za-z][A-Za-z0-9_-]*");

    private PlayerChatNotifier() {
    }

    @Nonnull
    public static Message prefixed(@Nonnull Message body) {
        return Message.join(Message.raw(FixedValue.CHAT_PREFIX.value()).color(PREFIX_COLOR), body);
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

    public static void sendAugmentAvailability(PlayerRef playerRef, List<PassiveTier> tiers) {
        if (playerRef == null || !playerRef.isValid() || tiers == null || tiers.isEmpty()) {
            return;
        }

        List<Message> parts = new ArrayList<>();
        parts.add(Message.raw(text(playerRef, ChatMessageTemplate.AUGMENTS_AVAILABLE_HEADER))
                .color(ChatMessageTemplate.AUGMENTS_AVAILABLE_HEADER.colorHex()));

        for (PassiveTier tier : tiers) {
            if (tier == null) {
                continue;
            }
            parts.add(Message.raw("\n- ").color(ChatMessageStrings.Color.MUTED));
            parts.add(Message.raw(tier.name()).color(AugmentTheme.gridOwnedColor(tier)));
        }

        parts.add(Message.raw("\n").color(ChatMessageStrings.Color.MUTED));
        parts.add(Message.raw(text(playerRef, ChatMessageTemplate.AUGMENTS_AVAILABLE_FOOTER))
                .color(ChatMessageTemplate.AUGMENTS_AVAILABLE_FOOTER.colorHex()));

        send(playerRef, Message.join(parts.toArray(Message[]::new)));
    }

    @Nonnull
    public static String text(PlayerRef playerRef, ChatMessageTemplate template, Object... args) {
        if (template == null) {
            return "";
        }
        if (template == ChatMessageTemplate.SKILLS_COMMAND) {
            return FixedValue.ROOT_COMMAND.value();
        }
        UUID uuid = playerRef != null ? playerRef.getUuid() : null;
        String translated = Lang.tr(uuid, template.localizationKey(), args);
        if (template.lockCommandPrefix()) {
            translated = COMMAND_TOKEN_PATTERN.matcher(translated).replaceAll(FixedValue.ROOT_COMMAND.value());
        }
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