package com.example.sso.admin.internal.portalsettings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The admin-portal security knobs as an embeddable value object: the deliberate re-auth freshness
 * window, the elevation-token TTL, the admin session idle/absolute lifetimes, and the admin-console
 * IP allowlist (comma-separated CIDRs; blank/null = any network).
 */
@Embeddable
public record PortalSecuritySettings(
        @Column(name = "reauth_interval_minutes", nullable = false) int reauthIntervalMinutes,
        @Column(name = "elevation_token_ttl_minutes", nullable = false) int elevationTokenTtlMinutes,
        @Column(name = "session_idle_timeout_minutes", nullable = false) int sessionIdleTimeoutMinutes,
        @Column(name = "session_absolute_lifetime_minutes", nullable = false) int sessionAbsoluteLifetimeMinutes,
        @Column(name = "admin_allowed_cidrs", columnDefinition = "text") String adminAllowedCidrs) {
}
