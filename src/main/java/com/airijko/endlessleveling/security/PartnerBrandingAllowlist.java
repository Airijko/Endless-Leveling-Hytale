package com.airijko.endlessleveling.security;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hardcoded partner server allowlist for branding authorization.
 *
 * Branding overrides are accepted only when at least one declared server host
 * matches this list.
 */
public final class PartnerBrandingAllowlist {

    private static final Set<String> ALLOWED_EXACT_HOSTS = Set.of(
            "play.histatu.net");

    private static final Set<String> ALLOWED_SUFFIXES = Set.of(
            ".histatu.net");

    private PartnerBrandingAllowlist() {
    }

    public static Set<String> parseDeclaredHostsCsv(String declaredServerHostsCsv) {
        if (declaredServerHostsCsv == null || declaredServerHostsCsv.isBlank()) {
            return Set.of();
        }

        Set<String> hosts = new LinkedHashSet<>();
        String[] split = declaredServerHostsCsv.split(",");
        for (String part : split) {
            String normalized = normalizeServerHost(part);
            if (normalized != null && !normalized.isBlank()) {
                hosts.add(normalized);
            }
        }
        return hosts;
    }

    public static boolean hasAuthorizedHost(Set<String> declaredServerHosts) {
        if (declaredServerHosts == null || declaredServerHosts.isEmpty()) {
            return false;
        }

        for (String host : declaredServerHosts) {
            if (host == null || host.isBlank()) {
                continue;
            }

            if (ALLOWED_EXACT_HOSTS.contains(host)) {
                return true;
            }

            for (String suffix : ALLOWED_SUFFIXES) {
                if (suffix != null && !suffix.isBlank() && host.endsWith(suffix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String normalizeServerHost(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }

        normalized = normalized.replaceFirst("^https?://", "");

        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(0, slashIndex);
        }

        int colonIndex = normalized.indexOf(':');
        if (colonIndex >= 0) {
            normalized = normalized.substring(0, colonIndex);
        }

        if (normalized.startsWith("*.")) {
            normalized = normalized.substring(2);
        }

        return normalized;
    }
}
