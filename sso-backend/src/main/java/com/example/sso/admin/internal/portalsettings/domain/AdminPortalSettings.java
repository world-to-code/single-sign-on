package com.example.sso.admin.internal.portalsettings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Embedded
    private PortalSecuritySettings settings;

    @Getter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdminPortalSettings() {
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): replace all knobs and re-stamp. */
    public void update(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                       int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes,
                       String adminAllowedCidrs) {
        this.settings = new PortalSecuritySettings(reauthIntervalMinutes, elevationTokenTtlMinutes,
                sessionIdleTimeoutMinutes, sessionAbsoluteLifetimeMinutes, adminAllowedCidrs);
        this.updatedAt = Instant.now();
    }

    // The security knobs live in the embedded value object; these delegate to preserve callers.
    public int getReauthIntervalMinutes() {
        return settings.reauthIntervalMinutes();
    }

    public int getElevationTokenTtlMinutes() {
        return settings.elevationTokenTtlMinutes();
    }

    public int getSessionIdleTimeoutMinutes() {
        return settings.sessionIdleTimeoutMinutes();
    }

    public int getSessionAbsoluteLifetimeMinutes() {
        return settings.sessionAbsoluteLifetimeMinutes();
    }

    public String getAdminAllowedCidrs() {
        return settings.adminAllowedCidrs();
    }
}
