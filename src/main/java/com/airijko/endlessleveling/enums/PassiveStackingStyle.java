package com.airijko.endlessleveling.enums;

import java.util.Locale;

/**
 * Determines how multiple values that share the same stacking group should be
 * combined.
 */
public enum PassiveStackingStyle {
    ADDITIVE {
        @Override
        public double combine(double currentValue, double incoming) {
            return currentValue + incoming;
        }
    },
    DIMINISHING {
        @Override
        public double combine(double currentValue, double incoming) {
            double cappedCurrent = clamp01(currentValue);
            double cappedIncoming = clamp01(incoming);
            return 1.0D - ((1.0D - cappedCurrent) * (1.0D - cappedIncoming));
        }
    },
    UNIQUE {
        @Override
        public double combine(double currentValue, double incoming) {
            return Math.max(currentValue, incoming);
        }
    };

    public abstract double combine(double currentValue, double incoming);

    public static PassiveStackingStyle fromConfig(Object raw, PassiveStackingStyle fallback) {
        if (raw instanceof String string) {
            String normalized = string.trim().toUpperCase(Locale.ROOT);
            for (PassiveStackingStyle style : values()) {
                if (style.name().equals(normalized)) {
                    return style;
                }
            }
        }
        return fallback;
    }

    public static PassiveStackingStyle defaultFor(ArchetypePassiveType type) {
        if (type == null) {
            return ADDITIVE;
        }
        return switch (type) {
            case LAST_STAND -> DIMINISHING;
            default -> ADDITIVE;
        };
    }

    private static double clamp01(double value) {
        if (value <= 0.0D) {
            return 0.0D;
        }
        if (value >= 1.0D) {
            return 1.0D;
        }
        return value;
    }
}
