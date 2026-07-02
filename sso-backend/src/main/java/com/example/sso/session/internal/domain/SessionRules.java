package com.example.sso.session.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The session-management rules of a policy, as an embeddable value object: absolute/idle lifetimes, the
 * step-up re-auth window and its factors, client binding, max concurrent sessions, rotate-on-reauth,
 * and the (global) cookie SameSite attribute.
 */
@Embeddable
public record SessionRules(
        @Column(name = "absolute_timeout_minutes", nullable = false) int absoluteTimeoutMinutes,
        @Column(name = "idle_timeout_minutes", nullable = false) int idleTimeoutMinutes,
        @Column(name = "reauth_interval_minutes", nullable = false) int reauthIntervalMinutes,
        @Column(name = "reauth_factors", nullable = false, length = 128) String reauthFactors,
        @Column(name = "bind_client", nullable = false) boolean bindClient,
        @Column(name = "max_concurrent_sessions", nullable = false) int maxConcurrentSessions,
        @Column(name = "rotate_on_reauth", nullable = false) boolean rotateOnReauth,
        @Column(name = "cookie_same_site", nullable = false, length = 10) String cookieSameSite) {

    /** The seeded Default policy's rules. */
    public static SessionRules defaults() {
        return new SessionRules(480, 30, 5, "TOTP,FIDO2", true, 0, true, "Lax");
    }
}
