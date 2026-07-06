package com.example.sso.session.internal.domain;

import com.example.sso.shared.domain.AbstractEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A named session-management policy: session lifetimes, step-up re-auth window, client binding,
 * max concurrent sessions, rotate-on-reauth, and session-cookie attributes. Like {@code AuthPolicy},
 * several policies may exist and be assigned to users and/or roles; the highest-{@code priority}
 * policy that applies to a user wins. The seeded {@code Default} (priority 0, unassigned/global) is
 * the non-editable fallback. No setters — mutated via intention-revealing methods.
 *
 * <p>This entity carries only its own columns. The user/role assignments and IP rules live in their own
 * {@link SessionPolicyUser}/{@link SessionPolicyRole}/{@link SessionPolicyIpRule} rows, written explicitly by
 * the service (no JPA collection cascade). The service composes those rows with this entity into the public
 * {@code SessionPolicyDetails} view.
 *
 * <p>Note: {@code cookieSameSite} is GLOBAL — only the Default policy's value is applied (the session
 * cookie is established before the user is known), so a per-policy SameSite is not meaningful. The
 * cookie's {@code Secure} attribute is not a policy field: it is enforced by deployment config
 * ({@code server.servlet.session.cookie.secure}) in production.
 */
@Entity
@Table(name = "session_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SessionPolicy extends AbstractEntity implements OrgOwned {

    // Tier-aware uniqueness (partial indexes in V47): global name, or (org_id, name) per tenant.
    @Column(nullable = false, length = 100)
    private String name;

    // NULL = a GLOBAL/default policy (the seeded Default, platform-wide policies); non-null = owned by that
    // organization (RLS-isolated; applies only to that org's sessions). Fixed at creation.
    @Column(name = "org_id")
    private UUID orgId;

    /** Higher value wins when multiple policies apply to a user. */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    @Embedded
    private SessionRules rules = SessionRules.defaults();

    /** A global/default policy (no owning org). */
    public SessionPolicy(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /** A policy owned by {@code orgId} (null = global). The org is fixed at creation. */
    public SessionPolicy(String name, int priority, UUID orgId) {
        this(name, priority);
        this.orgId = orgId;
    }

    public void update(int absoluteTimeoutMinutes, int idleTimeoutMinutes, int reauthIntervalMinutes,
                       String reauthFactors, int sensitiveReauthWindowMinutes, String stepUpFactors,
                       boolean bindClient, int maxConcurrentSessions,
                       boolean rotateOnReauth, String cookieSameSite) {
        this.rules = new SessionRules(absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes,
                reauthFactors, sensitiveReauthWindowMinutes, stepUpFactors, bindClient, maxConcurrentSessions,
                rotateOnReauth, cookieSameSite);
    }

    public void updatePriority(int priority) {
        this.priority = priority;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
