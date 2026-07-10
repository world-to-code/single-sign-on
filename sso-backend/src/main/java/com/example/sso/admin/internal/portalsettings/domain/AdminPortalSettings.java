package com.example.sso.admin.internal.portalsettings.domain;

import com.example.sso.shared.domain.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * Which session policy governs the admin console (the {@code admin-console} elevation path) for one tenant.
 * A null policy means "the policy resolved for the acting admin" — the behaviour before a tenant selects one.
 *
 * <p>One row per organization plus a single GLOBAL default ({@code orgId == null}) every tenant inherits
 * until it saves its own. The elevation filter resolves the acting tenant and reads its row (or the global
 * fallback); there is no RLS on this table (it is read on pre-context/elevation paths), so isolation is
 * enforced in the application layer by resolving the acting org.
 */
@Entity
@Table(name = "admin_portal_settings")
@Getter
public class AdminPortalSettings extends AbstractEntity {

    /** The tenant this selection governs, or null for the global default inherited by tenants with no own row. */
    @Column(name = "org_id")
    private UUID orgId;

    /** The session policy governing the console, or null = the acting admin's own resolved policy. */
    @Column(name = "session_policy_id")
    private UUID sessionPolicyId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AdminPortalSettings() {
    }

    /** Create a tenant's own row, seeded from the selection it currently inherits (copy-on-write). */
    public AdminPortalSettings(UUID orgId, UUID sessionPolicyId) {
        this.orgId = orgId;
        this.sessionPolicyId = sessionPolicyId;
    }

    /** Domain mutation (intent-revealing, not a JavaBean setter): select the console's policy and re-stamp. */
    public void selectPolicy(UUID sessionPolicyId) {
        this.sessionPolicyId = sessionPolicyId;
        this.updatedAt = Instant.now();
    }
}
