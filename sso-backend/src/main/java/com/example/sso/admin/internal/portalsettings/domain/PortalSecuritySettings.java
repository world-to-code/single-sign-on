package com.example.sso.admin.internal.portalsettings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The admin-console-specific security knobs as an embeddable value object: the elevation-token TTL (the
 * OAuth proof's lifetime, enforced per-tenant by the elevation gate) and the admin-console IP allowlist
 * (comma-separated CIDRs; blank/null = any network). The admin session's idle/absolute lifetimes and the
 * step-up freshness come from the SESSION POLICY resolved for the admin user, not from here.
 */
@Embeddable
public record PortalSecuritySettings(
        @Column(name = "elevation_token_ttl_minutes", nullable = false) int elevationTokenTtlMinutes,
        @Column(name = "admin_allowed_cidrs", columnDefinition = "text") String adminAllowedCidrs) {
}
