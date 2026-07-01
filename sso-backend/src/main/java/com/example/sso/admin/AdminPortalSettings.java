package com.example.sso.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * The single-row, runtime-editable security policy for the admin portal (the {@code admin-console}
 * elevation path). It is a distinct axis from per-user/role {@code SessionPolicy}: these knobs bound
 * the deliberate admin-elevation session, not the general browser session.
 */
@Entity
@Table(name = "admin_portal_settings")
public class AdminPortalSettings {

    /** The one and only row (see the CHECK constraint in V25). */
    static final int SINGLETON_ID = 1;

    @Id
    private int id;

    @Column(name = "reauth_interval_minutes", nullable = false)
    private int reauthIntervalMinutes;

    @Column(name = "elevation_token_ttl_minutes", nullable = false)
    private int elevationTokenTtlMinutes;

    @Column(name = "session_idle_timeout_minutes", nullable = false)
    private int sessionIdleTimeoutMinutes;

    @Column(name = "session_absolute_lifetime_minutes", nullable = false)
    private int sessionAbsoluteLifetimeMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdminPortalSettings() {
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): replace all knobs and re-stamp. */
    public void update(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                       int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes) {
        this.reauthIntervalMinutes = reauthIntervalMinutes;
        this.elevationTokenTtlMinutes = elevationTokenTtlMinutes;
        this.sessionIdleTimeoutMinutes = sessionIdleTimeoutMinutes;
        this.sessionAbsoluteLifetimeMinutes = sessionAbsoluteLifetimeMinutes;
        this.updatedAt = Instant.now();
    }

    public int getReauthIntervalMinutes() {
        return reauthIntervalMinutes;
    }

    public int getElevationTokenTtlMinutes() {
        return elevationTokenTtlMinutes;
    }

    public int getSessionIdleTimeoutMinutes() {
        return sessionIdleTimeoutMinutes;
    }

    public int getSessionAbsoluteLifetimeMinutes() {
        return sessionAbsoluteLifetimeMinutes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Duration reauthInterval() {
        return Duration.ofMinutes(reauthIntervalMinutes);
    }

    public Duration sessionIdleTimeout() {
        return Duration.ofMinutes(sessionIdleTimeoutMinutes);
    }

    public Duration sessionAbsoluteLifetime() {
        return Duration.ofMinutes(sessionAbsoluteLifetimeMinutes);
    }
}
