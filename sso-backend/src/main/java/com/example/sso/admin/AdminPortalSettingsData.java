package com.example.sso.admin;

import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.time.Duration;
import java.util.List;

/**
 * Exposed, read-only projection of the admin-console-specific security settings: the elevation-token TTL
 * and the IP allowlist. The admin session's idle/absolute lifetimes and step-up freshness come from the
 * SESSION POLICY resolved for the admin user, not from here. This is the admin module's public contract;
 * the backing {@code AdminPortalSettings} entity stays module-internal.
 */
public record AdminPortalSettingsData(int elevationTokenTtlMinutes, List<String> adminAllowedCidrs) {

    /** Max age of the elevation access token itself (since issuance) — enforced per-tenant by the gate. */
    public Duration elevationTokenTtl() {
        return Duration.ofMinutes(elevationTokenTtlMinutes);
    }

    /**
     * Whether the admin console may be reached from {@code ip}. An empty allowlist means any network;
     * otherwise the ip must match at least one configured CIDR. A CIDR whose address family differs
     * from the client (e.g. IPv6 client vs IPv4 rule) simply does not match.
     */
    public boolean ipAllowed(String ip) {
        if (adminAllowedCidrs == null || adminAllowedCidrs.isEmpty()) {
            return true;
        }

        return adminAllowedCidrs.stream().anyMatch(cidr -> matches(cidr, ip));
    }

    private boolean matches(String cidr, String ip) {
        try {
            return new IpAddressMatcher(cidr).matches(ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
