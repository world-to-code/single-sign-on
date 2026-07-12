package com.example.sso.portal.binding;

import java.util.Optional;
import java.util.UUID;

/**
 * The session policy governing the ADMIN CONSOLE for the acting tenant, backed by the {@code policy_binding}
 * matrix (a {@code PORTAL}/{@code admin} all-subjects binding). The admin-settings endpoint reads and writes
 * it here so "which session policy governs the console" is one row in the unified model, not a parallel
 * settings table. Isolation and tier rules are enforced on write; a tenant sees its own selection, else the
 * GLOBAL default it inherits.
 */
public interface AdminConsoleBinding {

    /** The acting tenant's console session policy (its own binding, else the inherited global), or empty
     *  = "the acting admin's own resolved policy". This is the tenant-wide selection; a more specific
     *  role/user admin-console binding, if one exists, is what actually governs and is NOT reflected here. */
    Optional<UUID> sessionPolicyId();

    /** Selects (or, when {@code null}, clears) the acting tenant's admin-console session policy. The policy
     *  must be one of the acting tenant's own tier; only the platform context may edit the global default. */
    void setSessionPolicy(UUID sessionPolicyId);
}
