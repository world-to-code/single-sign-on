package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The admin console's per-tenant enforcement config: the elevation-token lifetime and the console entry IP
 * allowlist. A {@code null} {@link #orgId} is the GLOBAL default every tenant inherits; a non-null one is that
 * tenant's own override. These moved off {@code session_policy} (V85) because they mean something only for the
 * admin console, not for a general session posture.
 */
@Entity
@Table(name = "admin_console_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminConsoleConfig extends AuditedEntity implements OrgOwned {

    @Column(name = "elevation_token_ttl_minutes", nullable = false)
    private int elevationTokenTtlMinutes;

    /** Comma-separated CIDRs the console may be entered from; {@code null}/blank = any network. */
    @Column(name = "admin_allowed_cidrs", columnDefinition = "text")
    private String adminAllowedCidrs;

    /** Owning tenant, or {@code null} for the GLOBAL default row. */
    @Column(name = "org_id")
    private UUID orgId;

    public AdminConsoleConfig(UUID orgId, int elevationTokenTtlMinutes, String adminAllowedCidrs) {
        this.orgId = orgId;
        this.elevationTokenTtlMinutes = elevationTokenTtlMinutes;
        this.adminAllowedCidrs = adminAllowedCidrs;
    }

    /** Point this config at new console settings (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(int elevationTokenTtlMinutes, String adminAllowedCidrs) {
        this.elevationTokenTtlMinutes = elevationTokenTtlMinutes;
        this.adminAllowedCidrs = adminAllowedCidrs;
    }
}
