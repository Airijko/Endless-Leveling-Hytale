package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;

/**
 * Single source of truth for chat message keys, fallback text, and reusable
 * labels used by centralized player chat notifications.
 */
public final class ChatMessageStrings {

    public static final String PREFIX_TEXT = FixedValue.CHAT_PREFIX.value();

    public static final class Command {
        public static final String ROOT = FixedValue.ROOT_COMMAND.value();

        private Command() {
        }
    }

    public static final class Color {
        public static final String PREFIX_RED = "#ff3b30";
        public static final String INFO_CYAN = "#c9c9c9";
        public static final String HIGHLIGHT_GOLD = "#ffc300";
        public static final String WARNING_ORANGE = "#ff9d00";
        public static final String WARNING_RED = "#ff6666";
        public static final String SUCCESS_GREEN = "#6cff78";
        public static final String MUTED = "#c7d2e0";
        public static final String TIER_COMMON = AugmentTheme.gridOwnedColor(PassiveTier.COMMON);
        public static final String TIER_ELITE = AugmentTheme.gridOwnedColor(PassiveTier.ELITE);
        public static final String TIER_MYTHIC = AugmentTheme.gridOwnedColor(PassiveTier.MYTHIC);

        private Color() {
        }
    }

    public static final class Name {
        public static final String AUGMENT = "Augment";
        public static final String SECOND_WIND = "Second Wind";
        public static final String FIRST_STRIKE = "First Strike";
        public static final String ADRENALINE = "Adrenaline";
        public static final String EXECUTIONER = "Final Incantation";
        public static final String RETALIATION = "Retaliation";

        private Name() {
        }
    }

    private ChatMessageStrings() {
    }
}