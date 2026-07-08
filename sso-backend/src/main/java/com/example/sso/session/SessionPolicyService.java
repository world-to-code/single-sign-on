package com.example.sso.session;

import com.example.sso.user.UserAccount;

import java.util.List;
import java.util.UUID;

/**
 * The single session-policy contract injected by the security filters, interceptors and controllers:
 * resolves the effective policy per user and owns admin CRUD plus seeding/self-healing of the
 * non-editable {@code Default} fallback. The implementation (with its in-memory cache) stays
 * module-internal.
 */
public interface SessionPolicyService {

    String DEFAULT_NAME = "Default";

    /**
     * Priority of a tenant's provisioned "Default" — above the GLOBAL Default (priority 0) so it wins
     * resolution for that org, and low enough that a tenant's own higher-priority policies still override it.
     */
    int TENANT_DEFAULT_PRIORITY = 1;

    /** The effective session policy for the user: highest-priority assigned/global, else Default. */
    SessionPolicyDetails resolveForUser(UserAccount user);

    /** Resolves by username for the filter/interceptor callers; Default if the user is unknown. */
    SessionPolicyDetails resolveForUsername(String username);

    /** The non-editable Default fallback (also supplies the GLOBAL session-cookie attributes). */
    SessionPolicyDetails defaultPolicy();

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
