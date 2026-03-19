package com.airijko.endlessleveling.security;

import com.airijko.endlessleveling.util.DiscordLinkResolver;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that core Endless Leveling page title selectors keep the expected
 * branding text. If selectors are modified, callers can notify players.
 */
public final class UiTitleIntegrityGuard {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final String EXPECTED_BRAND = "Endless Leveling";
    private static final String ALERT_PREFIX = "[EndlessLeveling] ";
    private static final Pattern TEXT_PATTERN = Pattern.compile("@?Text\\s*[:=]\\s*\"([^\"]*)\"");

    private static final List<SelectorCheck> SELECTOR_CHECKS = List.of(
            new SelectorCheck("Common/UI/Custom/Pages/SkillsPage.ui", "SkillsPageTitle", EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/Augments/AugmentsPage.ui", "AugmentsPageTitle",
                    EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/Classes/ClassPathsPage.ui", "ClassPathsTitle",
                    EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/Leaderboards/LeaderboardsPage.ui", "LeaderboardsTitle",
                    EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/Profile/ProfilePage.ui", "ProfileTitle", EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/Races/RacesPage.ui", "RacesTitle", EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/SettingsPage.ui", "SettingsTitle", EXPECTED_BRAND),
            new SelectorCheck("Common/UI/Custom/Pages/SupportPage.ui", "SupportTitle", EXPECTED_BRAND));

    private static final String NAV_RESOURCE_PATH = "Common/UI/Custom/Pages/Nav/LeftNavPanel.ui";
    private static final String NAV_HEADER_SELECTOR = "NavHeader";
    private static final String NAV_SUB_HEADER_SELECTOR = "NavSubHeader";

    private volatile IntegrityResult lastResult = IntegrityResult.ok();
    private volatile String lastFingerprint = "";

    @Nonnull
    public IntegrityResult evaluate() {
        Map<String, String> resourceCache = new HashMap<>();
        List<TitleViolation> violations = new ArrayList<>();

        for (SelectorCheck check : SELECTOR_CHECKS) {
            String content = loadResource(resourceCache, check.resourcePath());
            if (content == null) {
                violations.add(TitleViolation.missingResource(check.resourcePath(), check.selector(), check.expectedText()));
                continue;
            }

            String actualText = extractSelectorText(content, check.selector());
            if (actualText == null) {
                violations.add(TitleViolation.missingText(check.resourcePath(), check.selector(), check.expectedText()));
                continue;
            }

            if (!normalize(actualText).equals(normalize(check.expectedText()))) {
                violations.add(TitleViolation.renamed(check.resourcePath(), check.selector(), check.expectedText(),
                        actualText));
            }
        }

        validateNavSplit(resourceCache, violations);

        IntegrityResult result = violations.isEmpty()
                ? IntegrityResult.ok()
                : new IntegrityResult(true, List.copyOf(violations));
        cacheAndLogIfChanged(result);
        return result;
    }

    public boolean hasUnauthorizedModifications() {
        return evaluate().modified();
    }

    public void notifyPlayerIfUnauthorized(@Nonnull PlayerRef playerRef) {
        notifyPlayerIfUnauthorized(playerRef, evaluate());
    }

    public void notifyPlayerIfUnauthorized(@Nonnull PlayerRef playerRef, @Nonnull IntegrityResult result) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (result == null || !result.modified()) {
            return;
        }

        playerRef.sendMessage(Message.join(
                Message.raw(ALERT_PREFIX).color("#ff4d4d"),
                Message.raw("Unauthorized UI title modification detected.").color("#ff4d4d")));

        List<String> renamedValues = new ArrayList<>();
        boolean hasValidationIssues = false;

        for (TitleViolation violation : result.violations()) {
            if (violation == null) {
                continue;
            }

            if (violation.actual() != null && !violation.actual().isBlank()) {
            if (!renamedValues.contains(violation.actual())) {
                renamedValues.add(violation.actual());
            }
            } else {
            hasValidationIssues = true;
            }
        }

        if (!renamedValues.isEmpty()) {
            String renamedSummary = String.join("\", \"", renamedValues);
            playerRef.sendMessage(Message.join(
                Message.raw(ALERT_PREFIX).color("#ff9f43"),
                Message.raw("UI branding was renamed to ").color("#ffd9a6"),
                Message.raw("\"" + renamedSummary + "\"").color("#ff6b6b"),
                Message.raw(".").color("#ffd9a6")));
        }

        if (hasValidationIssues) {
            playerRef.sendMessage(Message.join(
                Message.raw(ALERT_PREFIX).color("#ff9f43"),
                Message.raw("Some UI branding fields failed validation.").color("#ffd9a6")));
        }

        String discordInviteUrl = DiscordLinkResolver.getDiscordInviteUrl();

        playerRef.sendMessage(Message.join(
            Message.raw(ALERT_PREFIX).color("#ff4d4d"),
            Message.raw("Report this in Discord:").color("#ffd166")));

        playerRef.sendMessage(Message.join(
            Message.raw(ALERT_PREFIX).color("#ff4d4d"),
            Message.raw("1) Open ").color("#ffd166"),
            Message.raw("Endless Leveling Discord").link(discordInviteUrl).color("#6fe3ff"),
            Message.raw(".").color("#ffd166")));

        playerRef.sendMessage(Message.join(
            Message.raw(ALERT_PREFIX).color("#ff4d4d"),
            Message.raw("2) Ping ").color("#ffd166"),
            Message.raw("@juhjuh").color("#ff8ec7"),
            Message.raw(" (Airijko) and attach a screenshot.").color("#ffd166")));
    }

    private void validateNavSplit(Map<String, String> resourceCache, List<TitleViolation> violations) {
        String navContent = loadResource(resourceCache, NAV_RESOURCE_PATH);
        if (navContent == null) {
            violations.add(TitleViolation.missingResource(NAV_RESOURCE_PATH,
                    NAV_HEADER_SELECTOR + "+" + NAV_SUB_HEADER_SELECTOR,
                    EXPECTED_BRAND));
            return;
        }

        String header = extractSelectorText(navContent, NAV_HEADER_SELECTOR);
        String subHeader = extractSelectorText(navContent, NAV_SUB_HEADER_SELECTOR);
        if (header == null) {
            violations.add(TitleViolation.missingText(NAV_RESOURCE_PATH, NAV_HEADER_SELECTOR, "Endless"));
            return;
        }
        if (subHeader == null) {
            violations.add(TitleViolation.missingText(NAV_RESOURCE_PATH, NAV_SUB_HEADER_SELECTOR, "Leveling"));
            return;
        }

        String combinedRaw = header.trim() + " " + subHeader.trim();
        String combined = normalize(header + " " + subHeader);
        if (!combined.equals(normalize(EXPECTED_BRAND))) {
            violations.add(TitleViolation.renamed(
                    NAV_RESOURCE_PATH,
                    NAV_HEADER_SELECTOR + "+" + NAV_SUB_HEADER_SELECTOR,
                    EXPECTED_BRAND,
                    combinedRaw));
        }
    }

    private String loadResource(Map<String, String> resourceCache, String resourcePath) {
        String cached = resourceCache.get(resourcePath);
        if (cached != null) {
            return cached;
        }
        String loaded = readResource(resourcePath);
        if (loaded != null) {
            resourceCache.put(resourcePath, loaded);
        }
        return loaded;
    }

    private String readResource(String resourcePath) {
        try (InputStream in = UiTitleIntegrityGuard.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to read resource %s: %s", resourcePath, e.getMessage());
            return null;
        }
    }

    private String extractSelectorText(String fileContent, String selector) {
        if (fileContent == null || selector == null || selector.isBlank()) {
            return null;
        }

        Pattern blockPattern = Pattern.compile("(?s)#" + Pattern.quote(selector) + "\\s*\\{(.*?)\\}");
        Matcher blockMatcher = blockPattern.matcher(fileContent);
        if (!blockMatcher.find()) {
            return null;
        }

        Matcher textMatcher = TEXT_PATTERN.matcher(blockMatcher.group(1));
        if (!textMatcher.find()) {
            return null;
        }
        return textMatcher.group(1).trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private void cacheAndLogIfChanged(IntegrityResult result) {
        String fingerprint = buildFingerprint(result);
        if (!fingerprint.equals(lastFingerprint)) {
            if (result.modified()) {
                LOGGER.atWarning().log("UI title integrity check failed: %s", String.join(" | ",
                        result.violations().stream().map(TitleViolation::toLogString).toList()));
            } else {
                LOGGER.atInfo().log("UI title integrity check passed.");
            }
            lastFingerprint = fingerprint;
        }
        lastResult = result;
    }

    private String buildFingerprint(IntegrityResult result) {
        if (result == null || !result.modified()) {
            return "ok";
        }
        return "fail|" + String.join("|", result.violations().stream().map(TitleViolation::toLogString).toList());
    }

    @Nonnull
    public IntegrityResult getLastResult() {
        return lastResult;
    }

    private record SelectorCheck(String resourcePath, String selector, String expectedText) {
    }

    public record TitleViolation(String resourcePath,
            String selector,
            String expected,
            String actual,
            String problem) {
        public static TitleViolation missingResource(String resourcePath, String selector, String expected) {
            return new TitleViolation(resourcePath, selector, expected, null, "missing resource");
        }

        public static TitleViolation missingText(String resourcePath, String selector, String expected) {
            return new TitleViolation(resourcePath, selector, expected, null, "missing text binding");
        }

        public static TitleViolation renamed(String resourcePath, String selector, String expected, String actual) {
            return new TitleViolation(resourcePath, selector, expected, actual, "renamed title text");
        }

        public String toLogString() {
            return "resource=" + resourcePath
                    + ", selector=#" + selector
                    + ", expected='" + expected + "'"
                    + ", actual='" + (actual == null ? "<null>" : actual) + "'"
                    + ", problem=" + problem;
        }
    }

    public record IntegrityResult(boolean modified, List<TitleViolation> violations) {
        public static IntegrityResult ok() {
            return new IntegrityResult(false, List.of());
        }
    }
}