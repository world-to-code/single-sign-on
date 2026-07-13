package com.example.sso.portal.binding;

/**
 * Read model of the admin console's enforcement config for the acting tenant: the elevation-token lifetime and
 * the console entry IP allowlist (comma-separated CIDRs; {@code null}/blank = any network).
 */
public record AdminConsoleConfigView(int elevationTokenTtlMinutes, String adminAllowedCidrs) {
}
