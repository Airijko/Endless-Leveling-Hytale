package com.airijko.endlessleveling.ui;

/**
 * XP bar texture resolver intentionally pinned to built-ins.
 *
 * Runtime texture-path mutation for XP bars is unstable/restricted in current UI
 * runtime, so we always return core defaults to guarantee safe rendering.
 */
public final class PartnerXpBarAssetResolver {

    private static final String DEFAULT_XP_BACKGROUND = "Endless_XPBackground.png";
    private static final String DEFAULT_XP_FILL = "Endless_XPFill.png";

    private PartnerXpBarAssetResolver() {
    }

    public static String resolveBackgroundTexturePath() {
        return DEFAULT_XP_BACKGROUND;
    }

    public static String resolveFillTexturePath() {
        return DEFAULT_XP_FILL;
    }
}
