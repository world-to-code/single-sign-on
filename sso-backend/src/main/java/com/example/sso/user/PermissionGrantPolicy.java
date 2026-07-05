package com.example.sso.user;

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
}
