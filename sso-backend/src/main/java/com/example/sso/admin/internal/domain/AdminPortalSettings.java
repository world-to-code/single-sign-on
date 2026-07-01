package com.example.sso.admin.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;

/**
 * The single-row, runtime-editable security policy for the admin portal (the {@code admin-console}
 * elevation path). It is a distinct axis from per-user/role {@code SessionPolicy}: these knobs bound
 * the deliberate admin-elevation session, not the general browser session.
 */
@Entity
@Table(name = "admin_portal_settings")
public class AdminPortalSettings {

    /** The one and only row (see the CHECK constraint in V25). */
    public static final int SINGLETON_ID = 1;

    @Id
    private int id;

    @Getter
    @Column(name = "reauth_interval_minutes", nullable = false)
    private int reauthIntervalMinutes;

    @Getter
    @Column(name = "elevation_token_ttl_minutes", nullable = false)
    private int elevationTokenTtlMinutes;

    @Getter
    @Column(name = "session_idle_timeout_minutes", nullable = false)
    private int sessionIdleTimeoutMinutes;

    @Getter
    @Column(name = "session_absolute_lifetime_minutes", nullable = false)
    private int sessionAbsoluteLifetimeMinutes;

    /** Comma-separated CIDRs the admin console is reachable from; blank/null means "any network". */
    @Getter
    @Column(name = "admin_allowed_cidrs", columnDefinition = "text")
    private String adminAllowedCidrs;

    @Getter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdminPortalSettings() {
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): replace all knobs and re-stamp. */
    public void update(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                       int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes,
                       String adminAllowedCidrs) {
        this.reauthIntervalMinutes = reauthIntervalMinutes;
        this.elevationTokenTtlMinutes = elevationTokenTtlMinutes;
        this.sessionIdleTimeoutMinutes = sessionIdleTimeoutMinutes;
        this.sessionAbsoluteLifetimeMinutes = sessionAbsoluteLifetimeMinutes;
        this.adminAllowedCidrs = adminAllowedCidrs;
        this.updatedAt = Instant.now();
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
