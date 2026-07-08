package com.example.sso.admin.internal.portalsettings.domain;

import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * The runtime-editable security policy for the admin portal (the {@code admin-console} elevation path),
 * scoped to one tenant. It is a distinct axis from per-user/role {@code SessionPolicy}: these knobs bound
 * the deliberate admin-elevation session, not the general browser session.
 *
 * <p>One row per organization plus a single GLOBAL default ({@code orgId == null}) every tenant inherits
 * until it saves its own. The elevation filter resolves the acting tenant and reads its row (or the global
 * fallback); there is no RLS on this table (it is read on pre-context/elevation paths), so isolation is
 * enforced in the application layer by resolving the acting org.
 */
@Entity
@Table(name = "admin_portal_settings")
public class AdminPortalSettings extends AbstractEntity {

    /** The tenant this policy governs, or null for the global default inherited by tenants with no own row. */
    @Column(name = "org_id")
    private UUID orgId;

    @Embedded
    private PortalSecuritySettings settings;

    @Getter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdminPortalSettings() {
    }

    /** Create a tenant's own row, seeded from another row's knobs (copy-on-write from the global default). */
    public AdminPortalSettings(UUID orgId, PortalSecuritySettings settings) {
        this.orgId = orgId;
        this.settings = settings;
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): replace all knobs and re-stamp. */
    public void update(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                       int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes,
                       String adminAllowedCidrs) {
        this.settings = new PortalSecuritySettings(reauthIntervalMinutes, elevationTokenTtlMinutes,
                sessionIdleTimeoutMinutes, sessionAbsoluteLifetimeMinutes, adminAllowedCidrs);
        this.updatedAt = Instant.now();
    }

    public UUID getOrgId() {
        return orgId;
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

    /** The knobs as a value object — used to clone the global default into a new tenant row. */
    public PortalSecuritySettings settings() {
        return settings;
    }
}
