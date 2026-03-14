package com.airijko.endlessleveling.util;

/**
 * Centralized localization key registry for chat notifications and HUD labels.
 *
 * Add new keys here first, then consume them via Lang.tr(...).
 */
public enum LocalizationKey {
    NOTIFY_SKILLS_CHAT_HAVE("notify.skills.chat.have", "You still have "),
    NOTIFY_SKILLS_CHAT_USE("notify.skills.chat.use", " skill points. Use "),
    NOTIFY_SKILLS_CHAT_END("notify.skills.chat.end", " to spend them."),
    NOTIFY_SKILLS_COMMAND("notify.skills.command", FixedValue.ROOT_COMMAND.value()),

    NOTIFY_AUGMENTS_AVAILABLE_HEADER("notify.augments.available.header",
            "You have augments available to choose from:"),
    NOTIFY_AUGMENTS_AVAILABLE_FOOTER("notify.augments.available.footer",
            "Use " + FixedValue.ROOT_COMMAND.value() + " augments to choose."),

    NOTIFY_PASSIVES_SWIFTNESS_FADED("notify.passives.swiftness.faded", "Swiftness has faded."),
    NOTIFY_AUGMENTS_COOLDOWN_READY("notify.augments.cooldown.ready", "{0} is ready again!"),
    NOTIFY_PASSIVES_ADRENALINE_TRIGGERED("notify.passives.adrenaline.triggered",
            "Adrenaline triggered! Restoring {0}% stamina over {1}s"),

    NOTIFY_PASSIVES_GENERIC("notify.passives.generic", "{0}"),
    NOTIFY_AUGMENTS_GENERIC("notify.augments.generic", "{0}"),

    NOTIFY_AUGMENTS_SYNC_SUMMARY("notify.augments.sync.summary", "{0} player(s) have mismatched augment slot counts:"),
    NOTIFY_AUGMENTS_SYNC_ENTRY("notify.augments.sync.entry",
            "- {0} [{1}: has {2}, should have {3}] -> " + FixedValue.ROOT_COMMAND.value() + " augments reset {0}"),
    NOTIFY_AUGMENTS_SYNC_FIXALL("notify.augments.sync.fixall",
            "To fix all at once: " + FixedValue.ROOT_COMMAND.value() + " augments resetallplayers"),

    HUD_COMMON_UNAVAILABLE("hud.common.unavailable", "--"),
    HUD_RACE_NONE("hud.race.none", "No Race"),
    HUD_RACE_PREFIX("hud.race.prefix", "Race: "),
    HUD_CLASS_NONE("hud.class.none", "None"),
    HUD_MOB_PREFIX("hud.mob.prefix", "Local mobs: "),
    HUD_MOB_LEVEL_SINGLE("hud.mob.level.single", "Lv. {0}"),
    HUD_MOB_LEVEL_SINGLE_COMPACT("hud.mob.level.single_compact", "Lv {0}"),
    HUD_MOB_LEVEL_RANGE("hud.mob.level.range", "Lv. {0}-{1}"),
    HUD_LEVEL_NO_DATA("hud.level.no_data", "LVL --   XP: 0 / --"),
    HUD_LEVEL_WITHOUT_LEVELING("hud.level.without_leveling", "LVL {0}   XP: {1}"),
    HUD_LEVEL_MAX("hud.level.max", "LVL {0}   MAX LEVEL"),
    HUD_LEVEL_PROGRESS("hud.level.progress", "LVL {0}   XP: {1} / {2}"),

    UI_AUGMENTS_REMAINING_HEADER("ui.augments.remaining.header",
            "You still have more augments to choose from:"),
    UI_AUGMENTS_REMAINING_FOOTER("ui.augments.remaining.footer", "Choose again now.");

    private final String key;
    private final String fallback;

    LocalizationKey(String key, String fallback) {
        this.key = key;
        this.fallback = fallback;
    }

    public String key() {
        return key;
    }

    public String fallback() {
        return fallback;
    }
}
