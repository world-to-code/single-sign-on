package com.example.sso.session.internal.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The session-management rules of a policy, as an embeddable value object: absolute/idle lifetimes, the
 * step-up re-auth window and its factors, client binding, max concurrent sessions, rotate-on-reauth,
 * the (global) cookie SameSite attribute, and the admin-console posture (elevation-token lifetime and the
 * console IP allowlist) — so ONE policy describes a complete session posture, general and admin alike.
 */
@Embeddable
public record SessionRules(
        @Column(name = "absolute_timeout_minutes", nullable = false) int absoluteTimeoutMinutes,
        @Column(name = "idle_timeout_minutes", nullable = false) int idleTimeoutMinutes,
        @Column(name = "reauth_interval_minutes", nullable = false) int reauthIntervalMinutes,
        @Column(name = "reauth_factors", nullable = false, length = 128) String reauthFactors,
        @Column(name = "sensitive_reauth_window_minutes", nullable = false) int sensitiveReauthWindowMinutes,
        @Column(name = "stepup_factors", nullable = false, length = 128) String stepUpFactors,
        @Column(name = "bind_client", nullable = false) boolean bindClient,
        @Column(name = "max_concurrent_sessions", nullable = false) int maxConcurrentSessions,
        @Column(name = "rotate_on_reauth", nullable = false) boolean rotateOnReauth,
        @Column(name = "cookie_same_site", nullable = false, length = 10) String cookieSameSite,
        @Column(name = "elevation_token_ttl_minutes", nullable = false) int elevationTokenTtlMinutes,
        @Column(name = "admin_allowed_cidrs", columnDefinition = "text") String adminAllowedCidrs) {

    /** The seeded Default policy's rules. */
    public static SessionRules defaults() {
        return new SessionRules(480, 30, 5, "TOTP,FIDO2", 2, "TOTP,FIDO2", true, 0, true, "Lax", 5, null);
    }
}
