package com.airijko.endlessleveling.util;

/**
 * Centralized fixed values that must stay consistent across chat/UI flows.
 */
public enum FixedValue {
    ROOT_COMMAND("/lvl"),
    CHAT_PREFIX("[EndlessLeveling] ");

    private final String defaultValue;
    private volatile String value;

    FixedValue(String value) {
        this.defaultValue = value;
        this.value = value;
    }

    public String value() {
        return value;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public void setValue(String value) {
        if (value == null || value.isBlank()) {
            this.value = defaultValue;
            return;
        }
        this.value = value;
    }

    public void reset() {
        this.value = defaultValue;
    }
}
