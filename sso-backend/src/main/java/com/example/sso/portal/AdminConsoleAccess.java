package com.example.sso.portal;

import java.util.UUID;

/**
 * Grants admin-console entry to a role under the assignment-based entry model. Used by tenant baseline
 * provisioning to assign the console to an org's own {@code ROLE_ORG_ADMIN}; the seeded global
 * assignments (ROLE_ADMIN / the global ROLE_ORG_ADMIN) cover the platform tier. The implementation
 * stays module-internal.
 */
public interface AdminConsoleAccess {

    /**
     * Idempotently assigns the admin console to {@code roleId}, scoped to {@code orgId} (null = a global
     * assignment). A no-op (logged) when the console client is not seeded yet.
     */
    void assignToRole(UUID roleId, UUID orgId);
}
