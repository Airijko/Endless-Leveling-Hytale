package com.airijko.endlessleveling.util;

import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.themes.AugmentTheme;

/**
 * Single source of truth for chat message keys, fallback text, and reusable
 * labels used by centralized player chat notifications.
 */
public final class ChatMessageStrings {

    public static final String PREFIX_TEXT = "[EndlessLeveling] ";

    public static final class Color {
        public static final String PREFIX_RED = "#ff3b30";
        public static final String INFO_CYAN = "#4fd7f7";
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

    public static final class Keys {
        public static final String SKILLS_CHAT_HAVE = "notify.skills.chat.have";
        public static final String SKILLS_CHAT_USE = "notify.skills.chat.use";
        public static final String SKILLS_CHAT_END = "notify.skills.chat.end";
        public static final String SKILLS_COMMAND = "notify.skills.command";

        public static final String AUGMENTS_AVAILABLE_HEADER = "notify.augments.available.header";
        public static final String AUGMENTS_AVAILABLE_FOOTER = "notify.augments.available.footer";

        public static final String SWIFTNESS_FADED = "notify.passives.swiftness.faded";
        public static final String AUGMENT_READY_AGAIN = "notify.augments.cooldown.ready";
        public static final String ADRENALINE_TRIGGERED = "notify.passives.adrenaline.triggered";

        public static final String PASSIVE_GENERIC = "notify.passives.generic";
        public static final String AUGMENT_GENERIC = "notify.augments.generic";

        public static final String AUGMENT_SYNC_SUMMARY = "notify.augments.sync.summary";
        public static final String AUGMENT_SYNC_ENTRY = "notify.augments.sync.entry";
        public static final String AUGMENT_SYNC_FIX_ALL = "notify.augments.sync.fixall";

        private Keys() {
        }
    }

    public static final class Fallback {
        public static final String SKILLS_CHAT_HAVE = "You still have ";
        public static final String SKILLS_CHAT_USE = " skill points. Use ";
        public static final String SKILLS_CHAT_END = " to spend them.";
        public static final String SKILLS_COMMAND = "/el";

        public static final String AUGMENTS_AVAILABLE_HEADER = "You have augments available to choose from:";
        public static final String AUGMENTS_AVAILABLE_FOOTER = "Use /el augments to choose.";

        public static final String SWIFTNESS_FADED = "Swiftness has faded.";
        public static final String AUGMENT_READY_AGAIN = "{0} is ready again!";
        public static final String ADRENALINE_TRIGGERED = "Adrenaline triggered! Restoring {0}% stamina over {1}s";

        public static final String PASSIVE_GENERIC = "{0}";
        public static final String AUGMENT_GENERIC = "{0}";

        public static final String AUGMENT_SYNC_SUMMARY = "{0} player(s) have mismatched augment slot counts:";
        public static final String AUGMENT_SYNC_ENTRY = "- {0} [{1}: has {2}, should have {3}] -> /el augments reset {0}";
        public static final String AUGMENT_SYNC_FIX_ALL = "To fix all at once: /el augments resetallplayers";

        private Fallback() {
        }
    }

    public static final class Name {
        public static final String AUGMENT = "Augment";
        public static final String SECOND_WIND = "Second Wind";
        public static final String FIRST_STRIKE = "First Strike";
        public static final String ADRENALINE = "Adrenaline";
        public static final String EXECUTIONER = "Executioner";
        public static final String RETALIATION = "Retaliation";

        private Name() {
        }
    }

    private ChatMessageStrings() {
    }
}