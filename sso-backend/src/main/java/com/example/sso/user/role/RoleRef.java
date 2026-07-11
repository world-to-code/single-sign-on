package com.example.sso.user.role;


import java.util.Set;
import java.util.UUID;

/**
 * Read-only reference to a role — the user module's public projection of a {@code Role}. Exposes the
 * role's identity and its permission NAMES (not the {@code Permission} entity). The backing entity
 * stays module-internal.
 */
public interface RoleRef {

    UUID getId();

    /** The org this role belongs to, or {@code null} for a global/system role shared across every tenant. */
    UUID getOrgId();

    String getName();

    /** Whether this is a protected system role (name/deletion locked; e.g. ROLE_ADMIN, ROLE_USER). */
    boolean isSystem();

    Set<String> getPermissionNames();
}
