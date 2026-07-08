package com.example.sso.authpolicy;

import java.util.List;
import java.util.UUID;

/**
 * Write path of the authentication-policy engine: admin CRUD plus seeding/self-healing of the
 * non-editable Default fallback policy. Returns the public {@link AuthPolicyView}; the backing entity
 * stays module-internal.
 */
public interface AuthPolicyAdminService {

    /**
     * Ensures the fallback policy exists and is canonical (password then TOTP). Runs on every boot
     * and self-heals the Default if it was ever left in a bad state.
     */
    void seedDefault();

    /**
     * Priority of a tenant's provisioned "Default" — above the GLOBAL Default (priority 0) so it wins login
     * resolution for that org, and low enough that a tenant's own higher-priority policies still override it.
     */
    int TENANT_DEFAULT_PRIORITY = 1;

    /**
     * Provisions a new tenant's own editable "Default" login policy (org-owned, {@link #TENANT_DEFAULT_PRIORITY},
     * used-for-login, enrollment-at-login, steps password then TOTP, no assignments so it applies to every
     * member). Idempotent. Called when an organization is created so the tenant admin can edit its login flow.
     *
     * <p>SYSTEM provisioning only: it writes to the given {@code orgId} with NO caller authorization check
     * (the org-created listener supplies a trusted id). Never wire it to a request-facing endpoint without a
     * tier/permission guard — that would be an unscoped cross-org write.
     */
    void provisionDefault(UUID orgId);

    List<AuthPolicyView> listAll();

    AuthPolicyView create(AuthPolicySpec spec);

    AuthPolicyView update(UUID id, AuthPolicyUpdate update);

    void delete(UUID id);
}
