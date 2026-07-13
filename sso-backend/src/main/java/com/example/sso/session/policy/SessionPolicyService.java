package com.example.sso.session.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The session-policy contract owning admin CRUD, seeding/self-healing of the non-editable {@code Default}
 * fallback, and by-id/default lookups. Per-user resolution lives in {@link UserSessionPolicy} (off the
 * {@code policy_binding} matrix). The implementation (with its in-memory cache of policy scalars) stays
 * module-internal.
 */
public interface SessionPolicyService {

    String DEFAULT_NAME = "Default";

    /**
     * Priority of a tenant's provisioned "Default" — above the GLOBAL Default (priority 0) so it wins
     * resolution for that org, and low enough that a tenant's own higher-priority policies still override it.
     */
    int TENANT_DEFAULT_PRIORITY = 1;

    /** The non-editable Default fallback (also supplies the GLOBAL session-cookie attributes). */
    SessionPolicyDetails defaultPolicy();

    /** A policy by id (from the in-memory cache), or empty when it no longer exists. */
    Optional<SessionPolicyDetails> findById(UUID id);

    /** Ensures the Default fallback exists (idempotent; leaves an existing Default's settings intact). */
    void seedDefault();

    /**
     * Provisions a new tenant's own editable "Default" session policy (org-owned, {@link #TENANT_DEFAULT_PRIORITY},
     * baseline knobs, no assignments so it applies to every member). Idempotent. Called when an organization
     * is created so the tenant admin sees and can edit a policy instead of an empty page.
     *
     * <p>SYSTEM provisioning only: it writes to the given {@code orgId} with NO caller authorization check
     * (the org-created listener supplies a trusted id). Never wire it to a request-facing endpoint without a
     * tier/permission guard — that would be an unscoped cross-org write.
     */
    void provisionDefault(UUID orgId);

    List<SessionPolicyDetails> listAll();

    SessionPolicyDetails create(SessionPolicySpec spec);

    SessionPolicyDetails update(UUID id, SessionPolicyUpdate update);

    void delete(UUID id);
}
