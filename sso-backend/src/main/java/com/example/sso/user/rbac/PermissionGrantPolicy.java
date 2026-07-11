package com.example.sso.user.rbac;

/**
 * Whether the current actor may grant a given permission when building a role. Platform-only permissions
 * ({@link Permissions#PLATFORM}) are grantable only by a platform super-admin; a tenant (org) admin must
 * not be able to bundle one into a role and escalate. Implemented in the {@code admin} module (it depends
 * on the admin authorization policy + the security context); {@code RoleServiceImpl} depends on this
 * interface so the write-path guard stays module-boundary clean (DIP).
 */
public interface PermissionGrantPolicy {

    /** Whether the current actor is permitted to grant {@code permission}. */
    boolean mayGrant(String permission);

    /**
     * Whether the current actor may grant {@code permission} DIRECTLY to a user. Stricter than
     * {@link #mayGrant}: a non-super must also HOLD the permission themselves (grant-only-what-you-hold),
     * so the invariant survives even if the endpoint gate is ever bypassed or dropped.
     */
    boolean mayGrantDirectly(String permission);
}
